package io.casehub.qhorus.runtime.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;

public interface CommitmentStore {

    Commitment save(Commitment commitment);

    Optional<Commitment> findById(UUID commitmentId);

    Optional<Commitment> findByCorrelationId(String correlationId);

    /** All non-terminal commitments where this agent is the obligor (what do I owe?). */
    List<Commitment> findOpenByObligor(String obligor, UUID channelId);

    /** All non-terminal commitments where this agent is the requester (what's owed to me?). */
    List<Commitment> findOpenByRequester(String requester, UUID channelId);

    /** All commitments in a given state on a channel. */
    List<Commitment> findByState(CommitmentState state, UUID channelId);

    /** All OPEN or ACKNOWLEDGED commitments whose expiresAt is strictly before the cutoff. */
    List<Commitment> findExpiredBefore(Instant cutoff);

    /** All OPEN or ACKNOWLEDGED commitments across all channels, sorted oldest first. */
    List<Commitment> findAllOpen();

    void deleteById(UUID commitmentId);

    long deleteExpiredBefore(Instant cutoff);
}
