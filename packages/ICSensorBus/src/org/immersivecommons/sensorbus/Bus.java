package org.immersivecommons.sensorbus;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The bus: the one process that holds raw sensors. It discovers the device's stream
 * manifest at start, serves consent-gated streams to companions over ISensorBus, and
 * writes every decision to the ledger. One instance per process, shared by the bound
 * service (companions) and the control provider (shell).
 */
final class Bus {
    static final String TAG = "ICSensorBus";
    /** A companion may expose a service with this action, protected by BIND_COMPANION. */
    static final String COMPANION_HOST = "org.immersivecommons.sensorbus.COMPANION_HOST";
    static final String BIND_COMPANION = "org.immersivecommons.permission.BIND_COMPANION";
    static final String MOTION_STATE = "activity.motion_state";
    private static final int CLOSED_CLIENT_DIED = 5; // ledger only: nobody left to tell

    private static Bus instance;

    static synchronized Bus get(Context c) {
        if (instance == null) instance = new Bus(c.getApplicationContext());
        return instance;
    }

    // type, stream name, least tier that may open it
    private static final Object[][] RAW = {
        {Sensor.TYPE_ACCELEROMETER, "motion.accel", "raw"},
        {Sensor.TYPE_GYROSCOPE, "motion.gyro", "raw"},
        {Sensor.TYPE_MAGNETIC_FIELD, "motion.mag", "raw"},
        {Sensor.TYPE_GRAVITY, "motion.gravity", "feature"},
        {Sensor.TYPE_LINEAR_ACCELERATION, "motion.linear_accel", "feature"},
        {Sensor.TYPE_ROTATION_VECTOR, "motion.rotation", "feature"},
        {Sensor.TYPE_GAME_ROTATION_VECTOR, "motion.game_rotation", "feature"},
        {Sensor.TYPE_LIGHT, "env.light", "raw"},
        {Sensor.TYPE_PRESSURE, "env.pressure", "raw"},
        {Sensor.TYPE_AMBIENT_TEMPERATURE, "env.temperature", "raw"},
        {Sensor.TYPE_RELATIVE_HUMIDITY, "env.humidity", "raw"},
        {Sensor.TYPE_PROXIMITY, "env.proximity", "raw"},
    };

    static final class Stream {
        final String name, kind, tier, source;
        final int maxRateHz;
        final Sensor sensor; // the sensor a session registers (derived: its input)

        Stream(String name, String kind, String tier, int maxRateHz, String source, Sensor sensor) {
            this.name = name; this.kind = kind; this.tier = tier;
            this.maxRateHz = maxRateHz; this.source = source; this.sensor = sensor;
        }

        StreamInfo info() {
            StreamInfo i = new StreamInfo();
            i.name = name; i.kind = kind; i.tier = tier; i.maxRateHz = maxRateHz; i.source = source;
            return i;
        }
    }

    private final Context ctx;
    private final File dir;
    private final SensorManager sm;
    private final PackageManager pm;
    private final Handler handler;
    private final Grants grants;
    private final Ledger ledger;
    private final Map<String, Stream> streams = new LinkedHashMap<>();
    private final Map<Integer, Session> sessions = new TreeMap<>();
    private final Map<Integer, ServiceConnection> keepalives = new HashMap<>(); // by uid
    private int nextHandle = 1;
    private boolean paused, keepalive;
    final ISensorBus.Stub binder = new Api();

    private Bus(Context app) {
        ctx = app;
        // device-protected storage: the persistent bus starts before the user unlocks
        dir = app.createDeviceProtectedStorageContext().getFilesDir();
        dir.mkdirs();
        grants = new Grants(dir);
        ledger = new Ledger(dir);
        pm = app.getPackageManager();
        sm = app.getSystemService(SensorManager.class);
        HandlerThread t = new HandlerThread("sensorbus");
        t.start();
        handler = new Handler(t.getLooper());
        discover();
        paused = new File(dir, "paused").exists();
        keepalive = !new File(dir, "keepalive.off").exists();
        ledger.append("start", "uptimeSec", SystemClock.elapsedRealtime() / 1000,
            "streams", streams.size(), "paused", paused);
    }

