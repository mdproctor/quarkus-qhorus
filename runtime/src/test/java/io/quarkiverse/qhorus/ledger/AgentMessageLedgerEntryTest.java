package io.quarkiverse.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.qhorus.runtime.ledger.AgentMessageLedgerEntry;

/**
 * Pure unit tests for {@link AgentMessageLedgerEntry} — no Quarkus runtime.
 *
 * <p>
 * Verifies the structural contract of the subclass: field accessibility, subtype
 * relationship, and discriminator value. These tests fail to compile until the
 * production class is created (RED phase for issue #51).
 *
 * <p>
 * Refs #51, Epic #50.
 */
class AgentMessageLedgerEntryTest {

    @Test
    void isSubtypeOfLedgerEntry() {
        final AgentMessageLedgerEntry entry = new AgentMessageLedgerEntry();

        assertInstanceOf(LedgerEntry.class, entry);
    }

    @Test
    void requiredFields_areAccessible() {
        final AgentMessageLedgerEntry entry = new AgentMessageLedgerEntry();
        final UUID channelId = UUID.randomUUID();

        entry.subjectId = channelId; // inherited from LedgerEntry
        entry.channelId = channelId;
        entry.messageId = 42L;
        entry.toolName = "read_file";
        entry.durationMs = 137L;

        assertEquals(channelId, entry.subjectId);
        assertEquals(channelId, entry.channelId);
        assertEquals(42L, entry.messageId);
        assertEquals("read_file", entry.toolName);
        assertEquals(137L, entry.durationMs);
    }

    @Test
    void optionalFields_defaultToNull() {
        final AgentMessageLedgerEntry entry = new AgentMessageLedgerEntry();

        assertNull(entry.tokenCount);
        assertNull(entry.contextRefs);
        assertNull(entry.sourceEntity);
    }

    @Test
    void optionalFields_canBeSet() {
        final AgentMessageLedgerEntry entry = new AgentMessageLedgerEntry();

        entry.tokenCount = 1200L;
        entry.contextRefs = "[\"msg-17\",\"artefact-abc\"]";
        entry.sourceEntity = "{\"id\":\"case-1\",\"type\":\"CaseHub:Case\",\"system\":\"casehub\"}";

        assertEquals(1200L, entry.tokenCount);
        assertNotNull(entry.contextRefs);
        assertNotNull(entry.sourceEntity);
    }

    @Test
    void baseFields_inheritedFromLedgerEntry() {
        final AgentMessageLedgerEntry entry = new AgentMessageLedgerEntry();
        entry.actorId = "agent-1";
        entry.sequenceNumber = 3;

        assertEquals("agent-1", entry.actorId);
        assertEquals(3, entry.sequenceNumber);
        // correlationId moved to ObservabilitySupplement in ledger supplement refactoring
    }
}
