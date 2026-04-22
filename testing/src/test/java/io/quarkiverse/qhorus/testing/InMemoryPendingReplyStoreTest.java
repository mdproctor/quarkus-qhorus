package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.testing.contract.PendingReplyStoreContractTest;

class InMemoryPendingReplyStoreTest extends PendingReplyStoreContractTest {

    private final InMemoryPendingReplyStore store = new InMemoryPendingReplyStore();

    @Override
    protected PendingReply save(PendingReply pr) {
        return store.save(pr);
    }

    @Override
    protected Optional<PendingReply> findByCorrelationId(String correlationId) {
        return store.findByCorrelationId(correlationId);
    }

    @Override
    protected void deleteByCorrelationId(String correlationId) {
        store.deleteByCorrelationId(correlationId);
    }

    @Override
    protected boolean existsByCorrelationId(String correlationId) {
        return store.existsByCorrelationId(correlationId);
    }

    @Override
    protected List<PendingReply> findExpiredBefore(Instant cutoff) {
        return store.findExpiredBefore(cutoff);
    }

    @Override
    protected void deleteExpiredBefore(Instant cutoff) {
        store.deleteExpiredBefore(cutoff);
    }

    @Override
    protected void reset() {
        store.clear();
    }

    @Test
    void findExpiredBefore_noneExpired_returnsEmpty() {
        store.save(pendingReply("active", Instant.now().plusSeconds(60)));
        assertTrue(store.findExpiredBefore(Instant.now()).isEmpty());
    }

    @Test
    void findExpiredBefore_multipleExpired_returnsAll() {
        Instant now = Instant.now();
        store.save(pendingReply("expired-1", now.minusSeconds(1)));
        store.save(pendingReply("expired-2", now.minusSeconds(10)));
        store.save(pendingReply("active-1", now.plusSeconds(60)));
        List<PendingReply> expired = store.findExpiredBefore(now);
        assertEquals(2, expired.size());
        assertTrue(expired.stream().allMatch(pr -> pr.expiresAt.isBefore(now)));
    }
}
