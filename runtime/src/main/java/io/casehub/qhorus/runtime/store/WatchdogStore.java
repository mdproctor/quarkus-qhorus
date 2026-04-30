package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.casehub.qhorus.runtime.watchdog.Watchdog;

public interface WatchdogStore {
    Watchdog put(Watchdog watchdog);

    Optional<Watchdog> find(UUID id);

    List<Watchdog> scan(WatchdogQuery query);

    void delete(UUID id);
}
