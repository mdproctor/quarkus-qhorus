package io.casehub.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import io.casehub.qhorus.runtime.message.Commitment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

@ApplicationScoped
class CommitmentPanacheRepo implements PanacheRepositoryBase<Commitment, UUID> {
}
