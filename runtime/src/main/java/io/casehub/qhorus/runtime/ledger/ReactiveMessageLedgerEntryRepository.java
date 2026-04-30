package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.ledger.runtime.repository.ReactiveLedgerEntryRepository;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link MessageLedgerEntryRepository}.
 *
 * <p>
 * {@code @Alternative} — inactive by default. Activate via
 * {@code quarkus.arc.selected-alternatives} when a reactive datasource is configured.
 * Tests are {@code @Disabled} in CI (requires PostgreSQL reactive driver, not H2).
 *
 * <p>
 * {@link LedgerAttestation} methods throw {@link UnsupportedOperationException} —
 * reactive attestation persistence is not yet available in casehub-ledger.
 *
 * <p>
 * Refs #105, Epic #99.
 */
@Alternative
@ApplicationScoped
public class ReactiveMessageLedgerEntryRepository implements ReactiveLedgerEntryRepository {

    @Inject
    MessageReactivePanacheRepo repo;

    @Override
    public Uni<LedgerEntry> save(final LedgerEntry entry) {
        return repo.persist((MessageLedgerEntry) entry).map(e -> (LedgerEntry) e);
    }

    /**
     * All entries for a channel, ordered by sequence number ascending.
     */
    public Uni<List<MessageLedgerEntry>> findByChannelId(final UUID channelId) {
        return repo.list("subjectId = ?1 ORDER BY sequenceNumber ASC", channelId);
    }

    /**
     * Returns the most recent COMMAND or HANDOFF entry with the given correlationId.
     * Used at write time to resolve {@code causedByEntryId}.
     */
    public Uni<Optional<MessageLedgerEntry>> findLatestByCorrelationId(final UUID channelId,
            final String correlationId) {
        return repo.find(
                "subjectId = ?1 AND correlationId = ?2 AND messageType IN ('COMMAND','HANDOFF') " +
                        "ORDER BY sequenceNumber DESC",
                channelId, correlationId)
                .firstResult()
                .map(Optional::ofNullable);
    }

    @Override
    public Uni<Optional<LedgerEntry>> findLatestBySubjectId(final UUID subjectId) {
        return repo.find("subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResult()
                .map(e -> Optional.ofNullable((LedgerEntry) e));
    }

    @Override
    public Uni<Optional<LedgerEntry>> findEntryById(final UUID id) {
        return repo.findById(id).map(e -> Optional.ofNullable((LedgerEntry) e));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findBySubjectId(final UUID subjectId) {
        return repo.list("subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> listAll() {
        return repo.listAll().map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findAllEvents() {
        return repo.list("entryType = ?1", LedgerEntryType.EVENT)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorId(final String actorId, final Instant from, final Instant to) {
        return repo.list(
                "actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorId, from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorRole(final String actorRole, final Instant from, final Instant to) {
        return repo.list(
                "actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorRole, from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByTimeRange(final Instant from, final Instant to) {
        return repo.list("occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY occurredAt ASC", from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findCausedBy(final UUID entryId) {
        return repo.list("causedByEntryId = ?1 ORDER BY sequenceNumber ASC", entryId)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    @Override
    public Uni<LedgerAttestation> saveAttestation(final LedgerAttestation attestation) {
        throw new UnsupportedOperationException(
                "Reactive attestation writes not yet supported — use blocking LedgerEntryRepository");
    }

    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryId(final UUID ledgerEntryId) {
        throw new UnsupportedOperationException(
                "Reactive attestation reads not yet supported — use blocking LedgerEntryRepository");
    }

    @Override
    public Uni<Map<UUID, List<LedgerAttestation>>> findAttestationsForEntries(final Set<UUID> entryIds) {
        throw new UnsupportedOperationException(
                "Reactive attestation reads not yet supported — use blocking LedgerEntryRepository");
    }
}
