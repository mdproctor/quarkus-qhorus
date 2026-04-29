package io.quarkiverse.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.message.CommitmentState;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.store.CommitmentStore;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaCommitmentStoreTest {

    @Inject
    CommitmentStore store;

    @Test
    @TestTransaction
    void saveAndFindById_happyPath() {
        Commitment saved = store.save(cmd("jpa-1"));
        assertNotNull(saved.id);
        assertTrue(store.findById(saved.id).isPresent());
    }

    @Test
    @TestTransaction
    void saveAndFindByCorrelationId_happyPath() {
        store.save(cmd("jpa-corr-1"));
        Optional<Commitment> found = store.findByCorrelationId("jpa-corr-1");
        assertTrue(found.isPresent());
        assertEquals("jpa-corr-1", found.get().correlationId);
    }

    @Test
    @TestTransaction
    void save_withExplicitId_preservesId() {
        UUID id = UUID.randomUUID();
        Commitment c = cmd("jpa-id-1");
        c.id = id;
        store.save(c);
        assertEquals(id, store.findById(id).get().id);
    }

    @Test
    @TestTransaction
    void stateUpdate_persists() {
        Commitment c = store.save(cmd("jpa-state-1"));
        c.state = CommitmentState.FULFILLED;
        c.resolvedAt = Instant.now();
        store.save(c);
        assertEquals(CommitmentState.FULFILLED,
                store.findByCorrelationId("jpa-state-1").get().state);
    }

    @Test
    @TestTransaction
    void findOpenByObligor_excludesTerminal() {
        UUID ch = UUID.randomUUID();
        store.save(cmd("jpa-ob-open", ch));
        Commitment done = store.save(cmd("jpa-ob-done", ch));
        done.state = CommitmentState.FULFILLED;
        done.resolvedAt = Instant.now();
        store.save(done);

        assertEquals(1, store.findOpenByObligor("obl", ch).size());
    }

    @Test
    @TestTransaction
    void findExpiredBefore_excludesTerminalAndFuture() {
        Instant now = Instant.now();

        Commitment expired = store.save(cmd("jpa-exp-1"));
        expired.expiresAt = now.minusSeconds(10);
        store.save(expired);

        Commitment future = store.save(cmd("jpa-exp-2"));
        future.expiresAt = now.plusSeconds(60);
        store.save(future);

        Commitment terminalExpired = store.save(cmd("jpa-exp-3"));
        terminalExpired.expiresAt = now.minusSeconds(5);
        terminalExpired.state = CommitmentState.DECLINED;
        terminalExpired.resolvedAt = now;
        store.save(terminalExpired);

        List<Commitment> expiredResults = store.findExpiredBefore(now);
        assertEquals(1, expiredResults.size());
        assertEquals("jpa-exp-1", expiredResults.get(0).correlationId);
    }

    @Test
    @TestTransaction
    void deleteById_removesFromDb() {
        Commitment c = store.save(cmd("jpa-del-1"));
        store.deleteById(c.id);
        assertTrue(store.findById(c.id).isEmpty());
    }

    @Test
    @TestTransaction
    void deleteExpiredBefore_bulkDelete() {
        Instant now = Instant.now();

        Commitment c1 = store.save(cmd("jpa-bulk-1"));
        c1.expiresAt = now.minusSeconds(5);
        store.save(c1);

        Commitment c2 = store.save(cmd("jpa-bulk-2"));
        c2.expiresAt = now.plusSeconds(60);
        store.save(c2);

        assertEquals(1, store.deleteExpiredBefore(now));
        assertTrue(store.findByCorrelationId("jpa-bulk-2").isPresent());
    }

    private Commitment cmd(String correlationId) {
        return cmd(correlationId, UUID.randomUUID());
    }

    private Commitment cmd(String correlationId, UUID channelId) {
        Commitment c = new Commitment();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.messageType = MessageType.COMMAND;
        c.requester = "req";
        c.obligor = "obl";
        c.state = CommitmentState.OPEN;
        return c;
    }
}
