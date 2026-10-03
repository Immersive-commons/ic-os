package org.immersivecommons.companion.sample;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/** Empty on purpose: being bound by the bus is the whole job (keeps us unfrozen). */
public class CompanionHostService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(Companion.TAG, "bus bound the host service");
        return new Binder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(Companion.TAG, "bus unbound the host service");
        return false;
    }
}
