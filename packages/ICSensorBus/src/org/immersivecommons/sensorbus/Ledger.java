package org.immersivecommons.sensorbus;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Consent ledger: append-only JSONL, one line per decision the bus made about a
 * stream (open, close, deny, pause, resume, grant, revoke, start). Never rewritten.
 */
final class Ledger {
    private final File file;

    Ledger(File dir) {
        file = new File(dir, "ledger.jsonl");
    }

    /** kv alternates key, value. "t" (wall ms) and "event" come first on every line. */
    synchronized void append(String event, Object... kv) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", System.currentTimeMillis()).put("event", event);
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        String line = o.toString();
        Log.i(Bus.TAG, "ledger " + line);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(Bus.TAG, "ledger write failed", e);
        }
    }

    synchronized Deque<String> tail(int n) {
        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            for (String l; (l = r.readLine()) != null; ) {
                lines.addLast(l);
                if (lines.size() > n) lines.removeFirst();
            }
        } catch (IOException e) {
            // empty ledger
        }
        return lines;
    }

    synchronized long lines() {
        long n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            while (r.readLine() != null) n++;
        } catch (IOException e) {
            // empty ledger
        }
        return n;
    }
}
