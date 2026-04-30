package io.casehub.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.runtime.data.SharedData;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link SharedData}.
 *
 * <p>
 * Marked {@code @Alternative} so it is not active by default — consumers must select it
 * explicitly via {@code quarkus.arc.selected-alternatives} when they configure a reactive
 * datasource. This prevents Hibernate Reactive from booting in applications that only use
 * the blocking {@link JpaDataStore}.
 *
 * <p>
 * Kept package-private and injected into {@link ReactiveJpaDataStore}.
 *
 * <p>
 * Refs #74.
 */
@Alternative
@ApplicationScoped
class SharedDataReactivePanacheRepo implements PanacheRepositoryBase<SharedData, UUID> {
}
