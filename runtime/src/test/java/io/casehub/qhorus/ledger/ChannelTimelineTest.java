package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the {@code get_channel_timeline} MCP tool.
 *
 * <p>
 * Verifies that all message types — requests, responses, statuses, handoffs, done,
 * and EVENT telemetry — are returned interleaved in chronological order for a channel.
 *
 * <p>
 * RED-phase: will not compile until {@code get_channel_timeline} is added to
 * {@link QhorusMcpTools}.
 *
 * <p>
 * Refs #54, Epic #50.
 */
@QuarkusTest
@TestTransaction
class ChannelTimelineTest {

    @Inject
    QhorusMcpTools tools;

    private void setup(final String channel, final String... agents) {
        tools.createChannel(channel, "LAST_WRITE", null, null);
        for (final String agent : agents) {
            tools.registerInstance(channel, agent, null, null, null);
        }
    }

    private void sendEvent(final String channel, final String sender, final String tool) {
        tools.sendMessage(channel, sender, "event",
                String.format("{\"tool_name\":\"%s\",\"duration_ms\":10}", tool),
                null, null);
    }

    // =========================================================================
    // Happy path — all types interleaved
    // =========================================================================

    @Test
    void timeline_mixedTypes_returnedInOrder() {
        setup("ct-mixed-1", "agent-1");

        tools.sendMessage("ct-mixed-1", "agent-1", "command", "Do X", null, null);
        tools.sendMessage("ct-mixed-1", "agent-1", "status", "Working", null, null);
        sendEvent("ct-mixed-1", "agent-1", "read_file");

        final List<Map<String, Object>> timeline = tools.getChannelTimeline("ct-mixed-1", null, 50);

        assertEquals(3, timeline.size());
    }

    @Test
    void timeline_entriesHaveType() {
        setup("ct-type-1", "agent-1");

        tools.sendMessage("ct-type-1", "agent-1", "command", "Go", null, null);
        sendEvent("ct-type-1", "agent-1", "analyze");

        final List<Map<String, Object>> timeline = tools.getChannelTimeline("ct-type-1", null, 50);

        assertEquals(2, timeline.size());
        // Each entry has a type discriminator
        assertNotNull(timeline.get(0).get("type"));
        assertNotNull(timeline.get(1).get("type"));
    }

    @Test
    void timeline_regularMessages_haveMessageType() {
        setup("ct-msgtype-1", "agent-1");

        tools.sendMessage("ct-msgtype-1", "agent-1", "command", "Do something", null, null);

        final List<Map<String, Object>> timeline = tools.getChannelTimeline("ct-msgtype-1", null, 50);

        assertEquals(1, timeline.size());
        assertEquals("command", timeline.get(0).get("message_type"));
    }

    @Test
    void timeline_events_haveToolName() {
        setup("ct-event-1", "agent-1");

        sendEvent("ct-event-1", "agent-1", "write_file");

        final List<Map<String, Object>> timeline = tools.getChannelTimeline("ct-event-1", null, 50);

        assertEquals(1, timeline.size());
        assertEquals("write_file", timeline.get(0).get("tool_name"));
    }

    @Test
    void timeline_unknownChannel_throwsOrReturnsError() {
        assertThrows(ToolCallException.class,
                () -> tools.getChannelTimeline("no-such-channel", null, 50));
    }

    // =========================================================================
    // Ordering — chronological (id ascending)
    // =========================================================================

    @Test
    void timeline_chronologicalOrder() {
        setup("ct-order-1", "agent-1");

        tools.sendMessage("ct-order-1", "agent-1", "command", "step 1", null, null);
        sendEvent("ct-order-1", "agent-1", "analyze");
        tools.sendMessage("ct-order-1", "agent-1", "status", "done", null, null);

        final List<Map<String, Object>> timeline = tools.getChannelTimeline("ct-order-1", null, 50);

        assertEquals(3, timeline.size());
        assertEquals("command", timeline.get(0).get("message_type"));
        // Middle entry is an event (no message_type, has tool_name)
        assertEquals("analyze", timeline.get(1).get("tool_name"));
        assertEquals("status", timeline.get(2).get("message_type"));
    }

    // =========================================================================
    // Cursor pagination
    // =========================================================================

    @Test
    void timeline_limit_restrictsResults() {
        setup("ct-limit-1", "agent-1");

        for (int i = 0; i < 5; i++) {
            tools.sendMessage("ct-limit-1", "agent-1", "status", "step " + i,
                    null, null);
        }

        final List<Map<String, Object>> page = tools.getChannelTimeline("ct-limit-1", null, 3);

        assertEquals(3, page.size());
    }

    @Test
    void timeline_afterId_returnsNextPage() {
        setup("ct-cursor-1", "agent-1");

        tools.sendMessage("ct-cursor-1", "agent-1", "command", "first", null, null);
        tools.sendMessage("ct-cursor-1", "agent-1", "status", "second", null, null);
        tools.sendMessage("ct-cursor-1", "agent-1", "status", "third", null, null);

        final List<Map<String, Object>> page1 = tools.getChannelTimeline("ct-cursor-1", null, 2);
        assertEquals(2, page1.size());

        final Long afterId = (Long) page1.get(1).get("id");
        final List<Map<String, Object>> page2 = tools.getChannelTimeline("ct-cursor-1", afterId, 2);

        assertEquals(1, page2.size());
        assertEquals("third", page2.get(0).get("content"));
    }

    // =========================================================================
    // Empty channel
    // =========================================================================

    @Test
    void timeline_emptyChannel_returnsEmptyList() {
        setup("ct-empty-1", "agent-1");

        final List<Map<String, Object>> timeline = tools.getChannelTimeline("ct-empty-1", null, 50);

        assertTrue(timeline.isEmpty());
    }
}
