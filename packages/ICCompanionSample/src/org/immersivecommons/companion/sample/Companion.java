package org.immersivecommons.companion.sample;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.immersivecommons.sensorbus.ISensorBus;
import org.immersivecommons.sensorbus.IStreamCallback;
import org.immersivecommons.sensorbus.StreamInfo;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Binds the bus, opens motion.accel at 50 Hz for 60 s (launch extra durationSec overrides)
 * plus activity.motion_state, and
 * every 5 s logs the rate it actually receives from the bus next to the rate it gets by
 * reading the accelerometer DIRECTLY through SensorManager. In the foreground both are
 * ~50 Hz; in the background stock SensorService gives the direct listener nothing.
 */
final class Companion implements ServiceConnection {
    static final String TAG = "ICCompanion";
    private static final String BUS = "org.immersivecommons.sensorbus";
    private static Companion instance;

    static synchronized void start(Context app, int durationSec) {
        if (instance != null) return;
        instance = new Companion(app, durationSec);
    }

    private final Context ctx;
    private final Handler handler;
    private final AtomicLong busSamples = new AtomicLong(), directSamples = new AtomicLong();
    private final AtomicLong states = new AtomicLong();
    private volatile String state = "-";
    private ISensorBus bus;
    private long windowStart;
    private final int durationSec;
    private int reports;

    private Companion(Context app, int durationSec) {
        ctx = app;
        this.durationSec = durationSec;
        HandlerThread t = new HandlerThread("companion");
        t.start();
        handler = new Handler(t.getLooper());
        boolean ok = ctx.bindService(new Intent(BUS + ".BIND").setPackage(BUS), this,
            Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bind bus ok=" + ok);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder b) {
        bus = ISensorBus.Stub.asInterface(b);
        handler.post(this::run);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.i(TAG, "bus disconnected");
    }

    private final IStreamCallback.Stub callback = new IStreamCallback.Stub() {
        @Override public void onSample(int handle, long ts, float[] v) { busSamples.incrementAndGet(); }
        @Override public void onState(int handle, long ts, String s) {
            states.incrementAndGet();
            state = s;
            Log.i(TAG, "state handle=" + handle + " -> " + s);
        }
        @Override public void onClosed(int handle, int reason) {
            Log.i(TAG, "closed handle=" + handle + " reason=" + reason);
        }
    };

    private void run() {
        try {
            StringBuilder sb = new StringBuilder("streams:");
            for (StreamInfo s : bus.listStreams()) sb.append(' ').append(s.name).append('/').append(s.tier);
            Log.i(TAG, sb.toString());
            // the control path must refuse an app, even for a harmless call
            try {
                ctx.getContentResolver().call(Uri.parse("content://" + BUS + ".control"),
                    "pause", null, null);
                Log.i(TAG, "control call from app: ACCEPTED (gate broken)");
            } catch (SecurityException e) {
                Log.i(TAG, "control call from app: refused (" + e.getMessage() + ")");
            }
            Log.i(TAG, "hasGrant motion.accel=" + bus.hasGrant("motion.accel")
                + " activity.motion_state=" + bus.hasGrant("activity.motion_state"));
            Log.i(TAG, "open motion.accel@100Hz -> " + result(bus.openStream("motion.accel", 100, durationSec, callback)));
            Log.i(TAG, "open motion.accel@50Hz for " + durationSec + "s -> "
                + result(bus.openStream("motion.accel", 50, durationSec, callback)));
            Log.i(TAG, "open activity.motion_state -> "
                + result(bus.openStream("activity.motion_state", 1, durationSec, callback)));
        } catch (RemoteException e) {
            Log.e(TAG, "bus call failed", e);
            return;
        }
        SensorManager sm = ctx.getSystemService(SensorManager.class);
        sm.registerListener(new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) { directSamples.incrementAndGet(); }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        }, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), 20_000, handler);
        windowStart = SystemClock.elapsedRealtime();
        handler.postDelayed(this::report, 5000);
    }

    private void report() {
        long now = SystemClock.elapsedRealtime();
        double secs = (now - windowStart) / 1000.0;
        ActivityManager.RunningAppProcessInfo me = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(me);
        Log.i(TAG, String.format("rate bus.accel=%.1fHz direct.accel=%.1fHz state=%s states=%d"
                + " importance=%d window=%.1fs", busSamples.getAndSet(0) / secs,
            directSamples.getAndSet(0) / secs, state, states.get(), me.importance, secs));
        windowStart = now;
        // report 30 s past the end of the stream so the close is visible
        if (++reports < durationSec / 5 + 6) handler.postDelayed(this::report, 5000);
    }

    private static String result(int r) {
        switch (r) {
            case ISensorBus.NO_GRANT: return "NO_GRANT";
            case ISensorBus.UNKNOWN_STREAM: return "UNKNOWN_STREAM";
            case ISensorBus.RATE_ABOVE_GRANT: return "RATE_ABOVE_GRANT";
            case ISensorBus.PAUSED: return "PAUSED";
            case ISensorBus.BAD_ARGS: return "BAD_ARGS";
            default: return "handle " + r;
        }
    }
}
