package io.quarkiverse.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.PendingReply;

public abstract class PendingReplyStoreContractTest {

    protected abstract PendingReply save(PendingReply pr);

    protected abstract Optional<PendingReply> findByCorrelationId(String correlationId);

    protected abstract void deleteByCorrelationId(String correlationId);

    protected abstract boolean existsByCorrelationId(String correlationId);

    protected abstract List<PendingReply> findExpiredBefore(Instant cutoff);

    protected abstract void deleteExpiredBefore(Instant cutoff);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void save_assignsId_whenNull() {
        PendingReply pr = pendingReply("corr-id", Instant.now().plusSeconds(60));
        assertNull(pr.id);
        assertNotNull(save(pr).id);
    }

    @Test
    void save_andFind_happyPath() {
        save(pendingReply("corr-find", Instant.now().plusSeconds(60)));
        Optional<PendingReply> found = findByCorrelationId("corr-find");
        assertTrue(found.isPresent());
        assertEquals("corr-find", found.get().correlationId);
    }

    @Test
    void save_updatesExistingEntry() {
        PendingReply pr = pendingReply("corr-update", Instant.now().plusSeconds(60));
        save(pr);
        Instant newExpiry = Instant.now().plusSeconds(120);
        pr.expiresAt = newExpiry;
        save(pr);
        assertEquals(newExpiry, findByCorrelationId("corr-update").get().expiresAt);
    }

    @Test
    void findByCorrelationId_returnsEmpty_whenAbsent() {
        assertTrue(findByCorrelationId("nonexistent").isEmpty());
    }

    @Test
    void existsByCorrelationId_trueWhenPresent() {
        save(pendingReply("corr-exists", Instant.now().plusSeconds(60)));
        assertTrue(existsByCorrelationId("corr-exists"));
    }

    @Test
    void existsByCorrelationId_falseWhenAbsent() {
        assertFalse(existsByCorrelationId("missing"));
    }

    @Test
    void deleteByCorrelationId_removesEntry() {
        save(pendingReply("corr-del", Instant.now().plusSeconds(60)));
        deleteByCorrelationId("corr-del");
        assertTrue(findByCorrelationId("corr-del").isEmpty());
    }

    @Test
    void deleteByCorrelationId_nonexistent_noError() {
        assertDoesNotThrow(() -> deleteByCorrelationId("ghost"));
    }

    @Test
    void findExpiredBefore_returnsOnlyExpired() {
        Instant now = Instant.now();
        save(pendingReply("expired-1", now.minusSeconds(1)));
        save(pendingReply("active-1", now.plusSeconds(60)));
        List<PendingReply> expired = findExpiredBefore(now);
        assertEquals(1, expired.size());
        assertTrue(expired.get(0).expiresAt.isBefore(now));
    }

    @Test
    void deleteExpiredBefore_removesExpiredLeavesActive() {
        Instant now = Instant.now();
        save(pendingReply("expired", now.minusSeconds(5)));
        save(pendingReply("active", now.plusSeconds(60)));
        deleteExpiredBefore(now);
        assertFalse(existsByCorrelationId("expired"));
        assertTrue(existsByCorrelationId("active"));
    }

    protected PendingReply pendingReply(String correlationId, Instant expiresAt) {
        PendingReply pr = new PendingReply();
        pr.correlationId = correlationId;
        pr.channelId = UUID.randomUUID();
        pr.instanceId = UUID.randomUUID();
        pr.expiresAt = expiresAt;
        return pr;
    }
}