    private void discover() {
        for (Object[] row : RAW) {
            Sensor s = sm.getDefaultSensor((Integer) row[0]);
            if (s == null) continue;
            int maxHz = s.getMinDelay() > 0 ? 1_000_000 / s.getMinDelay() : 0;
            streams.put((String) row[1],
                new Stream((String) row[1], "sensor", (String) row[2], maxHz, s.getName(), s));
        }
        Stream accel = streams.get("motion.accel");
        if (accel != null) {
            streams.put(MOTION_STATE,
                new Stream(MOTION_STATE, "derived", "event", 0, accel.name, accel.sensor));
        }
    }

    // ---- companion API -------------------------------------------------------------

    private final class Api extends ISensorBus.Stub {
        @Override
        public StreamInfo[] listStreams() {
            synchronized (Bus.this) {
                StreamInfo[] out = new StreamInfo[streams.size()];
                int i = 0;
                for (Stream s : streams.values()) out[i++] = s.info();
                return out;
            }
        }

        @Override
        public boolean hasGrant(String stream) {
            int uid = Binder.getCallingUid();
            synchronized (Bus.this) {
                Stream st = streams.get(stream);
                return st != null && grantFor(uid, st) != null;
            }
        }

        @Override
        public int openStream(String stream, int rateHz, int durationSec, IStreamCallback cb) {
            return open(Binder.getCallingUid(), stream, rateHz, durationSec, cb);
        }

        @Override
        public void closeStream(int handle) {
            int uid = Binder.getCallingUid();
            synchronized (Bus.this) {
                Session s = sessions.get(handle);
                if (s != null && s.uid == uid) close(s, IStreamCallback.CLOSED_BY_CLIENT);
            }
        }
    }

    /** The caller's packages, from its uid only. A shared uid yields several. */
    private String[] packagesFor(int uid) {
        String[] p = pm.getPackagesForUid(uid);
        return p == null ? new String[0] : p;
    }

    private Grants.Grant grantFor(int uid, Stream st) {
        long now = System.currentTimeMillis();
        for (String p : packagesFor(uid)) {
            Grants.Grant g = grants.find(p, st.name);
            if (g != null && g.live(now)
                    && Grants.tierRank(g.tier) >= Grants.tierRank(st.tier)) {
                return g;
            }
        }
        return null;
    }

    private synchronized int open(int uid, String name, int rateHz, int durationSec,
                                  IStreamCallback cb) {
        Stream st = streams.get(name);
        Grants.Grant g = null;
        int err = 0;
        if (paused) err = ISensorBus.PAUSED;
        else if (st == null) err = ISensorBus.UNKNOWN_STREAM;
        else if (cb == null || rateHz <= 0 || durationSec <= 0) err = ISensorBus.BAD_ARGS;
        else if ((g = grantFor(uid, st)) == null) err = ISensorBus.NO_GRANT;
        else if (rateHz > g.maxRateHz) err = ISensorBus.RATE_ABOVE_GRANT;
        if (err != 0) {
            ledger.append("deny", "package", String.join(",", packagesFor(uid)), "uid", uid,
                "stream", name, "rateHz", rateHz, "reason", errorName(err));
            return err;
        }
        int rate = st.maxRateHz > 0 ? Math.min(rateHz, st.maxRateHz) : rateHz;
        long lifeMs = Math.min(durationSec * 1000L, g.expiresAt - System.currentTimeMillis());
        Session s = new Session(nextHandle++, uid, g.pkg, st, rate, cb, lifeMs);
        try {
            cb.asBinder().linkToDeath(s, 0);
        } catch (RemoteException e) {
            return ISensorBus.BAD_ARGS; // the caller died while asking
        }
        sessions.put(s.handle, s);
        // derived motion state samples its input at a fixed 50 Hz, whatever the event rate
        int periodUs = st.kind.equals("derived") ? 20_000 : 1_000_000 / rate;
        sm.registerListener(s, st.sensor, periodUs, handler);
        handler.postDelayed(s.expire, lifeMs);
        ledger.append("open", "package", g.pkg, "uid", uid, "handle", s.handle, "stream", name,
            "rateHz", rate, "requestedHz", rateHz, "tier", g.tier, "lifeSec", lifeMs / 1000);
        bindKeepalive(uid, g.pkg);
        return s.handle;
    }

