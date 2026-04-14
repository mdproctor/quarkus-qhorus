package io.quarkiverse.qhorus.runtime.channel;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "channel", uniqueConstraints = @UniqueConstraint(name = "uq_channel_name", columnNames = "name"))
public class Channel extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(nullable = false)
    public String name;

    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public ChannelSemantic semantic;

    @Column(name = "barrier_contributors", columnDefinition = "TEXT")
    public String barrierContributors;

    /**
     * Comma-separated list of allowed writers. Each entry is a bare instance ID, or a
     * {@code capability:tag} / {@code role:name} pattern. Null = open (any writer permitted).
     */
    @Column(name = "allowed_writers", columnDefinition = "TEXT")
    public String allowedWriters;

    /** When true, send_message is blocked and check_messages returns empty + paused status. */
    @Column(nullable = false)
    public boolean paused = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    public Instant lastActivityAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastActivityAt == null) {
            lastActivityAt = now;
        }
    }
}
