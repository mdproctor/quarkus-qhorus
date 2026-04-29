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

import io.quarkiverse.qhorus.api.message.CommitmentState;
import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.store.CommitmentStore;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryCommitmentStore implements CommitmentStore {

    private final Map<UUID, Commitment> byId = new LinkedHashMap<>();

    @Override
    public Commitment save(Commitment c) {
        if (c.id == null) {
            c.id = UUID.randomUUID();
        }
        if (c.createdAt == null) {
            c.createdAt = Instant.now();
        }
        byId.put(c.id, c);
        return c;
    }

    @Override
    public Optional<Commitment> findById(UUID commitmentId) {
        return Optional.ofNullable(byId.get(commitmentId));
    }

    @Override
    public Optional<Commitment> findByCorrelationId(String correlationId) {
        // Prefer active (non-terminal) commitment — supports delegation chains.
        return byId.values().stream()
                .filter(c -> correlationId.equals(c.correlationId))
                .filter(c -> !c.state.isTerminal())
                .findFirst()
                .or(() -> byId.values().stream()
                        .filter(c -> correlationId.equals(c.correlationId))
                        .findFirst());
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor, UUID channelId) {
        return byId.values().stream()
                .filter(c -> !c.state.isTerminal())
                .filter(c -> channelId.equals(c.channelId))
                .filter(c -> obligor != null && obligor.equals(c.obligor))
                .toList();
    }

    @Override
    public List<Commitment> findOpenByRequester(String requester, UUID channelId) {
        return byId.values().stream()
                .filter(c -> !c.state.isTerminal())
                .filter(c -> channelId.equals(c.channelId))
                .filter(c -> requester != null && requester.equals(c.requester))
                .toList();
    }

    @Override
    public List<Commitment> findByState(CommitmentState state, UUID channelId) {
        return byId.values().stream()
                .filter(c -> state == c.state)
                .filter(c -> channelId.equals(c.channelId))
                .toList();
    }

    @Override
    public List<Commitment> findExpiredBefore(Instant cutoff) {
        return byId.values().stream()
                .filter(c -> !c.state.isTerminal())
                .filter(c -> c.expiresAt != null && c.expiresAt.isBefore(cutoff))
                .toList();
    }

    @Override
    public List<Commitment> findAllOpen() {
        return byId.values().stream()
                .filter(c -> c.state == CommitmentState.OPEN || c.state == CommitmentState.ACKNOWLEDGED)
                .sorted(java.util.Comparator.comparing(c -> c.expiresAt != null ? c.expiresAt : java.time.Instant.MAX))
                .toList();
    }

    @Override
    public void deleteById(UUID commitmentId) {
        byId.remove(commitmentId);
    }

    @Override
    public long deleteExpiredBefore(Instant cutoff) {
        List<Commitment> expired = findExpiredBefore(cutoff);
        expired.forEach(c -> deleteById(c.id));
        return expired.size();
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        byId.clear();
    }
}
