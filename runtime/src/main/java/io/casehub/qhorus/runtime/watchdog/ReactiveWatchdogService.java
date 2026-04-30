package io.casehub.qhorus.runtime.watchdog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.store.ReactiveWatchdogStore;
import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveWatchdogService {

    @Inject
    ReactiveWatchdogStore watchdogStore;

    public Uni<Watchdog> register(String conditionType, String targetName, Integer thresholdSeconds,
            Integer thresholdCount, String notificationChannel, String createdBy) {
        return Panache.withTransaction(() -> {
            Watchdog w = new Watchdog();
            w.conditionType = conditionType;
            w.targetName = targetName;
            w.thresholdSeconds = thresholdSeconds;
            w.thresholdCount = thresholdCount;
            w.notificationChannel = notificationChannel;
            w.createdBy = createdBy;
            return watchdogStore.put(w);
        });
    }

    public Uni<List<Watchdog>> listAll() {
        return watchdogStore.scan(WatchdogQuery.all());
    }

    public Uni<Optional<Watchdog>> findById(UUID id) {
        return watchdogStore.find(id);
    }

    public Uni<Boolean> delete(UUID id) {
        return Panache.withTransaction(() -> watchdogStore.find(id).flatMap(opt -> {
            if (opt.isEmpty()) {
                return Uni.createFrom().item(false);
            }
            return watchdogStore.delete(id).map(ignored -> true);
        }));
    }
}
