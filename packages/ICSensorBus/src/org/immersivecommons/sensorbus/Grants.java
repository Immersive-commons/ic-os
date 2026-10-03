package org.immersivecommons.sensorbus;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Grants v0: {package, stream, maxRateHz, tier, expiresAt} rows in grants.json, written
 * atomically. Only the shell control path changes them (see ControlProvider).
 */
final class Grants {
    static final String[] TIERS = {"event", "feature", "raw"};

    static final class Grant {
        final String pkg, stream, tier;
        final int maxRateHz;
        final long expiresAt; // wall clock ms

        Grant(String pkg, String stream, int maxRateHz, String tier, long expiresAt) {
            this.pkg = pkg; this.stream = stream; this.maxRateHz = maxRateHz;
            this.tier = tier; this.expiresAt = expiresAt;
        }

        boolean live(long now) { return expiresAt > now; }

        JSONObject json() throws JSONException {
            return new JSONObject().put("package", pkg).put("stream", stream)
                .put("maxRateHz", maxRateHz).put("tier", tier).put("expiresAt", expiresAt);
        }
    }

    static int tierRank(String tier) {
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(tier)) return i;
        return -1;
    }

    private final AtomicFile file;
    private final List<Grant> rows = new ArrayList<>();

    Grants(File dir) {
        file = new AtomicFile(new File(dir, "grants.json"));
        try {
            JSONArray a = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                rows.add(new Grant(o.getString("package"), o.getString("stream"),
                    o.getInt("maxRateHz"), o.getString("tier"), o.getLong("expiresAt")));
            }
        } catch (IOException | JSONException e) {
            // no file yet (or unreadable): start with no grants, which denies everything
        }
    }

    Grant find(String pkg, String stream) {
        for (Grant g : rows) if (g.pkg.equals(pkg) && g.stream.equals(stream)) return g;
        return null;
    }

    List<Grant> all() { return new ArrayList<>(rows); }

    void put(Grant g) throws IOException {
        Grant old = find(g.pkg, g.stream);
        if (old != null) rows.remove(old);
        rows.add(g);
        save();
    }

    /** stream "*" revokes every stream of the package. Returns how many rows went. */
    int revoke(String pkg, String stream) throws IOException {
        int n = 0;
        for (Iterator<Grant> it = rows.iterator(); it.hasNext(); ) {
            Grant g = it.next();
            if (g.pkg.equals(pkg) && (stream.equals("*") || g.stream.equals(stream))) {
                it.remove();
                n++;
            }
        }
        if (n > 0) save();
        return n;
    }

    private void save() throws IOException {
        JSONArray a = new JSONArray();
        try {
            for (Grant g : rows) a.put(g.json());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        FileOutputStream out = file.startWrite();
        try {
            out.write(a.toString(1).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | JSONException e) {
            file.failWrite(out);
            throw new IOException(e);
        }
    }
}
