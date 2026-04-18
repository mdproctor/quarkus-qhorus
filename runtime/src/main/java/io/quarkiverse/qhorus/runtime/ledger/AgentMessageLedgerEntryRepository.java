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

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.LedgerEntryRepository;

/**
 * Hibernate ORM / Panache implementation of {@link LedgerEntryRepository} scoped to
 * {@link AgentMessageLedgerEntry}.
 *
 * <p>
 * This bean does NOT extend {@code JpaLedgerEntryRepository} from quarkus-ledger in order
 * to avoid CDI ambiguity. Instead it directly implements {@link LedgerEntryRepository} in
 * full and adds the typed {@link #findByChannelId(UUID)} convenience method.
 *
 * <p>
 * Refs #51, Epic #50.
 */
@ApplicationScoped
public class AgentMessageLedgerEntryRepository implements LedgerEntryRepository {

    /** {@inheritDoc} */
    @Override
    public LedgerEntry save(final LedgerEntry entry) {
        entry.persist();
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
        return AgentMessageLedgerEntry.list("subjectId = ?1 ORDER BY sequenceNumber ASC", channelId);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findBySubjectId(final UUID subjectId) {
        return LedgerEntry.list("subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
        return LedgerEntry.find("subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResultOptional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<LedgerEntry> findById(final UUID id) {
        return Optional.ofNullable(LedgerEntry.findById(id));
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
    public List<LedgerEntry> findAllEvents() {
        return LedgerEntry.find("entryType = ?1", LedgerEntryType.EVENT).list();
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
        return LedgerEntry.list("actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY sequenceNumber ASC",
                actorId, from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByActorRole(final String actorRole, final Instant from, final Instant to) {
        return LedgerEntry.list("actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY sequenceNumber ASC",
                actorRole, from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findByTimeRange(final Instant from, final Instant to) {
        return LedgerEntry.list("occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY sequenceNumber ASC", from, to);
    }

    /** {@inheritDoc} */
    @Override
    public List<LedgerEntry> findCausedBy(final UUID causeId) {
        return LedgerEntry.list("causeId = ?1 ORDER BY sequenceNumber ASC", causeId);
    }
}
