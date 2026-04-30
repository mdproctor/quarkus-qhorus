package io.casehub.qhorus.runtime.ledger;

import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.ledger.api.model.ActorTypeResolver;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.Message;

/**
 * Writes immutable audit ledger entries for every message sent on a channel.
 *
 * <p>
 * Called from {@code QhorusMcpTools.sendMessage} for all 9 message types — no
 * conditional branching in the caller. Every speech act on a channel is permanently
 * recorded as a {@link MessageLedgerEntry}. The CommitmentStore is the live obligation
 * state; this ledger is the tamper-evident historical record.
 *
 * <p>
 * For EVENT messages, telemetry fields ({@code toolName}, {@code durationMs}, etc.) are
 * extracted from the JSON payload. Malformed or partial payloads still produce an entry
 * — the speech act happened regardless of telemetry quality. All other types store
 * {@code message.content} verbatim in the {@code content} field.
 *
 * <p>
 * DONE, FAILURE, DECLINE, and HANDOFF entries have {@code causedByEntryId} resolved to
 * the most recent COMMAND or HANDOFF entry sharing the same {@code correlationId} on the
 * same channel — creating a traversable obligation chain in the ledger.
 *
 * <p>
 * For DONE, FAILURE, and DECLINE: a {@link LedgerAttestation} is written against the
 * originating COMMAND's entry via {@link CommitmentAttestationPolicy}. Verdict and
 * confidence feed the Bayesian Beta trust score in casehub-ledger. The CommitmentStore
 * is NOT queried here — attestation verdict is derived from {@link MessageType} directly,
 * which avoids a transaction-visibility bug (the outer transaction's commitment update
 * is not yet committed when this {@code REQUIRES_NEW} transaction runs).
 *
 * <p>
 * The {@code actorId} on each entry is resolved through {@link InstanceActorIdProvider}
 * to map session-scoped instanceIds to persona-scoped ledger actorIds.
 *
 * <p>
 * Ledger write failures are caught and logged; they never propagate to the caller.
 * The message pipeline must not be affected by ledger issues.
 *
 * <p>
 * Refs #102, #123, #124, Epic #99.
 */
@ApplicationScoped
public class LedgerWriteService {

    private static final Logger LOG = Logger.getLogger(LedgerWriteService.class);
    private static final Set<String> CAUSAL_TYPES = Set.of("DONE", "FAILURE", "DECLINE", "HANDOFF");
    private static final Set<MessageType> ATTESTATION_TYPES = Set.of(
            MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE);

    @Inject
    public MessageLedgerEntryRepository repository;

    @Inject
    public LedgerConfig config;

    @Inject
    public InstanceActorIdProvider actorIdProvider;

    @Inject
    public CommitmentAttestationPolicy attestationPolicy;

    @Inject
    public ObjectMapper objectMapper;

    /**
     * Record the given message as an immutable ledger entry.
     *
     * <p>
     * Runs in its own transaction ({@code REQUIRES_NEW}) so that a ledger write failure
     * does not roll back the calling transaction.
     *
     * @param ch the channel the message was sent to
     * @param message the persisted message to record
     */
    @Transactional(value = Transactional.TxType.REQUIRES_NEW)
    public void record(final Channel ch, final Message message) {
        if (!config.enabled()) {
            return;
        }

        final Optional<LedgerEntry> latest = repository.findLatestBySubjectId(ch.id);
        final int sequenceNumber = latest.map(e -> e.sequenceNumber + 1).orElse(1);

        final String resolvedActorId = actorIdProvider.resolve(message.sender);

        final MessageLedgerEntry entry = new MessageLedgerEntry();
        entry.subjectId = ch.id;
        entry.channelId = ch.id;
        entry.messageId = message.id;
        entry.messageType = message.messageType.name();
        entry.target = message.target;
        entry.correlationId = message.correlationId;
        entry.commitmentId = message.commitmentId;
        entry.actorId = resolvedActorId;
        entry.actorType = ActorTypeResolver.resolve(resolvedActorId);
        entry.occurredAt = message.createdAt.truncatedTo(ChronoUnit.MILLIS);
        entry.sequenceNumber = sequenceNumber;
        entry.entryType = switch (message.messageType) {
            case QUERY, COMMAND, HANDOFF -> LedgerEntryType.COMMAND;
            default -> LedgerEntryType.EVENT;
        };

        if (message.messageType == MessageType.EVENT) {
            populateTelemetry(entry, message.content);
        } else {
            entry.content = message.content;
        }

        if (CAUSAL_TYPES.contains(message.messageType.name()) && message.correlationId != null) {
            // ifPresent is intentional: attestation requires a committed COMMAND/HANDOFF entry as its
            // anchor (attestation.ledgerEntryId). If none exists for this correlationId (e.g. the
            // originating COMMAND was sent before the ledger was enabled), causedByEntryId stays null
            // and attestation is silently skipped — a gap in the trust chain, not a bug.
            repository.findLatestByCorrelationId(ch.id, message.correlationId)
                    .ifPresent(prior -> {
                        entry.causedByEntryId = prior.id;
                        if (ATTESTATION_TYPES.contains(message.messageType)) {
                            writeAttestation(ch, prior, message.messageType, resolvedActorId);
                        }
                    });
        }

        repository.save(entry);
    }

