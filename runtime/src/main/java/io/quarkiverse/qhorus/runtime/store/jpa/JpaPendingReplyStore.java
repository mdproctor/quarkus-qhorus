package io.quarkiverse.qhorus.runtime.store.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.runtime.store.PendingReplyStore;

@ApplicationScoped
public class JpaPendingReplyStore implements PendingReplyStore {

    @Override
    @Transactional
    public PendingReply save(PendingReply pr) {
        if (pr.id == null) {
            pr.persist();
        } else {
            pr = PendingReply.getEntityManager().merge(pr);
        }
        return pr;
    }

    @Override
    public Optional<PendingReply> findByCorrelationId(String correlationId) {
        return PendingReply.<PendingReply> find("correlationId", correlationId).firstResultOptional();
    }

    @Override
    @Transactional
    public void deleteByCorrelationId(String correlationId) {
        PendingReply.delete("correlationId", correlationId);
    }

    @Override
    public boolean existsByCorrelationId(String correlationId) {
        return PendingReply.count("correlationId", correlationId) > 0;
    }

    @Override
    public List<PendingReply> findExpiredBefore(Instant cutoff) {
        return PendingReply.<PendingReply> find("expiresAt < ?1", cutoff).list();
    }

    @Override
    @Transactional
    public void deleteExpiredBefore(Instant cutoff) {
        PendingReply.delete("expiresAt < ?1", cutoff);
    }
}
