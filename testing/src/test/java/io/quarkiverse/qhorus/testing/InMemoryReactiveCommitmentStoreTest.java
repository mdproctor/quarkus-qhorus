package io.quarkiverse.qhorus.testing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.message.CommitmentState;
import io.quarkiverse.qhorus.testing.contract.CommitmentStoreContractTest;

class InMemoryReactiveCommitmentStoreTest extends CommitmentStoreContractTest {

    private final InMemoryCommitmentStore blocking = new InMemoryCommitmentStore();
    private final InMemoryReactiveCommitmentStore store;

    InMemoryReactiveCommitmentStoreTest() {
        store = new InMemoryReactiveCommitmentStore();
        store.delegate = blocking;
    }

    @Override
    protected Commitment save(Commitment c) {
        return store.save(c).await().indefinitely();
    }

    @Override
    protected Optional<Commitment> findById(UUID id) {
        return store.findById(id).await().indefinitely();
    }

    @Override
    protected Optional<Commitment> findByCorrelationId(String c) {
        return store.findByCorrelationId(c).await().indefinitely();
    }

    @Override
    protected List<Commitment> findOpenByObligor(String o, UUID ch) {
        return store.findOpenByObligor(o, ch).await().indefinitely();
    }

    @Override
    protected List<Commitment> findOpenByRequester(String r, UUID ch) {
        return store.findOpenByRequester(r, ch).await().indefinitely();
    }

    @Override
    protected List<Commitment> findByState(CommitmentState s, UUID ch) {
        return store.findByState(s, ch).await().indefinitely();
    }

    @Override
    protected List<Commitment> findExpiredBefore(Instant t) {
        return store.findExpiredBefore(t).await().indefinitely();
    }

    @Override
    protected void deleteById(UUID id) {
        store.deleteById(id).await().indefinitely();
    }

    @Override
    protected long deleteExpiredBefore(Instant t) {
        return store.deleteExpiredBefore(t).await().indefinitely();
    }

    @Override
    protected void reset() {
        blocking.clear();
    }
}
