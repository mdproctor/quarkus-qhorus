package io.casehub.qhorus.testing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.runtime.store.WatchdogStore;
import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.casehub.qhorus.runtime.watchdog.Watchdog;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryWatchdogStore implements WatchdogStore {

    private final Map<UUID, Watchdog> store = new LinkedHashMap<>();

    @Override
    public Watchdog put(Watchdog watchdog) {
        if (watchdog.id == null) {
            watchdog.id = UUID.randomUUID();
        }
        if (watchdog.createdAt == null) {
            watchdog.createdAt = Instant.now();
        }
        store.put(watchdog.id, watchdog);
        return watchdog;
    }

    @Override
    public Optional<Watchdog> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Watchdog> scan(WatchdogQuery query) {
        return store.values().stream()
                .filter(query::matches)
                .toList();
    }

    @Override
    public void delete(UUID id) {
        store.remove(id);
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
    }
}
