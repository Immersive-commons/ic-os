package org.immersivecommons.sensorbus;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Headless probe for the IC sensor bus design questions:
 *  1. Which privileged permissions does a platform-signed app actually hold?
 *  2. What event rate does it get with no visible UI, at the fastest requested rate?
 *
 * Start:  am start-service -n org.immersivecommons.sensorbus/.ProbeService --ei period_us 0
 * Read:   logcat -s ICSensorBus
 */
public class ProbeService extends Service implements SensorEventListener {
    private static final String TAG = "ICSensorBus";
    private static final int[] TYPES = {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
    };
    private static final String[] PERMS = {
        "android.permission.HIGH_SAMPLING_RATE_SENSORS",
        "android.permission.ACCESS_CONTEXT_HUB",
        "android.permission.CAPTURE_AUDIO_HOTWORD",
        "android.permission.MANAGE_SENSOR_PRIVACY",
        "android.permission.POWER_SAVER",
        "android.permission.INTERACT_ACROSS_USERS",
    };

    private HandlerThread thread;
    private Handler handler;
    private SensorManager sm;
    private final long[] counts = new long[64];
    private long windowStart;
    private int reports;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (thread != null) return START_STICKY;
        for (String p : PERMS) {
            boolean granted = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "perm " + p + " granted=" + granted);
        }
        int periodUs = intent == null ? 0 : intent.getIntExtra("period_us", 0);
        thread = new HandlerThread("sensorbus");
        thread.start();
        handler = new Handler(thread.getLooper());
        sm = getSystemService(SensorManager.class);
        for (int type : TYPES) {
            Sensor s = sm.getDefaultSensor(type);
            if (s == null) {
                Log.i(TAG, "sensor type=" + type + " absent");
                continue;
            }
            boolean ok = sm.registerListener(this, s, periodUs, handler);
            Log.i(TAG, "register " + s.getName() + " minDelayUs=" + s.getMinDelay()
                + " maxRateHz=" + (s.getMinDelay() > 0 ? 1_000_000 / s.getMinDelay() : 0)
                + " requestedUs=" + periodUs + " ok=" + ok);
        }
        windowStart = SystemClock.elapsedRealtime();
        handler.postDelayed(this::report, 5000);
        return START_STICKY;
    }

    private void report() {
        long now = SystemClock.elapsedRealtime();
        double secs = (now - windowStart) / 1000.0;
        StringBuilder sb = new StringBuilder("rates_hz");
        for (int type : TYPES) {
            sb.append(' ').append(type).append('=')
              .append(String.format("%.1f", counts[type] / secs));
            counts[type] = 0;
        }
        Log.i(TAG, sb.toString());
        windowStart = now;
        if (++reports < 12) handler.postDelayed(this::report, 5000);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t >= 0 && t < counts.length) counts[t]++;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (sm != null) sm.unregisterListener(this);
        if (thread != null) thread.quitSafely();
    }
}
