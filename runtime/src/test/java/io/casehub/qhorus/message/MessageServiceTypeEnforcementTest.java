package io.casehub.qhorus.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessageServiceTypeEnforcementTest {

    @Inject
    MessageService messageService;

    @Inject
    ChannelService channelService;

    /** Helper: create a channel and return its ID, committed. */
    private UUID createChannel(String name, String allowedTypes) {
        UUID[] id = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            var ch = channelService.create(name, "Test channel", ChannelSemantic.APPEND,
                    null, null, null, null, null, allowedTypes);
            id[0] = ch.id;
        });
        return id[0];
    }

    /** Helper: create an open channel (no type constraint) and return its ID, committed. */
    private UUID createOpenChannel(String name) {
        UUID[] id = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> {
            var ch = channelService.create(name, "Open channel", ChannelSemantic.APPEND, null);
            id[0] = ch.id;
        });
        return id[0];
    }

    @Test
    void serverSide_rejectsDisallowedType_bypassingMcpTool() {
        String name = "server-enforce-" + System.nanoTime();
        UUID channelId = createChannel(name, "EVENT");

        assertThrows(MessageTypeViolationException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.send(channelId, "agent-1", MessageType.QUERY,
                        "text", null, null)));
    }

    @Test
    void serverSide_permitsAllowedType() {
        String name = "server-allow-" + System.nanoTime();
        UUID channelId = createChannel(name, "EVENT");

        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.send(channelId, "agent-1", MessageType.EVENT,
                        "{\"tool\":\"read\"}", null, null)));
    }

    @Test
    void serverSide_permitsAllTypes_whenConstraintIsNull() {
        String name = "server-open-" + System.nanoTime();
        UUID channelId = createOpenChannel(name);

        for (MessageType t : MessageType.values()) {
            final MessageType type = t;
            String corrId = type.requiresCorrelationId() ? UUID.randomUUID().toString() : null;
            String target = type == MessageType.HANDOFF ? "instance:other-001" : null;
            assertDoesNotThrow(
                    () -> QuarkusTransaction.requiringNew()
                            .run(() -> messageService.send(channelId, "agent-1", type, "content", corrId, null, null, target)),
                    "Expected " + t + " to be permitted on open channel");
        }
    }

    @Test
    void serverSide_violation_messageContainsChannelAndType() {
        String name = "server-msg-" + System.nanoTime();
        UUID channelId = createChannel(name, "QUERY,COMMAND");

        MessageTypeViolationException ex = assertThrows(MessageTypeViolationException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.send(channelId, "agent-1", MessageType.EVENT,
                        "{}", null, null)));
        assertTrue(ex.getMessage().contains(name), "Expected channel name in error: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("EVENT"), "Expected type in error: " + ex.getMessage());
    }

    @Test
    void serverSide_multiTypeConstraint_permitsAllListed() {
        String name = "server-multi-" + System.nanoTime();
        UUID channelId = createChannel(name, "QUERY,COMMAND");

        String corrId = UUID.randomUUID().toString();
        assertDoesNotThrow(
                () -> QuarkusTransaction.requiringNew().run(() -> messageService.send(channelId, "agent-1", MessageType.COMMAND,
                        "do it", corrId, null)));
    }
}
