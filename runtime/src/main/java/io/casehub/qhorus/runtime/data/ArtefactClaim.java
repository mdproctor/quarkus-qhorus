package io.casehub.qhorus.runtime.data;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

@Entity
@Table(name = "artefact_claim")
public class ArtefactClaim extends PanacheEntityBase {

    @Id
    public UUID id;

    @Column(name = "artefact_id", nullable = false)
    public UUID artefactId;

    @Column(name = "instance_id", nullable = false)
    public UUID instanceId;

    @Column(name = "claimed_at", nullable = false)
    public Instant claimedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (claimedAt == null) {
            claimedAt = Instant.now();
        }
    }
}
