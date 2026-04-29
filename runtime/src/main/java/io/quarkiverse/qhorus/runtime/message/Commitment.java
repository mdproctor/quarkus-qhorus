package io.quarkiverse.qhorus.runtime.message;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkiverse.qhorus.api.message.CommitmentState;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Tracks the full lifecycle of a QUERY or COMMAND obligation.
 *
 * <p>
 * The {@code id} is the same UUID as {@link Message#commitmentId} — they are the same value,
 * not a foreign key join. The {@code correlationId} is the business key used by all lookup
 * operations and by {@code wait_for_reply} polling.
 *
 * <p>
 * On HANDOFF, the original Commitment transitions to DELEGATED and a child Commitment is created
 * with the same {@code correlationId}, a new {@code id}, the new obligor as {@code obligor}, and
 * {@code parentCommitmentId} pointing to this record.
 */
@Entity
@Table(name = "commitment")
public class Commitment extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "correlation_id", nullable = false)
    public String correlationId;

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    public MessageType messageType;

    /** Sender of the QUERY/COMMAND — the agent owed the result (Singh: creditor). */
    @Column(name = "requester", nullable = false)
    public String requester;

    /** Target of the QUERY/COMMAND — the agent that must respond (Singh: debtor). Null = broadcast. */
    @Column(name = "obligor")
    public String obligor;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    public CommitmentState state = CommitmentState.OPEN;

    /** Obligation discharge deadline. Null = no temporal constraint. */
    @Column(name = "expires_at")
    public Instant expiresAt;

    /** Set when the first STATUS is received from the obligor. */
    @Column(name = "acknowledged_at")
    public Instant acknowledgedAt;

    /** Set when a terminal state is reached (FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED). */
    @Column(name = "resolved_at")
    public Instant resolvedAt;

    /** Populated on HANDOFF — the identity of the new obligor. */
    @Column(name = "delegated_to")
    public String delegatedTo;

    /** Links a delegated child Commitment to the parent it was created from. */
    @Column(name = "parent_commitment_id")
    public UUID parentCommitmentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
