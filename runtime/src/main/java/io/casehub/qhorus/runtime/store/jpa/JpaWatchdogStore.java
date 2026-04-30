package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.runtime.store.WatchdogStore;
import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.casehub.qhorus.runtime.watchdog.Watchdog;

@ApplicationScoped
public class JpaWatchdogStore implements WatchdogStore {

    @Override
    @Transactional
    public Watchdog put(Watchdog watchdog) {
        watchdog.persistAndFlush();
        return watchdog;
    }

    @Override
    public Optional<Watchdog> find(UUID id) {
        return Optional.ofNullable(Watchdog.findById(id));
    }

    @Override
    public List<Watchdog> scan(WatchdogQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Watchdog WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.conditionType() != null) {
            jpql.append(" AND conditionType = ?").append(idx++);
            params.add(q.conditionType());
        }

        return Watchdog.list(jpql.toString(), params.toArray());
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Watchdog.deleteById(id);
    }
}
