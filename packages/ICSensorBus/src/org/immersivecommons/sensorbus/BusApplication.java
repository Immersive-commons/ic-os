package org.immersivecommons.sensorbus;

import android.app.Application;
import android.content.Intent;

/**
 * The app is persistent, so system_server starts this process during boot and restarts
 * it if it dies. Initialising the bus here (not on first bind) means the stream manifest
 * is discovered and the service is up before any companion asks.
 */
public class BusApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Bus.get(this);
        startService(new Intent(this, SensorBusService.class));
    }
}
