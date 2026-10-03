package org.immersivecommons.sensorbus;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Companions bind here (action org.immersivecommons.sensorbus.BIND). Kept started from
 * boot by BusApplication so `dumpsys activity service org.immersivecommons.sensorbus`
 * always has a service record to print.
 */
public class SensorBusService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bus.get(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return Bus.get(this).binder;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        Bus.get(this).dump(pw, args);
    }
}
