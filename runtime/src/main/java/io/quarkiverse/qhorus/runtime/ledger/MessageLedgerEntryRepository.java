package io.quarkiverse.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;
import io.quarkus.hibernate.orm.PersistenceUnit;

/**
 * Blocking JPA repository for {@link MessageLedgerEntry}.
 *
 * <p>
 * Implements {@link LedgerEntryRepository} using direct {@link EntityManager} queries.
 * Key query: {@link #listEntries} — unified filtered query supporting type, agent, time,
 * and cursor filters. {@link #findLatestByCorrelationId} resolves causal chain links
 * at write time (DONE/FAILURE/DECLINE/HANDOFF → their originating COMMAND or HANDOFF).
 *
 * <p>
 * Refs #101, Epic #99.
 */
@Priority(10)
@ApplicationScoped
public class MessageLedgerEntryRepository implements LedgerEntryRepository {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        em.persist(entry);
        return entry;
    }

    /** All entries for a channel, ordered by sequence number ascending. */
    public List<MessageLedgerEntry> findByChannelId(final UUID channelId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber ASC",
                MessageLedgerEntry.class)
                .setParameter("sid", channelId)
                .getResultList();
    }

    /**
     * Filtered query for the {@code list_ledger_entries} MCP tool. All parameters except
     * {@code channelId} and {@code limit} are optional (pass null to skip).
     *
     * @param channelId scopes the query to this channel
     * @param messageTypes if non-null/non-empty, only entries whose {@code messageType} is in this set
     * @param afterSequence if non-null, only entries with sequenceNumber &gt; afterSequence
     * @param agentId if non-null/blank, filter by actorId
     * @param since if non-null, filter by occurredAt &gt;= since
     * @param limit max results
     */
    public List<MessageLedgerEntry> listEntries(final UUID channelId, final Set<String> messageTypes,
            final Long afterSequence, final String agentId, final Instant since, final int limit) {

        final StringBuilder jpql = new StringBuilder(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = ?1");
        final List<Object> params = new ArrayList<>();
        params.add(channelId);

        if (messageTypes != null && !messageTypes.isEmpty()) {
            jpql.append(" AND e.messageType IN (?").append(params.size() + 1).append(")");
            params.add(messageTypes);
        }
        if (afterSequence != null) {
            jpql.append(" AND e.sequenceNumber > ?").append(params.size() + 1);
            params.add(afterSequence);
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

        final TypedQuery<MessageLedgerEntry> query = em.createQuery(jpql.toString(), MessageLedgerEntry.class);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * Returns the most recent COMMAND or HANDOFF entry on this channel with the given
     * correlation ID. Used at write time to resolve {@code causedByEntryId} for DONE,
     * FAILURE, DECLINE, and HANDOFF entries.
     */
    public Optional<MessageLedgerEntry> findLatestByCorrelationId(final UUID channelId,
            final String correlationId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e " +
                        "WHERE e.subjectId = :sid AND e.correlationId = :corr " +
                        "AND e.messageType IN ('COMMAND', 'HANDOFF') " +
                        "ORDER BY e.sequenceNumber DESC",
                MessageLedgerEntry.class)
                .setParameter("sid", channelId)
                .setParameter("corr", correlationId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber DESC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    @Override
    public Optional<LedgerEntry> findEntryById(final UUID id) {
        return Optional.ofNullable(em.find(MessageLedgerEntry.class, id));
    }

    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.subjectId = :sid ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("sid", subjectId)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> listAll() {
        return em.createQuery("SELECT e FROM LedgerEntry e ORDER BY e.sequenceNumber ASC", LedgerEntry.class)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findAllEvents() {
        return em.createQuery(
                "SELECT e FROM MessageLedgerEntry e WHERE e.entryType = :type ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("type", LedgerEntryType.EVENT)
                .getResultList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(final UUID ledgerEntryId) {
        return em.createNamedQuery("LedgerAttestation.findByEntryId", LedgerAttestation.class)
                .setParameter("entryId", ledgerEntryId)
                .getResultList();
    }

    @Override
    public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
        em.persist(attestation);
        return attestation;
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(final Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return em.createNamedQuery("LedgerAttestation.findByEntryIds", LedgerAttestation.class)
                .setParameter("entryIds", entryIds)
                .getResultList()
                .stream()
                .collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    @Override
    public List<LedgerEntry> findByActorId(final String actorId, final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorId = :aid " +
                        "AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("aid", actorId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole, final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.actorRole = :role " +
                        "AND e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("role", actorRole)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findByTimeRange(final Instant from, final Instant to) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.occurredAt >= :from AND e.occurredAt <= :to ORDER BY e.occurredAt ASC",
                LedgerEntry.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    @Override
    public List<LedgerEntry> findCausedBy(final UUID entryId) {
        return em.createQuery(
                "SELECT e FROM LedgerEntry e WHERE e.causedByEntryId = :eid ORDER BY e.sequenceNumber ASC",
                LedgerEntry.class)
                .setParameter("eid", entryId)
                .getResultList();
    }
}
