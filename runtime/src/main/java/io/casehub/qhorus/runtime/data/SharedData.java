package io.casehub.qhorus.runtime.data;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "shared_data", uniqueConstraints = @UniqueConstraint(name = "uq_shared_data_key", columnNames = "data_key"))
public class SharedData extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "data_key", nullable = false)
    public String key;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(name = "created_by")
    public String createdBy;

    public String description;

    /** False while chunked upload is in progress; true once last_chunk received. */
    @Column(nullable = false)
    public boolean complete;

    @Column(name = "size_bytes", nullable = false)
    public long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        sizeBytes = content != null ? content.length() : 0;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        sizeBytes = content != null ? content.length() : 0;
    }
}
