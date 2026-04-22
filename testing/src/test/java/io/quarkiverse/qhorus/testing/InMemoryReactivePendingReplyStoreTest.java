package io.quarkiverse.qhorus.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.PendingReply;

class InMemoryReactivePendingReplyStoreTest {

    private final InMemoryReactivePendingReplyStore store = new InMemoryReactivePendingReplyStore();

    @BeforeEach
    void setUp() {
        store.clear();
    }

    @Test
    void saveAndFind_happyPath() {
        PendingReply pr = pendingReply("corr-1", Instant.now().plusSeconds(60));
        store.save(pr).await().indefinitely();
        var found = store.findByCorrelationId("corr-1").await().indefinitely();
        assertTrue(found.isPresent());
        assertEquals("corr-1", found.get().correlationId);
    }

    @Test
    void save_assignsIdIfAbsent() {
        PendingReply pr = pendingReply("corr-2", Instant.now().plusSeconds(60));
        assertNull(pr.id);
        store.save(pr).await().indefinitely();
        assertNotNull(pr.id);
    }

    @Test
    void findByCorrelationId_notFound_returnsEmpty() {
        assertTrue(store.findByCorrelationId("nonexistent").await().indefinitely().isEmpty());
    }

    @Test
    void existsByCorrelationId_trueWhenPresent() {
        store.save(pendingReply("corr-3", Instant.now().plusSeconds(60))).await().indefinitely();
        assertTrue(store.existsByCorrelationId("corr-3").await().indefinitely());
    }

    @Test
    void existsByCorrelationId_falseWhenAbsent() {
        assertFalse(store.existsByCorrelationId("missing").await().indefinitely());
    }

    @Test
    void save_updatesExistingEntry() {
        PendingReply pr = pendingReply("corr-upd", Instant.now().plusSeconds(60));
        store.save(pr).await().indefinitely();
        Instant newExpiry = Instant.now().plusSeconds(120);
        pr.expiresAt = newExpiry;
        store.save(pr).await().indefinitely();
        assertEquals(newExpiry, store.findByCorrelationId("corr-upd").await().indefinitely().get().expiresAt);
    }

    @Test
    void deleteByCorrelationId_nonexistent_noError() {
        assertDoesNotThrow(() -> store.deleteByCorrelationId("ghost").await().indefinitely());
    }

    @Test
    void deleteByCorrelationId_removesEntry() {
        store.save(pendingReply("corr-4", Instant.now().plusSeconds(60))).await().indefinitely();
        store.deleteByCorrelationId("corr-4").await().indefinitely();
        assertTrue(store.findByCorrelationId("corr-4").await().indefinitely().isEmpty());
    }

    @Test
    void findExpiredBefore_returnsOnlyExpired() {
        Instant now = Instant.now();
        store.save(pendingReply("expired-1", now.minusSeconds(1))).await().indefinitely();
        store.save(pendingReply("active-1", now.plusSeconds(60))).await().indefinitely();
        List<PendingReply> expired = store.findExpiredBefore(now).await().indefinitely();
        assertEquals(1, expired.size());
        assertTrue(expired.get(0).expiresAt.isBefore(now));
    }

    @Test
    void deleteExpiredBefore_removesExpiredLeavesActive() {
        Instant now = Instant.now();
        store.save(pendingReply("expired", now.minusSeconds(5))).await().indefinitely();
        store.save(pendingReply("active", now.plusSeconds(60))).await().indefinitely();
        store.deleteExpiredBefore(now).await().indefinitely();
        assertFalse(store.existsByCorrelationId("expired").await().indefinitely());
        assertTrue(store.existsByCorrelationId("active").await().indefinitely());
    }

    private PendingReply pendingReply(String correlationId, Instant expiresAt) {
        PendingReply pr = new PendingReply();
        pr.correlationId = correlationId;
        pr.channelId = UUID.randomUUID();
        pr.instanceId = UUID.randomUUID();
        pr.expiresAt = expiresAt;
        return pr;
    }
}
