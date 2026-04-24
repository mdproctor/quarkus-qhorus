package io.quarkiverse.qhorus.testing.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.message.CommitmentState;
import io.quarkiverse.qhorus.runtime.message.MessageType;

public abstract class CommitmentStoreContractTest {

    protected abstract Commitment save(Commitment c);

    protected abstract Optional<Commitment> findById(UUID id);

    protected abstract Optional<Commitment> findByCorrelationId(String correlationId);

    protected abstract List<Commitment> findOpenByObligor(String obligor, UUID channelId);

    protected abstract List<Commitment> findOpenByRequester(String requester, UUID channelId);

    protected abstract List<Commitment> findByState(CommitmentState state, UUID channelId);

    protected abstract List<Commitment> findExpiredBefore(Instant cutoff);

    protected abstract void deleteById(UUID id);

    protected abstract long deleteExpiredBefore(Instant cutoff);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    // --- Happy path: CRUD ---

    @Test
    void save_assignsId_whenNull() {
        Commitment c = openCommitment("corr-1", "req", "obl");
        assertNull(c.id);
        assertNotNull(save(c).id);
    }

    @Test
    void save_setsCreatedAt_whenNull() {
        Commitment c = openCommitment("corr-2", "req", "obl");
        assertNull(c.createdAt);
        save(c);
        assertNotNull(c.createdAt);
    }

    @Test
    void findById_returnsCommitment_afterSave() {
        Commitment c = save(openCommitment("corr-3", "req", "obl"));
        assertTrue(findById(c.id).isPresent());
        assertEquals(c.id, findById(c.id).get().id);
    }

    @Test
    void findByCorrelationId_returnsCommitment_afterSave() {
        save(openCommitment("corr-4", "req", "obl"));
        Optional<Commitment> found = findByCorrelationId("corr-4");
        assertTrue(found.isPresent());
        assertEquals("corr-4", found.get().correlationId);
    }

