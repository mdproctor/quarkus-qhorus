package io.casehub.qhorus.runtime.ledger;

import java.time.temporal.ChronoUnit;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.Message;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link LedgerWriteService}.
 *
 * <p>
 * {@code @Alternative} — inactive by default. Writes immutable audit ledger entries for
 * every message type using the reactive ledger repository. Called from
 * {@code ReactiveQhorusMcpTools.sendMessage} (via the blocking bridge in the current
 * {@code @Blocking} implementation). Failures are caught and swallowed at the call site
 * — the message pipeline must not be affected by ledger issues.
 *
 * <p>
 * Refs #105, Epic #99.
 */
@Alternative
@ApplicationScoped
public class ReactiveLedgerWriteService {

    private static final Logger LOG = Logger.getLogger(ReactiveLedgerWriteService.class);
    private static final Set<String> CAUSAL_TYPES = Set.of("DONE", "FAILURE", "DECLINE", "HANDOFF");
    private static final Set<MessageType> ATTESTATION_TYPES = Set.of(
            MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE);

    @Inject
    ReactiveMessageLedgerEntryRepository reactiveRepo;

    @Inject
    LedgerConfig config;

    @Inject
    public InstanceActorIdProvider actorIdProvider;

    @Inject
    public CommitmentAttestationPolicy attestationPolicy;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Record the given message as an immutable ledger entry via the reactive stack.
     *
     * @param ch the channel the message was sent to
     * @param message the persisted message to record
     */
    public Uni<Void> record(final Channel ch, final Message message) {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        return Panache.withTransaction(() -> reactiveRepo.findLatestBySubjectId(ch.id).flatMap(latestOpt -> {
            final int sequenceNumber = latestOpt.map(e -> e.sequenceNumber + 1).orElse(1);

            final MessageLedgerEntry entry = new MessageLedgerEntry();
            entry.subjectId = ch.id;
            entry.channelId = ch.id;
            entry.messageId = message.id;
            entry.messageType = message.messageType.name();
            entry.target = message.target;
            entry.correlationId = message.correlationId;
            entry.commitmentId = message.commitmentId;
            final String resolvedActorId = actorIdProvider.resolve(message.sender);
            entry.actorId = resolvedActorId;
            entry.actorType = ActorType.AGENT;
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
                return reactiveRepo.findLatestByCorrelationId(ch.id, message.correlationId)
                        .flatMap(priorOpt -> {
                            priorOpt.ifPresent(prior -> {
                                entry.causedByEntryId = prior.id;
                                if (ATTESTATION_TYPES.contains(message.messageType)) {
                                    logSkippedAttestation(prior, message.messageType);
                                }
                            });
                            return reactiveRepo.save(entry).replaceWithVoid();
                        });
            }
            return reactiveRepo.save(entry).replaceWithVoid();
        }));
    }

    private void logSkippedAttestation(final MessageLedgerEntry commandEntry,
            final MessageType terminalType) {
        // Reactive attestation writes not yet supported — ReactiveMessageLedgerEntryRepository
        // throws UnsupportedOperationException on saveAttestation(). The blocking LedgerWriteService
        // is the authoritative attestation path. Log at DEBUG so it's trackable.
        LOG.debugf("Reactive path: attestation for %s on COMMAND entry %s deferred to blocking path",
                terminalType, commandEntry.id);
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
                    LOG.warnf("Could not serialise context_refs for reactive ledger entry on message %d",
                            entry.messageId);
                }
            }
            final JsonNode se = root.get("source_entity");
            if (se != null && !se.isNull()) {
                try {
                    entry.sourceEntity = objectMapper.writeValueAsString(se);
                } catch (final Exception ignored) {
                    LOG.warnf("Could not serialise source_entity for reactive ledger entry on message %d",
                            entry.messageId);
                }
            }
        } catch (final Exception e) {
            LOG.warnf("Could not parse EVENT content as JSON for reactive message %d — telemetry fields will be null",
                    entry.messageId);
        }
    }
}
