package org.immersivecommons.sensorbus;

oneway interface IStreamCallback {
    /** onClosed reasons. */
    const int CLOSED_BY_CLIENT = 1;
    const int CLOSED_EXPIRED = 2;
    const int CLOSED_PAUSED = 3;
    const int CLOSED_REVOKED = 4;

    /** A raw or feature sample; timestampNs is SensorEvent.timestamp (elapsedRealtimeNanos base). */
    void onSample(int handle, long timestampNs, in float[] values);

    /** An event-tier stream changed state, e.g. activity.motion_state -> "moving". */
    void onState(int handle, long timestampNs, String state);

    void onClosed(int handle, int reason);
}
