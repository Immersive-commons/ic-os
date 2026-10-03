package org.immersivecommons.sensorbus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.io.IOException;

/**
 * Shell-only control path for grants and the kill switch:
 *   content call --uri content://org.immersivecommons.sensorbus.control \
 *       --method grant --arg "<package> <stream> <maxRateHz> <tier> <ttlSec>"
 * A ContentProvider.call() runs on a binder thread, so Binder.getCallingUid() is the
 * real caller; anything but shell (2000) or root (0) is refused. (dumpsys cannot do
 * this: the app's dump() is invoked by system_server, so the shell's uid is not visible.)
 */
public class ControlProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID) {
            throw new SecurityException("sensor bus control is shell-only (uid " + uid + ")");
        }
        String[] a = arg == null || arg.trim().isEmpty() ? new String[0] : arg.trim().split("\\s+");
        Bundle out = new Bundle();
        try {
            out.putString("result", Bus.get(getContext()).control(method, a,
                uid == Process.ROOT_UID ? "root" : "shell"));
        } catch (IllegalArgumentException | IOException e) {
            out.putString("error", e.getMessage());
        }
        return out;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
