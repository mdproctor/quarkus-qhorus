package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link MessageLedgerEntryRepository}.
 * Refs #101 — Epic #99.
 */
@QuarkusTest
@TestTransaction
class MessageLedgerEntryRepositoryTest {

    @Inject
    MessageLedgerEntryRepository repo;

    @Test
    void save_andFindByChannelId_returnsSavedEntry() {
        UUID channelId = UUID.randomUUID();
        repo.save(entry(channelId, 1L, "COMMAND", 1));
        List<MessageLedgerEntry> found = repo.findByChannelId(channelId);
        assertEquals(1, found.size());
        assertEquals("COMMAND", found.get(0).messageType);
        assertEquals(channelId, found.get(0).channelId);
    }

    @Test
    void findByChannelId_unknownChannel_returnsEmpty() {
        assertTrue(repo.findByChannelId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByChannelId_orderedBySequenceNumber() {
        UUID channelId = UUID.randomUUID();
        repo.save(entry(channelId, 3L, "DONE", 3));
        repo.save(entry(channelId, 1L, "COMMAND", 1));
        repo.save(entry(channelId, 2L, "STATUS", 2));
        List<MessageLedgerEntry> found = repo.findByChannelId(channelId);
        assertEquals(3, found.size());
        assertEquals(1, found.get(0).sequenceNumber);
        assertEquals(2, found.get(1).sequenceNumber);
        assertEquals(3, found.get(2).sequenceNumber);
    }

    @Test
    void findLatestBySubjectId_returnsHighestSequenceEntry() {
        UUID channelId = UUID.randomUUID();
        repo.save(entry(channelId, 1L, "COMMAND", 1));
        repo.save(entry(channelId, 2L, "DONE", 2));
        var latest = repo.findLatestBySubjectId(channelId);
        assertTrue(latest.isPresent());
        assertEquals(2, latest.get().sequenceNumber);
    }

    @Test
    void findLatestBySubjectId_emptyChannel_returnsEmpty() {
        assertTrue(repo.findLatestBySubjectId(UUID.randomUUID()).isEmpty());
    }

    @Test
    void listEntries_noFilter_returnsAllTypes() {
        UUID channelId = UUID.randomUUID();
        repo.save(entry(channelId, 1L, "COMMAND", 1));
        repo.save(entry(channelId, 2L, "STATUS", 2));
        repo.save(entry(channelId, 3L, "DONE", 3));
        repo.save(entry(channelId, 4L, "EVENT", 4));
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, null, null, null, 20);
        assertEquals(4, entries.size());
    }

    @Test
    void listEntries_typeFilter_returnsOnlyMatchingTypes() {
        UUID channelId = UUID.randomUUID();
        repo.save(entry(channelId, 1L, "COMMAND", 1));
        repo.save(entry(channelId, 2L, "DONE", 2));
        repo.save(entry(channelId, 3L, "EVENT", 3));
        repo.save(entry(channelId, 4L, "DECLINE", 4));
        List<MessageLedgerEntry> entries = repo.listEntries(
                channelId, Set.of("COMMAND", "DONE"), null, null, null, 20);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> Set.of("COMMAND", "DONE").contains(e.messageType)));
    }

    @Test
    void listEntries_typeFilter_noMatch_returnsEmpty() {
        UUID channelId = UUID.randomUUID();
        repo.save(entry(channelId, 1L, "COMMAND", 1));
        List<MessageLedgerEntry> entries = repo.listEntries(
                channelId, Set.of("DONE"), null, null, null, 20);
        assertTrue(entries.isEmpty());
    }

    @Test
    void listEntries_afterSequence_returnsOnlyLaterEntries() {
        UUID channelId = UUID.randomUUID();
        repo.save(entry(channelId, 1L, "COMMAND", 1));
        repo.save(entry(channelId, 2L, "STATUS", 2));
        repo.save(entry(channelId, 3L, "DONE", 3));
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, 1L, null, null, 20);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> e.sequenceNumber > 1));
    }

    @Test
    void listEntries_agentFilter_returnsOnlyMatchingAgent() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry e1 = entry(channelId, 1L, "COMMAND", 1);
        e1.actorId = "agent-a";
        MessageLedgerEntry e2 = entry(channelId, 2L, "DONE", 2);
        e2.actorId = "agent-b";
        repo.save(e1);
        repo.save(e2);
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, null, "agent-a", null, 20);
        assertEquals(1, entries.size());
        assertEquals("agent-a", entries.get(0).actorId);
    }

    @Test
    void listEntries_sinceFilter_excludesOlderEntries() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry old = entry(channelId, 1L, "COMMAND", 1);
        old.occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        repo.save(old);
        MessageLedgerEntry recent = entry(channelId, 2L, "DONE", 2);
        recent.occurredAt = Instant.parse("2026-06-01T00:00:00Z");
        repo.save(recent);
        List<MessageLedgerEntry> entries = repo.listEntries(
                channelId, null, null, null, Instant.parse("2026-03-01T00:00:00Z"), 20);
        assertEquals(1, entries.size());
        assertEquals("DONE", entries.get(0).messageType);
    }

    @Test
    void listEntries_limit_capsResults() {
        UUID channelId = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            repo.save(entry(channelId, (long) i, "EVENT", i));
        }
        List<MessageLedgerEntry> entries = repo.listEntries(channelId, null, null, null, null, 3);
        assertEquals(3, entries.size());
    }

    @Test
    void findLatestByCorrelationId_returnsCommandEntry() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry cmd = entry(channelId, 1L, "COMMAND", 1);
        cmd.correlationId = "corr-1";
        repo.save(cmd);
        Optional<MessageLedgerEntry> found = repo.findLatestByCorrelationId(channelId, "corr-1");
        assertTrue(found.isPresent());
        assertEquals("COMMAND", found.get().messageType);
    }

    @Test
    void findLatestByCorrelationId_withHandoff_returnsLatestHandoff() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry cmd = entry(channelId, 1L, "COMMAND", 1);
        cmd.correlationId = "corr-2";
        repo.save(cmd);
        MessageLedgerEntry handoff = entry(channelId, 2L, "HANDOFF", 2);
        handoff.correlationId = "corr-2";
        repo.save(handoff);
        Optional<MessageLedgerEntry> found = repo.findLatestByCorrelationId(channelId, "corr-2");
        assertTrue(found.isPresent());
        assertEquals("HANDOFF", found.get().messageType);
    }

    @Test
    void findLatestByCorrelationId_doneEntryIgnored() {
        UUID channelId = UUID.randomUUID();
        MessageLedgerEntry done = entry(channelId, 1L, "DONE", 1);
        done.correlationId = "corr-3";
        repo.save(done);
        Optional<MessageLedgerEntry> found = repo.findLatestByCorrelationId(channelId, "corr-3");
        assertTrue(found.isEmpty());
    }

    @Test
    void findLatestByCorrelationId_unknownCorrelationId_returnsEmpty() {
        UUID channelId = UUID.randomUUID();
        assertTrue(repo.findLatestByCorrelationId(channelId, "no-such-corr").isEmpty());
    }

    private MessageLedgerEntry entry(UUID channelId, long messageId, String type, int seq) {
        MessageLedgerEntry e = new MessageLedgerEntry();
        e.channelId = channelId;
        e.subjectId = channelId;
        e.messageId = messageId;
        e.messageType = type;
        e.sequenceNumber = seq;
        e.actorId = "agent-1";
        e.actorType = ActorType.AGENT;
        e.entryType = LedgerEntryType.EVENT;
        e.occurredAt = Instant.now();
        return e;
    }
}
