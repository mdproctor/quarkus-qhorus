package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.store.ReactiveWatchdogStore;
import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.casehub.qhorus.runtime.watchdog.Watchdog;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveJpaWatchdogStore implements ReactiveWatchdogStore {

    @Inject
    WatchdogReactivePanacheRepo repo;

    @Override
    @WithTransaction
    public Uni<Watchdog> put(Watchdog watchdog) {
        return repo.persist(watchdog);
    }

    @Override
    public Uni<Optional<Watchdog>> find(UUID id) {
        return repo.findById(id).map(Optional::ofNullable);
    }

    @Override
    public Uni<List<Watchdog>> scan(WatchdogQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Watchdog WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.conditionType() != null) {
            jpql.append(" AND conditionType = ?").append(idx++);
            params.add(q.conditionType());
        }

        return repo.list(jpql.toString(), params.toArray());
    }

    @Override
    @WithTransaction
    public Uni<Void> delete(UUID id) {
        return repo.deleteById(id).replaceWithVoid();
    }
}
