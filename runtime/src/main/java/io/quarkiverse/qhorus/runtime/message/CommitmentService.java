package io.quarkiverse.qhorus.runtime.message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.api.message.CommitmentState;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.CommitmentStore;

/**
 * Owns all state machine logic for the commitment obligation lifecycle.
 *
 * <p>
 * All transitions are idempotent and silent-no-op when:
 * <ul>
 * <li>The {@code correlationId} is {@code null} or blank</li>
 * <li>No Commitment exists for the correlationId</li>
 * <li>The Commitment is already in a terminal state</li>
 * </ul>
 */
@ApplicationScoped
public class CommitmentService {

    @Inject
    CommitmentStore store;

    /**
     * Called by MessageService when a QUERY or COMMAND is sent.
     * Creates a new Commitment with OPEN state.
     */
    @Transactional
    public Commitment open(UUID commitmentId, String correlationId, UUID channelId,
            MessageType type, String requester, String obligor, Instant expiresAt) {
        Commitment c = new Commitment();
        c.id = commitmentId;
        c.correlationId = correlationId;
        c.channelId = channelId;
        c.messageType = type;
        c.requester = requester;
        c.obligor = obligor;
        c.expiresAt = expiresAt;
        c.state = CommitmentState.OPEN;
        return store.save(c);
    }

    /**
     * Called when STATUS is received. Transitions OPEN or ACKNOWLEDGED → ACKNOWLEDGED.
     * Sets {@code acknowledgedAt} only on the first STATUS.
     */
    @Transactional
    public Optional<Commitment> acknowledge(String correlationId) {
        return transition(correlationId, CommitmentState.ACKNOWLEDGED, c -> {
            if (c.acknowledgedAt == null) {
                c.acknowledgedAt = Instant.now();
            }
        });
    }

    /** Called when RESPONSE (for QUERY) or DONE (for COMMAND) is received. */
    @Transactional
    public Optional<Commitment> fulfill(String correlationId) {
        return transition(correlationId, CommitmentState.FULFILLED,
                c -> c.resolvedAt = Instant.now());
    }

    /** Called when DECLINE is received. */
    @Transactional
    public Optional<Commitment> decline(String correlationId) {
        return transition(correlationId, CommitmentState.DECLINED,
                c -> c.resolvedAt = Instant.now());
    }

    /** Called when FAILURE is received. */
    @Transactional
    public Optional<Commitment> fail(String correlationId) {
        return transition(correlationId, CommitmentState.FAILED,
                c -> c.resolvedAt = Instant.now());
    }

    /**
     * Called when HANDOFF is received.
     * Transitions the current non-terminal commitment to DELEGATED and creates
     * a child commitment for {@code delegatedTo} with the same {@code correlationId}
     * so {@code wait_for_reply} polling continues transparently.
     */
    @Transactional
    public Optional<Commitment> delegate(String correlationId, String delegatedTo) {
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        return store.findByCorrelationId(correlationId)
                .filter(c -> !c.state.isTerminal())
                .map(c -> {
                    UUID parentId = c.id;
                    c.state = CommitmentState.DELEGATED;
                    c.delegatedTo = delegatedTo;
                    c.resolvedAt = Instant.now();
                    store.save(c);
                    Commitment child = new Commitment();
                    child.correlationId = correlationId; // takes over original correlationId
                    child.channelId = c.channelId;
                    child.messageType = c.messageType;
                    child.requester = c.requester;
                    child.obligor = delegatedTo;
                    child.expiresAt = c.expiresAt;
                    child.state = CommitmentState.OPEN;
                    child.parentCommitmentId = parentId;
                    store.save(child);
                    return c;
                });
    }

    /**
     * Called by the expiry scheduler. Transitions all overdue OPEN/ACKNOWLEDGED
     * commitments to EXPIRED. Returns the number expired.
     */
    @Transactional
    public int expireOverdue() {
        List<Commitment> overdue = store.findExpiredBefore(Instant.now());
        overdue.forEach(c -> {
            c.state = CommitmentState.EXPIRED;
            c.resolvedAt = Instant.now();
            store.save(c);
        });
        return overdue.size();
    }

    private Optional<Commitment> transition(String correlationId, CommitmentState target,
            Consumer<Commitment> update) {
        if (correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        return store.findByCorrelationId(correlationId)
                .filter(c -> !c.state.isTerminal())
                .map(c -> {
                    update.accept(c);
                    c.state = target;
                    return store.save(c);
                });
    }
}