    /**
     * @deprecated Use {@link #record(Channel, Message)} instead. Kept for compilation
     *             compatibility until {@code QhorusMcpTools} is updated in Epic #99.
     */
    @Deprecated
    @Transactional(value = Transactional.TxType.REQUIRES_NEW)
    public void recordEvent(final Channel ch, final Message message) {
        record(ch, message);
    }

    private void writeAttestation(final Channel ch, final MessageLedgerEntry commandEntry,
            final MessageType terminalType, final String resolvedActorId) {
        attestationPolicy.attestationFor(terminalType, resolvedActorId).ifPresent(outcome -> {
            try {
                final LedgerAttestation attestation = new LedgerAttestation();
                attestation.ledgerEntryId = commandEntry.id;
                attestation.subjectId = ch.id;
                attestation.attestorId = outcome.attestorId();
                attestation.attestorType = outcome.attestorType();
                attestation.verdict = outcome.verdict();
                attestation.confidence = outcome.confidence();
                repository.saveAttestation(attestation);
                LOG.debugf("LedgerAttestation %s written for COMMAND entry %s (correlationId='%s')",
                        attestation.verdict, commandEntry.id, commandEntry.correlationId);
            } catch (final Exception e) {
                LOG.warnf("Could not write attestation for entry %s — trust signal lost but pipeline unaffected",
                        commandEntry.id);
            }
        });
    }

    private void populateTelemetry(final MessageLedgerEntry entry, final String content) {
        if (content == null || !content.stripLeading().startsWith("{")) {
            return;
        }
        try {
            final JsonNode root = objectMapper.readTree(content);
            final JsonNode tn = root.get("tool_name");
            if (tn != null && tn.isTextual()) {
                entry.toolName = tn.asText();
            }
            final JsonNode dm = root.get("duration_ms");
            if (dm != null && dm.isNumber()) {
                entry.durationMs = dm.asLong();
            }
            final JsonNode tc = root.get("token_count");
            if (tc != null && tc.isNumber()) {
                entry.tokenCount = tc.asLong();
            }
            final JsonNode cr = root.get("context_refs");
            if (cr != null && !cr.isNull()) {
                try {
                    entry.contextRefs = objectMapper.writeValueAsString(cr);
                } catch (final Exception ignored) {
                    LOG.warnf("Could not serialise context_refs for ledger entry on message %d",
                            entry.messageId);
                }
            }
            final JsonNode se = root.get("source_entity");
            if (se != null && !se.isNull()) {
                try {
                    entry.sourceEntity = objectMapper.writeValueAsString(se);
                } catch (final Exception ignored) {
                    LOG.warnf("Could not serialise source_entity for ledger entry on message %d",
                            entry.messageId);
                }
            }
        } catch (final Exception e) {
            LOG.warnf("Could not parse EVENT content as JSON for message %d — telemetry fields will be null",
                    entry.messageId);
        }
    }
}
