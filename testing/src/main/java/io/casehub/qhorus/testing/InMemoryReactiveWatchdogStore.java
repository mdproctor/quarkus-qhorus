package io.casehub.qhorus.testing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.runtime.store.ReactiveWatchdogStore;
import io.casehub.qhorus.runtime.store.query.WatchdogQuery;
import io.casehub.qhorus.runtime.watchdog.Watchdog;
import io.smallrye.mutiny.Uni;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryReactiveWatchdogStore implements ReactiveWatchdogStore {

    private final InMemoryWatchdogStore delegate = new InMemoryWatchdogStore();

    @Override
    public Uni<Watchdog> put(Watchdog watchdog) {
        return Uni.createFrom().item(() -> delegate.put(watchdog));
    }

    @Override
    public Uni<Optional<Watchdog>> find(UUID id) {
        return Uni.createFrom().item(() -> delegate.find(id));
    }

    @Override
    public Uni<List<Watchdog>> scan(WatchdogQuery query) {
        return Uni.createFrom().item(() -> delegate.scan(query));
    }

    @Override
    public Uni<Void> delete(UUID id) {
        return Uni.createFrom().voidItem().invoke(() -> delegate.delete(id));
    }

    public void clear() {
        delegate.clear();
    }
}
