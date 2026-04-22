package io.quarkiverse.qhorus.testing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.runtime.store.PendingReplyStore;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryPendingReplyStore implements PendingReplyStore {

    private final Map<String, PendingReply> store = new LinkedHashMap<>();

    @Override
    public PendingReply save(PendingReply pr) {
        if (pr.id == null) {
            pr.id = UUID.randomUUID();
        }
        store.put(pr.correlationId, pr);
        return pr;
    }

    @Override
    public Optional<PendingReply> findByCorrelationId(String correlationId) {
        return Optional.ofNullable(store.get(correlationId));
    }

    @Override
    public void deleteByCorrelationId(String correlationId) {
        store.remove(correlationId);
    }

    @Override
    public boolean existsByCorrelationId(String correlationId) {
        return store.containsKey(correlationId);
    }

    @Override
    public List<PendingReply> findExpiredBefore(Instant cutoff) {
        return store.values().stream()
                .filter(pr -> pr.expiresAt != null && pr.expiresAt.isBefore(cutoff))
                .toList();
    }

    @Override
    public void deleteExpiredBefore(Instant cutoff) {
        store.values().removeIf(pr -> pr.expiresAt != null && pr.expiresAt.isBefore(cutoff));
    }

    /** Call in @BeforeEach / @AfterEach for test isolation. */
    public void clear() {
        store.clear();
    }
}
