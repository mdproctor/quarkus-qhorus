package io.quarkiverse.qhorus.api.message;

/** Obligation lifecycle state for a QUERY or COMMAND commitment. */
public enum CommitmentState {

    /** QUERY or COMMAND sent; debtor must respond or decline. */
    OPEN,

    /** STATUS received; debtor is working and has extended their deadline. */
    ACKNOWLEDGED,

    /** RESPONSE (for QUERY) or DONE (for COMMAND) received; obligation discharged. */
    FULFILLED,

    /** DECLINE received; debtor refused the obligation. */
    DECLINED,

    /** FAILURE received; debtor attempted but could not complete. */
    FAILED,

    /** HANDOFF received; obligation transferred to a new debtor. A child Commitment was created. */
    DELEGATED,

    /** Deadline exceeded with no response; infrastructure-generated terminal state. */
    EXPIRED;

    /** True for all states from which no further transition is possible. */
    public boolean isTerminal() {
        return this == FULFILLED || this == DECLINED || this == FAILED
                || this == DELEGATED || this == EXPIRED;
    }
}
