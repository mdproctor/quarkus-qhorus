package io.casehub.qhorus.runtime.ledger;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.casehub.ledger.runtime.model.LedgerEntry;

/**
 * A ledger entry recording any agent-to-agent message as a speech act.
 *
 * <p>
 * Every message type is recorded — this is the complete, immutable channel audit trail.
 * {@link #messageType} discriminates content interpretation. Telemetry fields
 * ({@code toolName}, {@code durationMs}, etc.) are populated only for EVENT messages.
 *
 * <p>
 * The CommitmentStore is the live obligation state; this ledger is the permanent record.
 * {@code causedByEntryId} (inherited from {@link io.casehub.ledger.runtime.model.LedgerEntry})
 * links terminal messages back to the COMMAND.
 *
 * <p>
 * Refs #100, Epic #99.
 */
@Entity
@Table(name = "message_ledger_entry")
@DiscriminatorValue("QHORUS_MESSAGE")
public class MessageLedgerEntry extends LedgerEntry {

    /** UUID of the channel this message was sent on. Mirrors {@code subjectId}. */
    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(name = "message_id", nullable = false)
    public Long messageId;

    /**
     * Qhorus {@code MessageType} enum name — the discriminator for interpreting all other fields.
     * Normative types (COMMAND, DECLINE, DONE, etc.) populate {@link #content} and {@link #target}.
     * {@code EVENT} populates the telemetry fields ({@link #toolName}, {@link #durationMs}, etc.).
     */
    @Column(name = "message_type", nullable = false)
    public String messageType;

    /** Intended recipient for COMMAND and HANDOFF. Null for broadcasts. */
    @Column(name = "target")
    public String target;

    /**
     * Message content for normative types: COMMAND description, DECLINE/FAILURE reason,
     * DONE summary. Null for EVENT (telemetry uses dedicated fields).
     */
    @Column(name = "content", columnDefinition = "TEXT")
    public String content;

    /** Propagated from the message — used for causal chain resolution and request/reply tracing. */
    @Column(name = "correlation_id")
    public String correlationId;

    /** Links this entry to the live CommitmentStore record for obligation-bearing message types. */
    @Column(name = "commitment_id")
    public UUID commitmentId;

    // EVENT-only telemetry fields — null for all non-EVENT message types

    @Column(name = "tool_name")
    public String toolName;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "token_count")
    public Long tokenCount;

    @Column(name = "context_refs", columnDefinition = "TEXT")
    public String contextRefs;

    @Column(name = "source_entity", columnDefinition = "TEXT")
    public String sourceEntity;
}
