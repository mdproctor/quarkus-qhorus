package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.runtime.model.LedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;

class MessageLedgerEntryTest {

    @Test
    void isSubtypeOfLedgerEntry() {
        assertInstanceOf(LedgerEntry.class, new MessageLedgerEntry());
    }

    @Test
    void commonFields_areAccessible() {
        MessageLedgerEntry e = new MessageLedgerEntry();
        UUID channelId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID(); // distinct from channelId
        e.channelId = channelId;
        e.subjectId = subjectId;
        e.messageId = 42L;
        e.messageType = "COMMAND";
        e.actorId = "agent-1";
        e.sequenceNumber = 1;
        assertEquals(channelId, e.channelId);
        assertEquals(subjectId, e.subjectId);
        assertEquals(42L, e.messageId);
        assertEquals("COMMAND", e.messageType);
        assertEquals("agent-1", e.actorId);
        assertEquals(1, e.sequenceNumber);
    }

    @Test
    void normativeFields_defaultToNull() {
        MessageLedgerEntry e = new MessageLedgerEntry();
        assertNull(e.target);
        assertNull(e.content);
        assertNull(e.correlationId);
        assertNull(e.commitmentId);
    }

    @Test
    void telemetryFields_defaultToNull() {
        MessageLedgerEntry e = new MessageLedgerEntry();
        assertNull(e.toolName);
        assertNull(e.durationMs);
        assertNull(e.tokenCount);
        assertNull(e.contextRefs);
        assertNull(e.sourceEntity);
    }

    @Test
    void allFields_canBeSet() {
        MessageLedgerEntry e = new MessageLedgerEntry();
        UUID commitmentId = UUID.randomUUID();
        e.target = "instance:abc";
        e.content = "Please generate the report";
        e.correlationId = "corr-1";
        e.commitmentId = commitmentId;
        e.toolName = "read_file";
        e.durationMs = 42L;
        e.tokenCount = 1200L;
        e.contextRefs = "[\"msg-1\"]";
        e.sourceEntity = "{\"id\":\"case-1\"}";
        assertEquals("instance:abc", e.target);
        assertEquals("Please generate the report", e.content);
        assertEquals("corr-1", e.correlationId);
        assertEquals(commitmentId, e.commitmentId);
        assertEquals("read_file", e.toolName);
        assertEquals(42L, e.durationMs);
        assertEquals(1200L, e.tokenCount);
        assertEquals("[\"msg-1\"]", e.contextRefs);
        assertEquals("{\"id\":\"case-1\"}", e.sourceEntity);
    }
}
