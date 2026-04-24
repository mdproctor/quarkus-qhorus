package io.quarkiverse.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

@ApplicationScoped
class CommitmentPanacheRepo implements PanacheRepositoryBase<Commitment, UUID> {
}
