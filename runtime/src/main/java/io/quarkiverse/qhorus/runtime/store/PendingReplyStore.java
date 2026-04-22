package io.quarkiverse.qhorus.runtime.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.quarkiverse.qhorus.runtime.message.PendingReply;

public interface PendingReplyStore {

    /** Persist a new PendingReply or update an existing one (matched by correlationId — the business key). */
    PendingReply save(PendingReply pr);

    Optional<PendingReply> findByCorrelationId(String correlationId);

    void deleteByCorrelationId(String correlationId);

    boolean existsByCorrelationId(String correlationId);

    /** All entries whose expiresAt is strictly before the given cutoff. */
    List<PendingReply> findExpiredBefore(Instant cutoff);

    /** Delete all entries whose expiresAt is strictly before the given cutoff. */
    void deleteExpiredBefore(Instant cutoff);
}
