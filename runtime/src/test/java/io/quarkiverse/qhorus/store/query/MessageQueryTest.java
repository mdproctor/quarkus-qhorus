package io.quarkiverse.qhorus.store.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;

class MessageQueryTest {

    private static final UUID CHANNEL_A = UUID.randomUUID();
    private static final UUID CHANNEL_B = UUID.randomUUID();

    private Message message(UUID channelId, MessageType type) {
        Message m = new Message();
        m.channelId = channelId;
        m.messageType = type;
        m.sender = "agent-1";
        return m;
    }

    @Test
    void forChannel_matchesSameChannel() {
        Message m = message(CHANNEL_A, MessageType.COMMAND);
        assertTrue(MessageQuery.forChannel(CHANNEL_A).matches(m));
    }

    @Test
    void forChannel_doesNotMatchDifferentChannel() {
        Message m = message(CHANNEL_B, MessageType.COMMAND);
        assertFalse(MessageQuery.forChannel(CHANNEL_A).matches(m));
    }

    @Test
    void poll_filtersById() {
        Message m = message(CHANNEL_A, MessageType.COMMAND);
        m.id = 10L;

        assertTrue(MessageQuery.poll(CHANNEL_A, 5L, 20).matches(m));
        assertFalse(MessageQuery.poll(CHANNEL_A, 10L, 20).matches(m));
        assertFalse(MessageQuery.poll(CHANNEL_A, 15L, 20).matches(m));
    }

    @Test
    void excludeTypes_filtersOutMatchingType() {
        Message m = message(CHANNEL_A, MessageType.EVENT);

        MessageQuery excludeEvents = MessageQuery.builder()
                .channelId(CHANNEL_A)
                .excludeTypes(List.of(MessageType.EVENT))
                .build();
        assertFalse(excludeEvents.matches(m));

        MessageQuery excludeRequests = MessageQuery.builder()
                .channelId(CHANNEL_A)
                .excludeTypes(List.of(MessageType.COMMAND))
                .build();
        assertTrue(excludeRequests.matches(m));
    }

    @Test
    void sender_filtersCorrectly() {
        Message m = message(CHANNEL_A, MessageType.COMMAND);
        m.sender = "agent-1";

        assertTrue(MessageQuery.builder().sender("agent-1").build().matches(m));
        assertFalse(MessageQuery.builder().sender("agent-2").build().matches(m));
    }

    @Test
    void target_filtersCorrectly() {
        Message m = message(CHANNEL_A, MessageType.COMMAND);
        m.target = "instance:abc";

        assertTrue(MessageQuery.builder().target("instance:abc").build().matches(m));
        assertFalse(MessageQuery.builder().target("instance:xyz").build().matches(m));
    }

    @Test
    void replies_matchesOnInReplyTo() {
        Message m = message(CHANNEL_A, MessageType.RESPONSE);
        m.inReplyTo = 42L;

        assertTrue(MessageQuery.replies(CHANNEL_A, 42L).matches(m));
        assertFalse(MessageQuery.replies(CHANNEL_A, 99L).matches(m));
    }

    @Test
    void contentPattern_caseInsensitiveSubstring() {
        Message m = message(CHANNEL_A, MessageType.EVENT);
        m.content = "Task completed successfully";

        assertTrue(MessageQuery.builder().contentPattern("COMPLETED").build().matches(m));
        assertFalse(MessageQuery.builder().contentPattern("failed").build().matches(m));
    }

    @Test
    void contentPattern_doesNotMatchNullContent() {
        Message m = message(CHANNEL_A, MessageType.COMMAND);
        m.content = null;

        assertFalse(MessageQuery.builder().contentPattern("anything").build().matches(m));
    }

    @Test
    void builder_combinesMultiplePredicates() {
        Message m = message(CHANNEL_A, MessageType.COMMAND);
        m.sender = "orchestrator";
        m.id = 20L;

        MessageQuery q = MessageQuery.builder()
                .channelId(CHANNEL_A)
                .sender("orchestrator")
                .afterId(10L)
                .excludeTypes(List.of(MessageType.EVENT))
                .build();

        assertTrue(q.matches(m));

        // change one dimension to fail
        m.sender = "other-agent";
        assertFalse(q.matches(m));
    }
}
