package io.quarkiverse.qhorus.runtime.message;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "message")
@SequenceGenerator(name = "message_seq", sequenceName = "message_seq", allocationSize = 50)
public class Message extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "message_seq")
    public Long id;

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(nullable = false)
    public String sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    public MessageType messageType;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(name = "correlation_id")
    public String correlationId;

    @Column(name = "in_reply_to")
    public Long inReplyTo;

    @Column(name = "reply_count", nullable = false)
    public int replyCount = 0;

    @Column(name = "artefact_refs")
    public String artefactRefs;

    /** Addressing target: null (broadcast), instance:<id>, capability:<tag>, or role:<name>. */
    @Column(name = "target")
    public String target;

    /** Links to CommitmentStore entry. Auto-set by infrastructure on QUERY/COMMAND. */
    @Column(name = "commitment_id")
    public UUID commitmentId;

    /**
     * When the obligation must be discharged. Null = no temporal constraint (STATUS, RESPONSE, EVENT).
     * Set from channel config default on QUERY/COMMAND when not provided by sender.
     */
    @Column(name = "deadline")
    public Instant deadline;

    /** When the obligation was explicitly accepted. Null in v1; populated by v2 ACK mechanism. */
    @Column(name = "acknowledged_at")
    public Instant acknowledgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
