package io.quarkiverse.qhorus.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.testing.InMemoryChannelStore;
import io.quarkiverse.qhorus.testing.InMemoryMessageStore;

/**
 * Happy path example test using in-memory stores — no database, no Quarkus boot.
 *
 * This demonstrates the recommended pattern for consumers of quarkus-qhorus
 * who want fast unit tests. Add quarkus-qhorus-testing at test scope and
 * wire up InMemory*Store directly (or let CDI do it with @Alternative @Priority(1)).
 */
class StoreUsageExampleTest {

    private InMemoryChannelStore channelStore;
    private InMemoryMessageStore messageStore;
    private StoreUsageExample example;

    @BeforeEach
    void setUp() {
        channelStore = new InMemoryChannelStore();
        messageStore = new InMemoryMessageStore();
        example = new StoreUsageExample();
        example.channelStore = channelStore;
        example.messageStore = messageStore;
    }

    @Test
    void happyPath_createChannelAndPostMessage() {
        Channel ch = example.createChannel("coordination", ChannelSemantic.APPEND);
        assertNotNull(ch.id);
        assertEquals("coordination", ch.name);

        Optional<Message> msg = example.postMessage("coordination", "agent-1", "hello");
        assertTrue(msg.isPresent());
        assertEquals("hello", msg.get().content);
        assertEquals("agent-1", msg.get().sender);
        assertEquals(MessageType.COMMAND, msg.get().messageType);
    }

    @Test
    void pollMessages_excludesEventMessages() {
        Channel ch = example.createChannel("events-test", ChannelSemantic.APPEND);
        example.postMessage("events-test", "agent-1", "real message");

        // Post an EVENT directly via store (bypasses example method)
        Message evt = new Message();
        evt.channelId = ch.id;
        evt.sender = "agent-1";
        evt.messageType = MessageType.EVENT;
        evt.content = "{\"tool_name\":\"foo\",\"duration_ms\":42}";
        messageStore.put(evt);

        List<Message> polled = example.pollMessages(ch.id, null);
        assertEquals(1, polled.size());
        assertEquals(MessageType.COMMAND, polled.get(0).messageType);
    }

    @Test
    void postMessage_returnsEmpty_whenChannelNotFound() {
        Optional<Message> result = example.postMessage("no-such-channel", "agent-1", "hello");
        assertTrue(result.isEmpty());
    }

    @Test
    void pausedChannels_returnsOnlyPausedChannels() {
        Channel active = example.createChannel("active-ch", ChannelSemantic.APPEND);
        active.paused = false;
        channelStore.put(active);

        Channel paused = example.createChannel("paused-ch", ChannelSemantic.APPEND);
        paused.paused = true;
        channelStore.put(paused);

        List<Channel> results = example.pausedChannels();
        assertTrue(results.stream().anyMatch(c -> "paused-ch".equals(c.name)));
        assertTrue(results.stream().noneMatch(c -> "active-ch".equals(c.name)));
    }
}
