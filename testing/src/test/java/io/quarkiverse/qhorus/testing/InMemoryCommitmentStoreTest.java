package io.quarkiverse.qhorus.testing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.message.CommitmentState;
import io.quarkiverse.qhorus.testing.contract.CommitmentStoreContractTest;

class InMemoryCommitmentStoreTest extends CommitmentStoreContractTest {

    private final InMemoryCommitmentStore store = new InMemoryCommitmentStore();

    @Override
    protected Commitment save(Commitment c) {
        return store.save(c);
    }

    @Override
    protected Optional<Commitment> findById(UUID id) {
        return store.findById(id);
    }

    @Override
    protected Optional<Commitment> findByCorrelationId(String c) {
        return store.findByCorrelationId(c);
    }

    @Override
    protected List<Commitment> findOpenByObligor(String o, UUID ch) {
        return store.findOpenByObligor(o, ch);
    }

    @Override
    protected List<Commitment> findOpenByRequester(String r, UUID ch) {
        return store.findOpenByRequester(r, ch);
    }

    @Override
    protected List<Commitment> findByState(CommitmentState s, UUID ch) {
        return store.findByState(s, ch);
    }

    @Override
    protected List<Commitment> findExpiredBefore(Instant t) {
        return store.findExpiredBefore(t);
    }

    @Override
    protected void deleteById(UUID id) {
        store.deleteById(id);
    }

    @Override
    protected long deleteExpiredBefore(Instant t) {
        return store.deleteExpiredBefore(t);
    }

    @Override
    protected void reset() {
        store.clear();
    }
}
