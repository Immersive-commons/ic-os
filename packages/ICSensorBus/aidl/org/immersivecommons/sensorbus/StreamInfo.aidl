package org.immersivecommons.sensorbus;

parcelable StreamInfo {
    /** e.g. "motion.accel", "env.light", "activity.motion_state" */
    String name;
    /** "sensor" (one Android sensor, hardware or fused) or "derived" (computed in the bus) */
    String kind;
    /** least grant tier that may open it: "event" < "feature" < "raw" */
    String tier;
    /** 0 for on-change streams */
    int maxRateHz;
    /** the sensor name, or the stream it is derived from */
    String source;
}
