package io.quarkiverse.qhorus.runtime.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.repository.ReactiveLedgerEntryRepository;
import io.smallrye.mutiny.Uni;

/**
 * Reactive implementation of {@link ReactiveLedgerEntryRepository} for
 * {@link AgentMessageLedgerEntry}.
 *
 * <p>
 * Delegates persistence operations to {@link AgentMessageReactivePanacheRepo}, which
 * provides the Hibernate Reactive session infrastructure. All methods return {@link Uni}
 * and are safe to call from the Vert.x event loop without {@code @Blocking}.
 *
 * <p>
 * {@link LedgerAttestation} is still a blocking {@code PanacheEntityBase} — attestation
 * methods throw {@link UnsupportedOperationException} until a reactive attestation repo
 * is available in quarkus-ledger. This does not affect current Qhorus services, which do
 * not write attestations.
 *
 * <p>
 * Refs #68.
 */
/**
 * Marked {@code @Alternative} — inactive by default. Activate alongside
 * {@link AgentMessageReactivePanacheRepo} via {@code quarkus.arc.selected-alternatives}
 * when configuring a reactive datasource.
 */
@Alternative
@ApplicationScoped
public class ReactiveAgentMessageLedgerEntryRepository implements ReactiveLedgerEntryRepository {

    @Inject
    AgentMessageReactivePanacheRepo repo;

    /** {@inheritDoc} */
    @Override
    public Uni<LedgerEntry> save(final LedgerEntry entry) {
        return repo.persist((AgentMessageLedgerEntry) entry).map(e -> (LedgerEntry) e);
    }

    /**
     * Return all {@link AgentMessageLedgerEntry} rows for the given channel, ordered by
     * sequence number ascending.
     *
     * @param channelId the channel UUID
     * @return ordered list of entries; empty if none exist
     */
    public Uni<List<AgentMessageLedgerEntry>> findByChannelId(final UUID channelId) {
        return repo.list("subjectId = ?1 ORDER BY sequenceNumber ASC", channelId);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findBySubjectId(final UUID subjectId) {
        return repo.list("subjectId = ?1 ORDER BY sequenceNumber ASC", subjectId)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    /** {@inheritDoc} */
    @Override
    public Uni<Optional<LedgerEntry>> findLatestBySubjectId(final UUID subjectId) {
        return repo.find("subjectId = ?1 ORDER BY sequenceNumber DESC", subjectId)
                .firstResult()
                .map(e -> Optional.ofNullable((LedgerEntry) e));
    }

    /** {@inheritDoc} */
    @Override
    public Uni<Optional<LedgerEntry>> findEntryById(final UUID id) {
        return repo.findById(id).map(e -> Optional.ofNullable((LedgerEntry) e));
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> listAll() {
        return repo.listAll().map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findAllEvents() {
        return repo.list("entryType = ?1", LedgerEntryType.EVENT)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorId(final String actorId, final Instant from, final Instant to) {
        return repo.list(
                "actorId = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorId, from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByActorRole(final String actorRole, final Instant from, final Instant to) {
        return repo.list(
                "actorRole = ?1 AND occurredAt >= ?2 AND occurredAt <= ?3 ORDER BY occurredAt ASC",
                actorRole, from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findByTimeRange(final Instant from, final Instant to) {
        return repo.list("occurredAt >= ?1 AND occurredAt <= ?2 ORDER BY occurredAt ASC", from, to)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public Uni<List<LedgerEntry>> findCausedBy(final UUID entryId) {
        return repo.list("causedByEntryId = ?1 ORDER BY sequenceNumber ASC", entryId)
                .map(l -> (List<LedgerEntry>) (List<?>) l);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — {@code LedgerAttestation} is a blocking
     *         {@code PanacheEntityBase}; reactive attestation persistence is not yet available
     */
    @Override
    public Uni<LedgerAttestation> saveAttestation(final LedgerAttestation attestation) {
        throw new UnsupportedOperationException(
                "Reactive attestation writes not yet supported — use blocking LedgerEntryRepository");
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — see {@link #saveAttestation}
     */
    @Override
    public Uni<List<LedgerAttestation>> findAttestationsByEntryId(final UUID ledgerEntryId) {
        throw new UnsupportedOperationException(
                "Reactive attestation reads not yet supported — use blocking LedgerEntryRepository");
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — see {@link #saveAttestation}
     */
    @Override
    public Uni<Map<UUID, List<LedgerAttestation>>> findAttestationsForEntries(final Set<UUID> entryIds) {
        throw new UnsupportedOperationException(
                "Reactive attestation reads not yet supported — use blocking LedgerEntryRepository");
    }
}
