package io.quarkiverse.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;

/**
 * Blocking JPA implementation of {@link LedgerEntryRepository} scoped to
 * {@link AgentMessageLedgerEntry}.
 *
 * <p>
 * Uses {@link EntityManager} directly (not Panache entity statics) because
 * {@code LedgerEntry} is now a plain {@code @Entity} — static Panache methods are no
 * longer available on the base class. {@code LedgerAttestation} is still a
 * {@code PanacheEntityBase}; its Panache static methods are used as-is.
 *
 * <p>
 * Refs #68.
 */
@ApplicationScoped
public class AgentMessageLedgerEntryRepository implements LedgerEntryRepository {

    @Inject
    EntityManager em;

    /** {@inheritDoc} */
    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        em.persist(entry);
        return entry;
    }

    /**
     * Return all {@link AgentMessageLedgerEntry} rows for the given channel, ordered by
     * sequence number ascending.
     *
     * @param channelId the channel UUID
     * @return ordered list of entries; empty if none exist
     */
    public List<AgentMessageLedgerEntry> findByChannelId(final UUID channelId) {
        return em.createQuery(
                "SELECT e FROM AgentMessageLedgerEntry e WHERE e.subjectId = :subjectId ORDER BY e.sequenceNumber ASC",
                AgentMessageLedgerEntry.class)
                .setParameter("subjectId", channelId)
                .getResultList();
    }

    /**
     * Dynamic query for the {@code list_events} MCP tool. All filters are optional.
     *
     * @param channelId required channel UUID
     * @param afterSequence if non-null, only entries with sequenceNumber &gt; afterSequence
     * @param agentId if non-null/blank, filter by actorId
     * @param since if non-null, filter by occurredAt &gt;= since
     * @param limit max results
     * @return matching entries in sequence order
     */
    public List<AgentMessageLedgerEntry> listEventEntries(final UUID channelId, final Long afterSequence,
            final String agentId, final java.time.Instant since, final int limit) {
        final StringBuilder jpql = new StringBuilder(
                "SELECT e FROM AgentMessageLedgerEntry e WHERE e.subjectId = ?1");
        final java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(channelId);

        if (afterSequence != null) {
            jpql.append(" AND e.sequenceNumber > ?").append(params.size() + 1);
            params.add(afterSequence.intValue());
        }
        if (agentId != null && !agentId.isBlank()) {
            jpql.append(" AND e.actorId = ?").append(params.size() + 1);
            params.add(agentId);
        }
        if (since != null) {
            jpql.append(" AND e.occurredAt >= ?").append(params.size() + 1);
            params.add(since);
        }
        jpql.append(" ORDER BY e.sequenceNumber ASC");

        final jakarta.persistence.TypedQuery<AgentMessageLedgerEntry> query = em.createQuery(
                jpql.toString(), AgentMessageLedgerEntry.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM AgentMessageLedgerEntry e WHERE e.subjectId = :subjectId ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("subjectId", subjectId)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM AgentMessageLedgerEntry e WHERE e.subjectId = :subjectId ORDER BY e.sequenceNumber DESC",
                LedgerEntry.class)
                .setParameter("subjectId", subjectId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id) {
        return Optional.ofNullable(em.find(AgentMessageLedgerEntry.class, id));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> listAll() {
        return em.createQuery("SELECT e FROM LedgerEntry e ORDER BY e.sequenceNumber ASC", LedgerEntry.class)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findAllEvents() {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.entryType = :type ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("type", LedgerEntryType.EVENT)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return LedgerAttestation.list("ledgerEntryId = ?1 ORDER BY occurredAt ASC", ledgerEntryId);
    }

    /** {@inheritDoc} */
    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        attestation.persist();
        return attestation;
    }

    /** {@inheritDoc} */
    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        final List<LedgerAttestation> all = LedgerAttestation.list("ledgerEntryId IN ?1", entryIds);
        return all.stream().collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorId(final String actorId, final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorId = :actorId AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("actorId", actorId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole, final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorRole = :actorRole AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("actorRole", actorRole)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByTimeRange(final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findCausedBy(final UUID entryId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.causedByEntryId = :entryId ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("entryId", entryId)
                .getResultList();
    }
}