    private synchronized void close(Session s, int reason) {
        if (sessions.remove(s.handle) == null) return;
        s.open = false;
        sm.unregisterListener(s);
        handler.removeCallbacks(s.expire);
        s.cb.asBinder().unlinkToDeath(s, 0);
        if (reason != CLOSED_CLIENT_DIED) {
            try {
                s.cb.onClosed(s.handle, reason);
            } catch (RemoteException e) {
                // client gone; the ledger still records the close
            }
        }
        double secs = (SystemClock.elapsedRealtime() - s.openedAt) / 1000.0;
        ledger.append("close", "package", s.pkg, "uid", s.uid, "handle", s.handle,
            "stream", s.stream.name, "reason", closeName(reason), "delivered", s.delivered,
            "failed", s.failed, "secs", Math.round(secs * 10) / 10.0,
            "avgHz", Math.round(s.delivered / Math.max(secs, 0.001) * 10) / 10.0);
        boolean uidStillOpen = false;
        for (Session o : sessions.values()) uidStillOpen |= o.uid == s.uid;
        if (!uidStillOpen) unbindKeepalive(s.uid);
    }

    /**
     * Keep a companion's process unfrozen while it holds a stream: the bus binds the
     * companion's COMPANION_HOST service with BIND_NOT_FOREGROUND, which stops the
     * cached-app freezer from parking it without making its uid foreground (so it still
     * cannot read sensors directly). Only services that require BIND_COMPANION qualify.
     */
    private void bindKeepalive(int uid, String pkg) {
        if (!keepalive || keepalives.containsKey(uid)) return;
        List<ResolveInfo> found = pm.queryIntentServices(
            new Intent(COMPANION_HOST).setPackage(pkg), 0);
        if (found.isEmpty()) return;
        ServiceInfo si = found.get(0).serviceInfo;
        if (!BIND_COMPANION.equals(si.permission)) {
            Log.w(TAG, pkg + " host service not protected by " + BIND_COMPANION + "; not bound");
            return;
        }
        ServiceConnection c = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {}
            @Override public void onServiceDisconnected(ComponentName n) {}
        };
        Intent i = new Intent(COMPANION_HOST).setComponent(new ComponentName(si.packageName, si.name));
        if (ctx.bindService(i, c, Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND)) {
            keepalives.put(uid, c);
            Log.i(TAG, "keepalive bound " + pkg);
        }
    }

    private void unbindKeepalive(int uid) {
        ServiceConnection c = keepalives.remove(uid);
        if (c != null) ctx.unbindService(c);
    }

    // ---- sessions --------------------------------------------------------------------

    private final class Session implements SensorEventListener, IBinder.DeathRecipient {
        final int handle, uid, rateHz;
        final String pkg;
        final Stream stream;
        final IStreamCallback cb;
        final long periodNs, openedAt, endsAt;
        final Runnable expire = () -> close(this, IStreamCallback.CLOSED_EXPIRED);
        volatile boolean open = true;
        long delivered, failed, nextDueNs, winStartNs, winCount;
        double lastHz;
        // activity.motion_state: 1 s window of |accel|
        final float[] mags = new float[50];
        int n;
        String state;

        Session(int handle, int uid, String pkg, Stream stream, int rateHz, IStreamCallback cb,
                long lifeMs) {
            this.handle = handle; this.uid = uid; this.pkg = pkg; this.stream = stream;
            this.rateHz = rateHz; this.cb = cb;
            periodNs = 1_000_000_000L / rateHz;
            openedAt = SystemClock.elapsedRealtime();
            endsAt = openedAt + lifeMs;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (!open) return;
            if (stream.kind.equals("derived")) {
                motion(e);
                return;
            }
            long ts = e.timestamp;
            // SensorManager may run the sensor faster than asked (another client wants
            // more), so the bus enforces the rate itself.
            if (nextDueNs != 0 && ts < nextDueNs - periodNs / 10) return;
            nextDueNs = (nextDueNs == 0 || ts - nextDueNs > periodNs) ? ts + periodNs
                : nextDueNs + periodNs;
            try {
                cb.onSample(handle, ts, e.values.clone());
                delivered++;
                countWindow(ts);
            } catch (RemoteException ex) {
                failed++;
            }
        }

        private void countWindow(long ts) {
            if (winStartNs == 0) { winStartNs = ts; return; }
            winCount++;
            if (ts - winStartNs >= 5_000_000_000L) {
                lastHz = winCount * 1e9 / (ts - winStartNs);
                winStartNs = ts;
                winCount = 0;
            }
        }

        /** still/moving from the std-dev of |accel| over 1 s, with hysteresis; emits on change. */
        private void motion(SensorEvent e) {
            float[] v = e.values;
            mags[n++ % mags.length] = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (n < mags.length || n % 25 != 0) return;
            double mean = 0, var = 0;
            for (float m : mags) mean += m;
            mean /= mags.length;
            for (float m : mags) var += (m - mean) * (m - mean);
            double sd = Math.sqrt(var / mags.length);
            String next = state == null ? (sd > 0.2 ? "moving" : "still")
                : state.equals("still") ? (sd > 0.25 ? "moving" : "still")
                : (sd < 0.15 ? "still" : "moving");
            if (next.equals(state)) return;
            state = next;
            try {
                cb.onState(handle, e.timestamp, next);
                delivered++;
            } catch (RemoteException ex) {
                failed++;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {}

        @Override
        public void binderDied() {
            close(this, CLOSED_CLIENT_DIED);
        }
    }

    // ---- shell control (ControlProvider) ---------------------------------------------

    synchronized String control(String method, String[] a, String by) throws IOException {
        switch (method) {
            case "grant": {
                if (a.length != 5) throw new IllegalArgumentException(
                    "grant <package> <stream> <maxRateHz> <event|feature|raw> <ttlSec>");
                Stream st = streams.get(a[1]);
                if (st == null) throw new IllegalArgumentException("unknown stream " + a[1]);
                int rate = Integer.parseInt(a[2]);
                long ttl = Long.parseLong(a[4]);
                if (Grants.tierRank(a[3]) < 0 || rate <= 0 || ttl <= 0)
                    throw new IllegalArgumentException("bad tier, rate or ttl");
                if (Grants.tierRank(a[3]) < Grants.tierRank(st.tier))
                    throw new IllegalArgumentException(a[1] + " needs tier " + st.tier);
                long expiresAt = System.currentTimeMillis() + ttl * 1000;
                grants.put(new Grants.Grant(a[0], a[1], rate, a[3], expiresAt));
                ledger.append("grant", "package", a[0], "stream", a[1], "maxRateHz", rate,
                    "tier", a[3], "expiresAt", expiresAt, "by", by);
                return "granted " + a[0] + " " + a[1] + " <=" + rate + "Hz " + a[3] + " for " + ttl + "s";
            }
            case "revoke": {
                if (a.length < 1 || a.length > 2)
                    throw new IllegalArgumentException("revoke <package> [stream|*]");
                String stream = a.length == 2 ? a[1] : "*";
                int n = grants.revoke(a[0], stream);
                int closed = 0;
                for (Session s : new ArrayList<>(sessions.values())) {
                    if (s.pkg.equals(a[0]) && (stream.equals("*") || s.stream.name.equals(stream))) {
                        close(s, IStreamCallback.CLOSED_REVOKED);
                        closed++;
                    }
                }
                ledger.append("revoke", "package", a[0], "stream", stream, "grants", n,
                    "closed", closed, "by", by);
                return "revoked " + n + " grant(s), closed " + closed + " stream(s)";
            }
            case "pause": {
                paused = true;
                new File(dir, "paused").createNewFile();
                int closed = 0;
                for (Session s : new ArrayList<>(sessions.values())) {
                    close(s, IStreamCallback.CLOSED_PAUSED);
                    closed++;
                }
                ledger.append("pause", "closed", closed, "by", by);
                return "paused; closed " + closed + " stream(s)";
            }
            case "resume": {
                paused = false;
                new File(dir, "paused").delete();
                ledger.append("resume", "by", by);
                return "resumed";
            }
            case "keepalive": {
                if (a.length != 1 || !(a[0].equals("on") || a[0].equals("off")))
                    throw new IllegalArgumentException("keepalive on|off");
                keepalive = a[0].equals("on");
                File off = new File(dir, "keepalive.off");
                if (keepalive) off.delete(); else off.createNewFile();
                return "keepalive " + a[0] + " (applies to streams opened from now)";
            }
            default:
                throw new IllegalArgumentException(
                    "methods: grant, revoke, pause, resume, keepalive");
        }
    }

    // ---- dumpsys ---------------------------------------------------------------------

    synchronized void dump(PrintWriter pw, String[] args) {
        int tail = 20;
        if (args != null && args.length == 2 && args[0].equals("ledger")) tail = Integer.parseInt(args[1]);
        long now = System.currentTimeMillis(), up = SystemClock.elapsedRealtime();
        pw.println("IC sensor bus v0  paused=" + paused + " keepalive=" + keepalive
            + " data=" + dir);
        pw.println("streams (" + streams.size() + "):");
        for (Stream s : streams.values()) {
            pw.printf("  %-22s %-7s tier=%-7s maxHz=%-4d %s%n", s.name, s.kind, s.tier,
                s.maxRateHz, s.source);
        }
        pw.println("grants:");
        for (Grants.Grant g : grants.all()) {
            pw.printf("  %s %s <=%dHz %s %s%n", g.pkg, g.stream, g.maxRateHz, g.tier,
                g.live(now) ? "expires in " + (g.expiresAt - now) / 1000 + "s" : "EXPIRED");
        }
        pw.println("active streams (" + sessions.size() + "):");
        for (Session s : sessions.values()) {
            double secs = (up - s.openedAt) / 1000.0;
            pw.printf("  #%d %s uid=%d %s @%dHz delivered=%d failed=%d avgHz=%.1f last5sHz=%.1f"
                    + " state=%s endsIn=%ds%n", s.handle, s.pkg, s.uid, s.stream.name, s.rateHz,
                s.delivered, s.failed, s.delivered / Math.max(secs, 0.001), s.lastHz,
                s.state, (s.endsAt - up) / 1000);
        }
        pw.println("keepalive bindings (uids): " + keepalives.keySet());
        pw.println("ledger (" + ledger.lines() + " lines, " + dir + "/ledger.jsonl), last " + tail + ":");
        for (String l : ledger.tail(tail)) pw.println("  " + l);
    }

    static String errorName(int err) {
        switch (err) {
            case ISensorBus.NO_GRANT: return "NO_GRANT";
            case ISensorBus.UNKNOWN_STREAM: return "UNKNOWN_STREAM";
            case ISensorBus.RATE_ABOVE_GRANT: return "RATE_ABOVE_GRANT";
            case ISensorBus.PAUSED: return "PAUSED";
            case ISensorBus.BAD_ARGS: return "BAD_ARGS";
            default: return "ERROR_" + err;
        }
    }

    static String closeName(int reason) {
        switch (reason) {
            case IStreamCallback.CLOSED_BY_CLIENT: return "client";
            case IStreamCallback.CLOSED_EXPIRED: return "expired";
            case IStreamCallback.CLOSED_PAUSED: return "paused";
            case IStreamCallback.CLOSED_REVOKED: return "revoked";
            case CLOSED_CLIENT_DIED: return "client_died";
            default: return "reason_" + reason;
        }
    }
}
