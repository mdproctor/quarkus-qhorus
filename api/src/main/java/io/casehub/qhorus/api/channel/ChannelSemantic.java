package io.casehub.qhorus.api.channel;

public enum ChannelSemantic {
    /** Ordered accumulation — default for conversation threads. */
    APPEND,
    /** N writers contribute; delivered atomically then cleared. Fan-in primitive. */
    COLLECT,
    /** Releases only when all declared contributors have written. Join gate. */
    BARRIER,
    /** Visible to next reader only, then cleared. Routing hints, transient context. */
    EPHEMERAL,
    /** One authoritative writer; concurrent writes return 409. */
    LAST_WRITE
}
