package io.quarkiverse.qhorus.runtime.instance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "instance", uniqueConstraints = @UniqueConstraint(name = "uq_instance_instance_id", columnNames = "instance_id"))
public class Instance extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "instance_id", nullable = false)
    public String instanceId;

    public String description;

    /** online | offline | stale */
    @Column(nullable = false)
    public String status;

    @Column(name = "claudony_session_id")
    public String claudonySessionId;

    @Column(name = "session_token")
    public String sessionToken;

    @Column(name = "read_only", nullable = false)
    public boolean readOnly;

    @Column(name = "last_seen", nullable = false)
    public Instant lastSeen;

    @Column(name = "registered_at", nullable = false, updatable = false)
    public Instant registeredAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (registeredAt == null) {
            registeredAt = now;
        }
        if (lastSeen == null) {
            lastSeen = now;
        }
        if (status == null) {
            status = "online";
        }
    }
}
