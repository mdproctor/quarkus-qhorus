package io.casehub.qhorus.runtime.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.testing.InMemoryCommitmentStore;

/**
 * Pure unit tests — no CDI, no database. Uses InMemoryCommitmentStore directly.
 */
class CommitmentServiceTest {

    private final InMemoryCommitmentStore store = new InMemoryCommitmentStore();
    private final CommitmentService service = new CommitmentService();

    @BeforeEach
    void setup() {
        service.store = store;
        store.clear();
    }

    // --- Happy path ---

    @Test
    void open_createsCommitmentWithOpenState() {
        UUID id = UUID.randomUUID();
        Commitment c = service.open(id, "corr-1", UUID.randomUUID(),
                MessageType.COMMAND, "req", "obl", null);
        assertEquals(CommitmentState.OPEN, c.state);
        assertEquals(id, c.id);
        assertEquals("corr-1", c.correlationId);
        assertEquals("req", c.requester);
        assertEquals("obl", c.obligor);
    }

    @Test
    void acknowledge_transitionsOpenToAcknowledged() {
        openCmd("corr-ack");
        Optional<Commitment> result = service.acknowledge("corr-ack");
        assertTrue(result.isPresent());
        assertEquals(CommitmentState.ACKNOWLEDGED, result.get().state);
        assertNotNull(result.get().acknowledgedAt);
    }

    @Test
    void acknowledge_setsAcknowledgedAtOnlyOnce() {
        openCmd("corr-ack-once");
        service.acknowledge("corr-ack-once");
        Instant first = store.findByCorrelationId("corr-ack-once").get().acknowledgedAt;
        service.acknowledge("corr-ack-once");
        Instant second = store.findByCorrelationId("corr-ack-once").get().acknowledgedAt;
        assertEquals(first, second);
    }

    @Test
    void fulfill_transitionsToFulfilled_setsResolvedAt() {
        openCmd("corr-fulfill");
        service.fulfill("corr-fulfill");
        Commitment c = store.findByCorrelationId("corr-fulfill").get();
        assertEquals(CommitmentState.FULFILLED, c.state);
        assertNotNull(c.resolvedAt);
    }

    @Test
    void decline_transitionsToDeclined_setsResolvedAt() {
        openCmd("corr-decline");
        service.decline("corr-decline");
        Commitment c = store.findByCorrelationId("corr-decline").get();
        assertEquals(CommitmentState.DECLINED, c.state);
        assertNotNull(c.resolvedAt);
    }

    @Test
    void fail_transitionsToFailed_setsResolvedAt() {
        openCmd("corr-fail");
        service.fail("corr-fail");
        Commitment c = store.findByCorrelationId("corr-fail").get();
        assertEquals(CommitmentState.FAILED, c.state);
        assertNotNull(c.resolvedAt);
    }

    @Test
    void delegate_transitionsParentToDelegatedAndCreatesChild() {
        UUID ch = UUID.randomUUID();
        Commitment parent = service.open(UUID.randomUUID(), "corr-delegate", ch,
                MessageType.COMMAND, "req", "obl-a", null);
        service.delegate("corr-delegate", "obl-b");

        Commitment updated = store.findById(parent.id).get();
        assertEquals(CommitmentState.DELEGATED, updated.state);
        assertEquals("obl-b", updated.delegatedTo);
        assertNotNull(updated.resolvedAt);

        assertThat(store.findOpenByObligor("obl-b", ch))
                .hasSize(1)
                .first()
                .satisfies(child -> {
                    assertEquals("corr-delegate", child.correlationId);
                    assertEquals(parent.id, child.parentCommitmentId);
                    assertEquals(CommitmentState.OPEN, child.state);
                });
    }

    // --- Correctness: terminal idempotency ---

    @Test
    void fulfill_afterDecline_isNoOp() {
        openCmd("corr-idem-1");
        service.decline("corr-idem-1");
        Optional<Commitment> result = service.fulfill("corr-idem-1");
        assertTrue(result.isEmpty());
        assertEquals(CommitmentState.DECLINED,
                store.findByCorrelationId("corr-idem-1").get().state);
    }

    @Test
    void acknowledge_afterFulfill_isNoOp() {
        openCmd("corr-idem-2");
        service.fulfill("corr-idem-2");
        Optional<Commitment> result = service.acknowledge("corr-idem-2");
        assertTrue(result.isEmpty());
        assertEquals(CommitmentState.FULFILLED,
                store.findByCorrelationId("corr-idem-2").get().state);
    }

    @Test
    void allTerminalStates_blockFurtherTransitions() {
        for (CommitmentState terminal : new CommitmentState[] {
                CommitmentState.FULFILLED, CommitmentState.DECLINED,
                CommitmentState.FAILED, CommitmentState.EXPIRED }) {
            String corr = "corr-block-" + terminal;
            openCmd(corr);
            Commitment c = store.findByCorrelationId(corr).get();
            c.state = terminal;
            store.save(c);
            assertTrue(service.acknowledge(corr).isEmpty());
            assertTrue(service.fulfill(corr).isEmpty());
        }
    }

    // --- Robustness ---

    @Test
    void allTransitions_withNullCorrelationId_areNoOps() {
        assertFalse(service.acknowledge(null).isPresent());
        assertFalse(service.fulfill(null).isPresent());
        assertFalse(service.decline(null).isPresent());
        assertFalse(service.fail(null).isPresent());
        assertFalse(service.delegate(null, "obl").isPresent());
    }

    @Test
    void allTransitions_withUnknownCorrelationId_areNoOps() {
        assertFalse(service.acknowledge("no-such").isPresent());
        assertFalse(service.fulfill("no-such").isPresent());
    }

    @Test
    void expireOverdue_transitionsOnlyOpenAndAcknowledgedPastDeadline() {
        openCmdWithExpiry("exp-1", Instant.now().minusSeconds(5));
        openCmdWithExpiry("exp-2", Instant.now().plusSeconds(60));
        openCmdWithExpiry("exp-3", Instant.now().minusSeconds(3));
        service.acknowledge("exp-3");

        int expired = service.expireOverdue();
        assertEquals(2, expired);
        assertEquals(CommitmentState.EXPIRED, store.findByCorrelationId("exp-1").get().state);
        assertEquals(CommitmentState.OPEN, store.findByCorrelationId("exp-2").get().state);
        assertEquals(CommitmentState.EXPIRED, store.findByCorrelationId("exp-3").get().state);
    }

    @Test
    void resolvedAt_setOnAllTerminalTransitions() {
        openCmd("res-1");
        openCmd("res-2");
        openCmd("res-3");
        service.fulfill("res-1");
        service.decline("res-2");
        service.fail("res-3");
        assertNotNull(store.findByCorrelationId("res-1").get().resolvedAt);
        assertNotNull(store.findByCorrelationId("res-2").get().resolvedAt);
        assertNotNull(store.findByCorrelationId("res-3").get().resolvedAt);
    }

    private void openCmd(String correlationId) {
        service.open(UUID.randomUUID(), correlationId, UUID.randomUUID(),
                MessageType.COMMAND, "req", "obl", null);
    }

    private void openCmdWithExpiry(String correlationId, Instant expiresAt) {
        service.open(UUID.randomUUID(), correlationId, UUID.randomUUID(),
                MessageType.COMMAND, "req", "obl", expiresAt);
    }
}
