package io.casehub.qhorus.runtime.store.jpa;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.runtime.watchdog.Watchdog;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link Watchdog}.
 *
 * <p>
 * Marked {@code @Alternative} so it is not active by default — consumers must select it
 * explicitly via {@code quarkus.arc.selected-alternatives} when they configure a reactive
 * datasource. This prevents Hibernate Reactive from booting in applications that only use
 * the blocking {@link JpaWatchdogStore}.
 *
 * <p>
 * Kept package-private and injected into {@link ReactiveJpaWatchdogStore}.
 *
 * <p>
 * Refs #74.
 */
@Alternative
@ApplicationScoped
class WatchdogReactivePanacheRepo implements PanacheRepositoryBase<Watchdog, UUID> {
}
