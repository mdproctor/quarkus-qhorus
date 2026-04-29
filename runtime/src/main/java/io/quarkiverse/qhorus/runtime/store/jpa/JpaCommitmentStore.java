package io.quarkiverse.qhorus.runtime.store.jpa;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.api.message.CommitmentState;
import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.store.CommitmentStore;

@ApplicationScoped
public class JpaCommitmentStore implements CommitmentStore {

    @Inject
    CommitmentPanacheRepo repo;

    @Override
    @Transactional
    public Commitment save(Commitment c) {
        if (c.id == null) {
            repo.persist(c);
        } else {
            c = repo.getEntityManager().merge(c);
        }
        return c;
    }

    @Override
    public Optional<Commitment> findById(UUID id) {
        return repo.findByIdOptional(id);
    }

    @Override
    @Transactional
    public Optional<Commitment> findByCorrelationId(String correlationId) {
        // Prefer the active (non-terminal) commitment — supports delegation chains where
        // multiple records share a correlationId.
        return repo.find("correlationId = ?1 ORDER BY createdAt DESC", correlationId)
                .list()
                .stream()
                .filter(c -> !c.state.isTerminal())
                .findFirst()
                .or(() -> repo.find("correlationId = ?1 ORDER BY createdAt DESC", correlationId)
                        .firstResultOptional());
    }

    @Override
    public List<Commitment> findOpenByObligor(String obligor, UUID channelId) {
        return repo.list(
                "obligor = ?1 AND channelId = ?2 AND state NOT IN ?3",
                obligor, channelId, terminalStates());
    }

    @Override
    public List<Commitment> findOpenByRequester(String requester, UUID channelId) {
        return repo.list(
                "requester = ?1 AND channelId = ?2 AND state NOT IN ?3",
                requester, channelId, terminalStates());
    }

    @Override
    public List<Commitment> findByState(CommitmentState state, UUID channelId) {
        return repo.list("state = ?1 AND channelId = ?2", state, channelId);
    }

    @Override
    public List<Commitment> findExpiredBefore(Instant cutoff) {
        return repo.list(
                "expiresAt < ?1 AND state NOT IN ?2",
                cutoff, terminalStates());
    }

    @Override
    public List<Commitment> findAllOpen() {
        return repo.list(
                "state IN ?1 ORDER BY expiresAt ASC NULLS LAST",
                List.of(CommitmentState.OPEN, CommitmentState.ACKNOWLEDGED));
    }

    @Override
    @Transactional
    public void deleteById(UUID id) {
        repo.deleteById(id);
    }

    @Override
    @Transactional
    public long deleteExpiredBefore(Instant cutoff) {
        return repo.delete("expiresAt < ?1 AND state NOT IN ?2", cutoff, terminalStates());
    }

    private List<CommitmentState> terminalStates() {
        return List.of(CommitmentState.FULFILLED, CommitmentState.DECLINED,
                CommitmentState.FAILED, CommitmentState.DELEGATED, CommitmentState.EXPIRED);
    }
}