    @Test
    void findById_returnsEmpty_whenAbsent() {
        assertTrue(findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByCorrelationId_returnsEmpty_whenAbsent() {
        assertTrue(findByCorrelationId("ghost").isEmpty());
    }

    @Test
    void save_updatesExistingCommitment() {
        Commitment c = save(openCommitment("corr-5", "req", "obl"));
        c.state = CommitmentState.ACKNOWLEDGED;
        c.acknowledgedAt = Instant.now();
        save(c);
        assertEquals(CommitmentState.ACKNOWLEDGED,
                findByCorrelationId("corr-5").get().state);
    }

    @Test
    void save_withExplicitId_preservesId() {
        UUID id = UUID.randomUUID();
        Commitment c = openCommitment("corr-id-1", "req", "obl");
        c.id = id;
        save(c);
        assertEquals(id, findById(id).get().id);
    }

    @Test
    void deleteById_removesCommitment() {
        Commitment c = save(openCommitment("corr-6", "req", "obl"));
        deleteById(c.id);
        assertTrue(findById(c.id).isEmpty());
        assertTrue(findByCorrelationId("corr-6").isEmpty());
    }

    @Test
    void deleteById_nonexistent_noError() {
        assertDoesNotThrow(() -> deleteById(UUID.randomUUID()));
    }

    // --- Correctness: state filtering ---

    @Test
    void findOpenByObligor_excludesTerminalStates() {
        UUID ch = UUID.randomUUID();
        save(openCommitment("corr-ob-open", "req", "obl", ch));
        Commitment ack = save(openCommitment("corr-ob-ack", "req", "obl", ch));
        ack.state = CommitmentState.ACKNOWLEDGED;
        save(ack);
        Commitment fulfilled = save(openCommitment("corr-ob-done", "req", "obl", ch));
        fulfilled.state = CommitmentState.FULFILLED;
        fulfilled.resolvedAt = Instant.now();
        save(fulfilled);

        List<Commitment> result = findOpenByObligor("obl", ch);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(c -> c.correlationId)
                .containsExactlyInAnyOrder("corr-ob-open", "corr-ob-ack");
    }

    @Test
    void findOpenByRequester_excludesTerminal() {
        UUID ch = UUID.randomUUID();
        save(openCommitment("corr-rq-open", "req", "obl", ch));
        Commitment declined = save(openCommitment("corr-rq-declined", "req", "obl", ch));
        declined.state = CommitmentState.DECLINED;
        declined.resolvedAt = Instant.now();
        save(declined);

        assertThat(findOpenByRequester("req", ch)).hasSize(1);
    }

    @Test
    void findByState_returnsOnlyMatchingState() {
        UUID ch = UUID.randomUUID();
        save(openCommitment("corr-st-1", "req", "obl", ch));
        Commitment fulfilled = save(openCommitment("corr-st-2", "req", "obl", ch));
        fulfilled.state = CommitmentState.FULFILLED;
        fulfilled.resolvedAt = Instant.now();
        save(fulfilled);

        assertThat(findByState(CommitmentState.OPEN, ch)).hasSize(1);
        assertThat(findByState(CommitmentState.FULFILLED, ch)).hasSize(1);
        assertThat(findByState(CommitmentState.DECLINED, ch)).isEmpty();
    }

    @Test
    void findOpenByObligor_doesNotCrossChannels() {
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        save(openCommitment("corr-ch-1", "req", "obl", ch1));
        save(openCommitment("corr-ch-2", "req", "obl", ch2));

        assertThat(findOpenByObligor("obl", ch1)).hasSize(1);
        assertThat(findOpenByObligor("obl", ch2)).hasSize(1);
    }

    // --- Correctness: expiry ---

    @Test
    void findExpiredBefore_returnsOpenAndAcknowledgedPastCutoff() {
        Instant now = Instant.now();

        Commitment expired = save(openCommitment("corr-exp-1", "req", "obl"));
        expired.expiresAt = now.minusSeconds(1);
        save(expired);

        Commitment active = save(openCommitment("corr-exp-2", "req", "obl"));
        active.expiresAt = now.plusSeconds(60);
        save(active);

        Commitment terminalExpired = save(openCommitment("corr-exp-3", "req", "obl"));
        terminalExpired.expiresAt = now.minusSeconds(10);
        terminalExpired.state = CommitmentState.FULFILLED;
        terminalExpired.resolvedAt = now;
        save(terminalExpired);

        List<Commitment> result = findExpiredBefore(now);
        assertThat(result).hasSize(1);
        assertEquals("corr-exp-1", result.get(0).correlationId);
    }

    @Test
    void deleteExpiredBefore_removesExpiredLeavesActive() {
        Instant now = Instant.now();

        Commitment c1 = save(openCommitment("corr-del-1", "req", "obl"));
        c1.expiresAt = now.minusSeconds(5);
        save(c1);

        Commitment c2 = save(openCommitment("corr-del-2", "req", "obl"));
        c2.expiresAt = now.plusSeconds(60);
        save(c2);

        long deleted = deleteExpiredBefore(now);
        assertEquals(1, deleted);
        assertTrue(findByCorrelationId("corr-del-1").isEmpty());
        assertTrue(findByCorrelationId("corr-del-2").isPresent());
    }

    // --- Robustness ---

    @Test
    void allTerminalStatesExcludedFromOpenQueries() {
        UUID ch = UUID.randomUUID();
        for (CommitmentState terminal : List.of(CommitmentState.FULFILLED,
                CommitmentState.DECLINED, CommitmentState.FAILED,
                CommitmentState.DELEGATED, CommitmentState.EXPIRED)) {
            Commitment c = save(openCommitment("corr-term-" + terminal, "req", "obl", ch));
            c.state = terminal;
            c.resolvedAt = Instant.now();
            save(c);
        }
        assertThat(findOpenByObligor("obl", ch)).isEmpty();
        assertThat(findOpenByRequester("req", ch)).isEmpty();
    }

    protected Commitment openCommitment(String correlationId, String requester, String obligor) {
        return openCommitment(correlationId, requester, obligor, UUID.randomUUID());
    }

    protected Commitment openCommitment(String correlationId, String requester, String obligor,
            UUID channelId) {
        Commitment c = new Commitment();
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.messageType = MessageType.COMMAND;
        c.requester = requester;
        c.obligor = obligor;
        c.state = CommitmentState.OPEN;
        return c;
    }
}
