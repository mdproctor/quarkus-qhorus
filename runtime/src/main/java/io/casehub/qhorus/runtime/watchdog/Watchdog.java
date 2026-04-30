package io.casehub.qhorus.runtime.watchdog;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * A condition-based watchdog that fires alert messages to a notification channel
 * when the condition threshold is exceeded.
 *
 * <p>
 * Condition types: BARRIER_STUCK, APPROVAL_PENDING, AGENT_STALE, CHANNEL_IDLE, QUEUE_DEPTH.
 * Only active when {@code casehub.qhorus.watchdog.enabled=true}.
 */
@Entity
@Table(name = "watchdog")
public class Watchdog extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "condition_type", nullable = false)
    public String conditionType;

    @Column(name = "target_name", nullable = false)
    public String targetName;

    @Column(name = "threshold_seconds")
    public Integer thresholdSeconds;

    @Column(name = "threshold_count")
    public Integer thresholdCount;

    @Column(name = "notification_channel", nullable = false)
    public String notificationChannel;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_fired_at")
    public Instant lastFiredAt;

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
