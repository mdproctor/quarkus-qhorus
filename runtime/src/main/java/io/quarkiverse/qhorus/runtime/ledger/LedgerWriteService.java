package io.quarkiverse.qhorus.runtime.ledger;

import java.time.temporal.ChronoUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.service.LedgerHashChain;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.message.Message;

/**
 * Writes structured audit ledger entries for EVENT messages.
 *
 * <p>
 * Called from {@code QhorusMcpTools.sendMessage} when the message type is EVENT. Parses
 * the JSON payload, extracts mandatory telemetry fields ({@code tool_name}, {@code duration_ms}),
 * and persists an {@link AgentMessageLedgerEntry} in the same transaction as the message.
 *
 * <p>
 * Gracefully skips entries with missing mandatory fields — a warning is logged but no
 * exception is thrown. This keeps the message pipeline unaffected by malformed telemetry.
 *
 * <p>
 * Refs #52, Epic #50.
 */
@ApplicationScoped
public class LedgerWriteService {

    private static final Logger LOG = Logger.getLogger(LedgerWriteService.class);

    @Inject
    AgentMessageLedgerEntryRepository repository;

    @Inject
    LedgerConfig config;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Record an EVENT message as a structured ledger entry.
     *
     * <p>
     * Parses {@code message.content} as JSON. Mandatory fields: {@code tool_name} (String) and
     * {@code duration_ms} (Long). If either is absent or null, logs a warning and returns
     * without writing a ledger entry.
     *
     * @param ch the channel the EVENT was posted to
     * @param message the persisted EVENT message
     */
    @Transactional
    public void recordEvent(final Channel ch, final Message message) {
        if (!config.enabled()) {
            return;
        }

        // Parse JSON payload — only attempt if content looks like a JSON object
        final String content = message.content;
        if (content == null || !content.stripLeading().startsWith("{")) {
            return; // free-form EVENT content; not a structured telemetry payload
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            LOG.warnf("LedgerWriteService: could not parse EVENT content as JSON for message %d — skipping. Error: %s",
                    message.id, e.getMessage());
            return;
        }

        // Extract mandatory fields
        final JsonNode toolNameNode = root.get("tool_name");
        final JsonNode durationMsNode = root.get("duration_ms");

        if (toolNameNode == null || toolNameNode.isNull() || !toolNameNode.isTextual()) {
            LOG.warnf("LedgerWriteService: EVENT message %d missing mandatory 'tool_name' — skipping ledger entry",
                    message.id);
            return;
        }
        if (durationMsNode == null || durationMsNode.isNull() || !durationMsNode.isNumber()) {
            LOG.warnf("LedgerWriteService: EVENT message %d missing mandatory 'duration_ms' — skipping ledger entry",
                    message.id);
            return;
        }

        final String toolName = toolNameNode.asText();
        final long durationMs = durationMsNode.asLong();

        // Extract optional fields
        Long tokenCount = null;
        final JsonNode tokenCountNode = root.get("token_count");
        if (tokenCountNode != null && !tokenCountNode.isNull() && tokenCountNode.isNumber()) {
            tokenCount = tokenCountNode.asLong();
        }

        String contextRefs = null;
        final JsonNode contextRefsNode = root.get("context_refs");
        if (contextRefsNode != null && !contextRefsNode.isNull()) {
            try {
                contextRefs = objectMapper.writeValueAsString(contextRefsNode);
            } catch (Exception e) {
                LOG.warnf("LedgerWriteService: could not serialize context_refs for message %d", message.id);
            }
        }

        String sourceEntity = null;
        final JsonNode sourceEntityNode = root.get("source_entity");
        if (sourceEntityNode != null && !sourceEntityNode.isNull()) {
            try {
                sourceEntity = objectMapper.writeValueAsString(sourceEntityNode);
            } catch (Exception e) {
                LOG.warnf("LedgerWriteService: could not serialize source_entity for message %d", message.id);
            }
        }

        // Determine sequence number
        final java.util.Optional<LedgerEntry> latest = repository.findLatestBySubjectId(ch.id);
        final int sequenceNumber = latest.map(e -> e.sequenceNumber + 1).orElse(1);

        // Build entry
        final AgentMessageLedgerEntry entry = new AgentMessageLedgerEntry();
        entry.subjectId = ch.id;
        entry.channelId = ch.id;
        entry.messageId = message.id;
        entry.toolName = toolName;
        entry.durationMs = durationMs;
        entry.tokenCount = tokenCount;
        entry.contextRefs = contextRefs;
        entry.sourceEntity = sourceEntity;
        entry.actorId = message.sender;
        entry.actorType = ActorType.AGENT;
        entry.entryType = LedgerEntryType.EVENT;
        entry.correlationId = message.correlationId;
        entry.occurredAt = message.createdAt.truncatedTo(ChronoUnit.MILLIS);
        entry.sequenceNumber = sequenceNumber;

        // Hash chain
        if (config.hashChain().enabled()) {
            final String previousHash = latest.map(e -> e.digest).orElse(null);
            entry.previousHash = previousHash;
            entry.digest = LedgerHashChain.compute(previousHash, entry);
        }

        repository.save(entry);
    }
}
