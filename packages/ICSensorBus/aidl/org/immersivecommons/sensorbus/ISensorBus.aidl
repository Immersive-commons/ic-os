package org.immersivecommons.sensorbus;

import org.immersivecommons.sensorbus.IStreamCallback;
import org.immersivecommons.sensorbus.StreamInfo;

/**
 * The IC sensor bus. Bind with action org.immersivecommons.sensorbus.BIND,
 * package org.immersivecommons.sensorbus. The caller is always identified by its
 * binder uid; there is no package-name argument to lie with.
 */
interface ISensorBus {
    /** openStream errors (a handle is always > 0). */
    const int NO_GRANT = -1;
    const int UNKNOWN_STREAM = -2;
    const int RATE_ABOVE_GRANT = -3;
    const int PAUSED = -4;
    const int BAD_ARGS = -5;

    /** Every stream this device can serve: raw sensors found at boot plus derived streams. */
    StreamInfo[] listStreams();

    /** True if the CALLING package holds an unexpired grant for this stream. */
    boolean hasGrant(String stream);

    /**
     * Open a stream at up to rateHz for durationSec, delivered to callback.
     * Ends at min(now + durationSec, grant expiry). Returns a handle or an error above.
     */
    int openStream(String stream, int rateHz, int durationSec, IStreamCallback callback);

    void closeStream(int handle);
}
