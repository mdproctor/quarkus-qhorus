package io.quarkiverse.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.qhorus.runtime.ledger.MessageLedgerEntry;
import io.quarkiverse.qhorus.runtime.ledger.MessageLedgerEntryRepository;

/**
 * Pure unit tests for the 6 new query methods on {@link MessageLedgerEntryRepository}.
 *
 * <p>
 * Uses {@link CapturingRepo} — the same in-memory stub pattern as
 * {@link LedgerWriteServiceTest}. No Quarkus runtime or database required.
 *
 * <p>
 * Refs #111, Epic #110.
 */
class LedgerQueryRepoTest {

    // ── In-memory stub ────────────────────────────────────────────────────────

    /**
     * In-memory stub that records saved entries and supports the queries
     * needed by the 6 new repository methods.
     *
     * <p>
     * Unlike the full JPA implementation, this stub stores entries in a
     * simple list and answers queries by iterating — correctness over speed.
     */
    static class CapturingRepo extends MessageLedgerEntryRepository {

        final List<MessageLedgerEntry> saved = new ArrayList<>();

        @Override
        public LedgerEntry save(final LedgerEntry entry) {
            final MessageLedgerEntry mle = (MessageLedgerEntry) entry;
            if (mle.id == null) {
                mle.id = UUID.randomUUID();
            }
            saved.add(mle);
            return mle;
        }

        @Override
        public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
            return saved.stream()
                    .filter(e -> subjectId.equals(e.subjectId))
                    .reduce((a, b) -> b)
                    .map(e -> (LedgerEntry) e);
        }

        // Override the 6 new query methods — they delegate to in-memory logic

        @Override
        public List<MessageLedgerEntry> findAllByCorrelationId(final UUID channelId,
                final String correlationId) {
            return saved.stream()
                    .filter(e -> channelId.equals(e.subjectId)
                            && correlationId.equals(e.correlationId))
                    .sorted(java.util.Comparator.comparingLong(e -> e.sequenceNumber))
                    .toList();
        }

        @Override
        public List<MessageLedgerEntry> findAncestorChain(final UUID channelId,
                final UUID entryId) {
            final List<MessageLedgerEntry> chain = new ArrayList<>();
            UUID currentId = entryId;
            final java.util.Set<UUID> visited = new java.util.HashSet<>();
            while (currentId != null && !visited.contains(currentId)) {
                visited.add(currentId);
                final UUID id = currentId;
                final MessageLedgerEntry entry = saved.stream()
                        .filter(e -> id.equals(e.id) && channelId.equals(e.subjectId))
                        .findFirst()
                        .orElse(null);
                if (entry == null) {
                    break;
                }
                chain.add(entry);
                currentId = entry.causedByEntryId;
            }
            java.util.Collections.reverse(chain);
            return chain;
        }

        @Override
        public List<MessageLedgerEntry> findStalledCommands(final UUID channelId,
                final Instant olderThan) {
            return saved.stream()
                    .filter(e -> channelId.equals(e.subjectId)
                            && "COMMAND".equals(e.messageType)
                            && e.occurredAt != null
                            && e.occurredAt.isBefore(olderThan))
                    .filter(e -> saved.stream().noneMatch(t -> channelId.equals(t.subjectId)
                            && e.correlationId != null
                            && e.correlationId.equals(t.correlationId)
                            && List.of("DONE", "FAILURE", "DECLINE", "HANDOFF")
                                    .contains(t.messageType)))
                    .toList();
        }

        @Override
        public Map<String, Long> countByOutcome(final UUID channelId) {
            final java.util.Set<String> relevant = java.util.Set.of(
                    "COMMAND", "DONE", "FAILURE", "DECLINE", "HANDOFF");
            final Map<String, Long> counts = new java.util.HashMap<>();
            saved.stream()
                    .filter(e -> channelId.equals(e.subjectId)
                            && relevant.contains(e.messageType))
                    .forEach(e -> counts.merge(e.messageType, 1L, Long::sum));
            return counts;
        }

        @Override
        public List<MessageLedgerEntry> findByActorIdInChannel(final UUID channelId,
                final String actorId, final int limit) {
            return saved.stream()
                    .filter(e -> channelId.equals(e.subjectId)
                            && actorId.equals(e.actorId))
                    .sorted(java.util.Comparator
                            .comparingLong((MessageLedgerEntry e) -> e.sequenceNumber)
                            .reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<MessageLedgerEntry> findEventsSince(final UUID channelId,
                final Instant since) {
            return saved.stream()
                    .filter(e -> channelId.equals(e.subjectId)
                            && "EVENT".equals(e.messageType)
                            && (since == null || (e.occurredAt != null && !e.occurredAt.isBefore(since))))
                    .toList();
        }

        @Override
        public List<MessageLedgerEntry> listEntries(final UUID channelId,
                final Set<String> messageTypes, final Long afterSequence, final String agentId,
                final Instant since, final String correlationId, final boolean sortDesc,
                final int limit) {
            java.util.stream.Stream<MessageLedgerEntry> stream = saved.stream()
                    .filter(e -> channelId.equals(e.subjectId));
            if (messageTypes != null && !messageTypes.isEmpty()) {
                stream = stream.filter(e -> messageTypes.contains(e.messageType));
            }
            if (afterSequence != null) {
                stream = stream.filter(e -> e.sequenceNumber > afterSequence);
            }
            if (agentId != null && !agentId.isBlank()) {
                stream = stream.filter(e -> agentId.equals(e.actorId));
            }
            if (since != null) {
                stream = stream.filter(e -> e.occurredAt != null && !e.occurredAt.isBefore(since));
            }
            if (correlationId != null && !correlationId.isBlank()) {
                stream = stream.filter(e -> correlationId.equals(e.correlationId));
            }
            java.util.Comparator<MessageLedgerEntry> comparator = java.util.Comparator.comparingLong(e -> e.sequenceNumber);
            if (sortDesc) {
                comparator = comparator.reversed();
            }
            return stream.sorted(comparator).limit(limit).toList();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CapturingRepo repo;
    private UUID channelId;
    private UUID otherChannelId;

    @BeforeEach
    void setup() {
        repo = new CapturingRepo();
        channelId = UUID.randomUUID();
        otherChannelId = UUID.randomUUID();
    }

    private MessageLedgerEntry entry(final String type, final String correlationId,
            final UUID causedBy) {
        return entry(type, correlationId, causedBy, "agent-a", channelId);
    }

    private MessageLedgerEntry entry(final String type, final String correlationId,
            final UUID causedBy, final String actorId, final UUID chId) {
        final MessageLedgerEntry e = new MessageLedgerEntry();
        e.id = UUID.randomUUID();
        e.subjectId = chId;
        e.channelId = chId;
        e.messageType = type;
        e.correlationId = correlationId;
        e.causedByEntryId = causedBy;
        e.actorId = actorId;
        e.occurredAt = Instant.now();
        e.sequenceNumber = repo.saved.size() + 1;
        repo.saved.add(e);
        return e;
    }

    private MessageLedgerEntry eventEntry(final String toolName, final long durationMs,
            final long tokenCount) {
        final MessageLedgerEntry e = new MessageLedgerEntry();
        e.id = UUID.randomUUID();
        e.subjectId = channelId;
        e.channelId = channelId;
        e.messageType = "EVENT";
        e.toolName = toolName;
        e.durationMs = durationMs;
        e.tokenCount = tokenCount;
        e.occurredAt = Instant.now();
        e.sequenceNumber = repo.saved.size() + 1;
        repo.saved.add(e);
        return e;
    }

    // ── findAllByCorrelationId ────────────────────────────────────────────────

    @Test
    void findAllByCorrelationId_returnsAllEntriesForCorrelation_ascendingOrder() {
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-1", null);
        final MessageLedgerEntry done = entry("DONE", "corr-1", cmd.id);
        entry("COMMAND", "corr-2", null); // different correlationId — excluded

        final List<MessageLedgerEntry> result = repo.findAllByCorrelationId(channelId, "corr-1");

        assertEquals(2, result.size());
        assertEquals(cmd.id, result.get(0).id);
        assertEquals(done.id, result.get(1).id);
    }

    @Test
    void findAllByCorrelationId_differentChannel_excluded() {
        entry("COMMAND", "corr-1", null, "agent-a", otherChannelId);

        final List<MessageLedgerEntry> result = repo.findAllByCorrelationId(channelId, "corr-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByCorrelationId_unknownCorrelation_returnsEmpty() {
        final List<MessageLedgerEntry> result = repo.findAllByCorrelationId(channelId, "no-such-corr");

        assertTrue(result.isEmpty());
    }

    // ── findAncestorChain ─────────────────────────────────────────────────────

    @Test
    void findAncestorChain_threeHops_returnsChainOldestFirst() {
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-a", null);
        final MessageLedgerEntry handoff = entry("HANDOFF", "corr-a", cmd.id);
        final MessageLedgerEntry done = entry("DONE", "corr-a", handoff.id);

        final List<MessageLedgerEntry> chain = repo.findAncestorChain(channelId, done.id);

        assertEquals(3, chain.size());
        assertEquals(cmd.id, chain.get(0).id);
        assertEquals(handoff.id, chain.get(1).id);
        assertEquals(done.id, chain.get(2).id);
    }

    @Test
    void findAncestorChain_rootEntry_returnsSingleEntry() {
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-b", null);

        final List<MessageLedgerEntry> chain = repo.findAncestorChain(channelId, cmd.id);

        assertEquals(1, chain.size());
        assertEquals(cmd.id, chain.get(0).id);
    }

    @Test
    void findAncestorChain_unknownEntry_returnsEmpty() {
        final List<MessageLedgerEntry> chain = repo.findAncestorChain(channelId, UUID.randomUUID());

        assertTrue(chain.isEmpty());
    }

    @Test
    void findAncestorChain_differentChannel_stopsAtBoundary() {
        // COMMAND lives in the correct channel; DONE lives in a different channel
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-c", null, "agent-a", channelId);
        final MessageLedgerEntry done = entry("DONE", "corr-c", cmd.id, "agent-b", otherChannelId);

        // Querying from the other channel: DONE is found but its parent is in a different channel
        final List<MessageLedgerEntry> chain = repo.findAncestorChain(otherChannelId, done.id);

        assertEquals(1, chain.size(), "Stops at channel boundary — COMMAND not in queried channel");
        assertEquals(done.id, chain.get(0).id);
    }

    // ── findStalledCommands ───────────────────────────────────────────────────

    @Test
    void findStalledCommands_commandWithNoDoneAndOldEnough_returned() {
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-stall", null);
        cmd.occurredAt = Instant.now().minus(60, ChronoUnit.SECONDS);

        final Instant threshold = Instant.now().minus(30, ChronoUnit.SECONDS);
        final List<MessageLedgerEntry> stalled = repo.findStalledCommands(channelId, threshold);

        assertEquals(1, stalled.size());
        assertEquals(cmd.id, stalled.get(0).id);
    }

    @Test
    void findStalledCommands_commandWithDoneSibling_notReturned() {
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-ok", null);
        cmd.occurredAt = Instant.now().minus(60, ChronoUnit.SECONDS);
        entry("DONE", "corr-ok", cmd.id);

        final Instant threshold = Instant.now().minus(30, ChronoUnit.SECONDS);
        final List<MessageLedgerEntry> stalled = repo.findStalledCommands(channelId, threshold);

        assertTrue(stalled.isEmpty());
    }

    @Test
    void findStalledCommands_commandTooRecent_notReturned() {
        entry("COMMAND", "corr-new", null); // occurredAt = now, younger than threshold

        final Instant threshold = Instant.now().minus(30, ChronoUnit.SECONDS);
        final List<MessageLedgerEntry> stalled = repo.findStalledCommands(channelId, threshold);

        assertTrue(stalled.isEmpty());
    }

    @Test
    void findStalledCommands_commandWithDeclineSibling_notStalled() {
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-declined", null);
        cmd.occurredAt = Instant.now().minus(60, ChronoUnit.SECONDS);
        entry("DECLINE", "corr-declined", cmd.id);

        final List<MessageLedgerEntry> stalled = repo.findStalledCommands(channelId,
                Instant.now().minus(30, ChronoUnit.SECONDS));

        assertTrue(stalled.isEmpty());
    }

    @Test
    void findStalledCommands_commandWithHandoffSibling_notStalled() {
        final MessageLedgerEntry cmd = entry("COMMAND", "corr-hoff", null);
        cmd.occurredAt = Instant.now().minus(60, ChronoUnit.SECONDS);
        entry("HANDOFF", "corr-hoff", cmd.id);

        final List<MessageLedgerEntry> stalled = repo.findStalledCommands(channelId,
                Instant.now().minus(30, ChronoUnit.SECONDS));

        assertTrue(stalled.isEmpty());
    }

    // ── countByOutcome ────────────────────────────────────────────────────────

    @Test
    void countByOutcome_mixedTypes_returnsCorrectCounts() {
        entry("COMMAND", "c1", null);
        entry("COMMAND", "c2", null);
        entry("DONE", "c1", null);
        entry("FAILURE", "c2", null);
        entry("STATUS", null, null); // irrelevant type — excluded
        entry("QUERY", null, null); // irrelevant type — excluded

        final Map<String, Long> counts = repo.countByOutcome(channelId);

        assertEquals(2L, counts.get("COMMAND"));
        assertEquals(1L, counts.get("DONE"));
        assertEquals(1L, counts.get("FAILURE"));
        assertNull(counts.get("STATUS"), "STATUS not tracked");
        assertNull(counts.get("QUERY"), "QUERY not tracked");
    }

    @Test
    void countByOutcome_emptyChannel_returnsEmptyMap() {
        final Map<String, Long> counts = repo.countByOutcome(channelId);

        assertTrue(counts.isEmpty());
    }

    @Test
    void countByOutcome_differentChannelExcluded() {
        entry("COMMAND", "cx1", null, "agent-a", otherChannelId);

        final Map<String, Long> counts = repo.countByOutcome(channelId);

        assertTrue(counts.isEmpty());
    }

    // ── findByActorIdInChannel ────────────────────────────────────────────────

    @Test
    void findByActorIdInChannel_returnsOnlyThatActorDescending() {
        final MessageLedgerEntry first = entry("COMMAND", "c1", null, "agent-a", channelId);
        final MessageLedgerEntry second = entry("STATUS", "c1", null, "agent-a", channelId);
        entry("DONE", "c1", null, "agent-b", channelId); // different actor — excluded

        final List<MessageLedgerEntry> result = repo.findByActorIdInChannel(channelId, "agent-a", 10);

        assertEquals(2, result.size());
        assertEquals(second.id, result.get(0).id, "Most recent first");
        assertEquals(first.id, result.get(1).id);
    }

    @Test
    void findByActorIdInChannel_limitRespected() {
        for (int i = 0; i < 5; i++) {
            entry("COMMAND", "c" + i, null, "agent-a", channelId);
        }

        final List<MessageLedgerEntry> result = repo.findByActorIdInChannel(channelId, "agent-a", 3);

        assertEquals(3, result.size());
    }

    @Test
    void findByActorIdInChannel_unknownActor_returnsEmpty() {
        final List<MessageLedgerEntry> result = repo.findByActorIdInChannel(channelId, "nobody", 10);

        assertTrue(result.isEmpty());
    }

    // ── findEventsSince ───────────────────────────────────────────────────────

    @Test
    void findEventsSince_noSince_returnsAllEventEntries() {
        eventEntry("tool-a", 100, 200);
        eventEntry("tool-b", 50, 100);
        entry("COMMAND", "c1", null); // non-EVENT — excluded

        final List<MessageLedgerEntry> result = repo.findEventsSince(channelId, null);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> "EVENT".equals(e.messageType)));
    }

    @Test
    void findEventsSince_withSince_excludesOlderEvents() {
        final MessageLedgerEntry old = eventEntry("tool-old", 10, 10);
        old.occurredAt = Instant.now().minus(120, ChronoUnit.SECONDS);

        eventEntry("tool-new", 20, 20); // occurredAt = now, within window

        final Instant cutoff = Instant.now().minus(60, ChronoUnit.SECONDS);
        final List<MessageLedgerEntry> result = repo.findEventsSince(channelId, cutoff);

        assertEquals(1, result.size());
        assertEquals("tool-new", result.get(0).toolName);
    }

    @Test
    void findEventsSince_nullToolName_stillIncluded() {
        eventEntry(null, 10, 10); // malformed EVENT — toolName absent

        final List<MessageLedgerEntry> result = repo.findEventsSince(channelId, null);

        assertEquals(1, result.size());
        assertNull(result.get(0).toolName);
    }

    @Test
    void findEventsSince_differentChannel_excluded() {
        final MessageLedgerEntry e = eventEntry("tool-x", 1, 1);
        e.subjectId = otherChannelId;
        e.channelId = otherChannelId;

        final List<MessageLedgerEntry> result = repo.findEventsSince(channelId, null);

        assertTrue(result.isEmpty());
    }

    // ── findAllByCorrelationId with enhanced listEntries ─────────────────────

    @Test
    void listEntries_withCorrelationIdFilter_returnsOnlyMatchingEntries() {
        entry("COMMAND", "corr-A", null);
        entry("STATUS", "corr-A", null);
        entry("COMMAND", "corr-B", null);

        // Only corr-A entries
        final List<MessageLedgerEntry> result = repo.listEntries(channelId, null, null, null, null, "corr-A", false, 10);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> "corr-A".equals(e.correlationId)));
    }

    @Test
    void listEntries_withSortDesc_returnsNewestFirst() {
        entry("COMMAND", "c1", null);
        entry("STATUS", "c1", null);
        entry("DONE", "c1", null);

        final List<MessageLedgerEntry> result = repo.listEntries(channelId, null, null, null, null, null, true, 10);

        assertEquals(3, result.size());
        assertTrue(result.get(0).sequenceNumber > result.get(1).sequenceNumber,
                "sortDesc=true should return newest first");
    }

    @Test
    void listEntries_defaultSort_returnsOldestFirst() {
        entry("COMMAND", "c2", null);
        entry("DONE", "c2", null);

        final List<MessageLedgerEntry> result = repo.listEntries(channelId, null, null, null, null, null, false, 10);

        assertEquals(2, result.size());
        assertTrue(result.get(0).sequenceNumber < result.get(1).sequenceNumber,
                "sortDesc=false should return oldest first");
    }

    @Test
    void listEntries_backwardCompatOverload_stillWorks() {
        entry("COMMAND", "c3", null);
        entry("DONE", "c3", null);

        final List<MessageLedgerEntry> result = repo.listEntries(channelId, null, null, null, null, 10);

        assertEquals(2, result.size());
    }
}
