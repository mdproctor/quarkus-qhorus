package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpToolsBase.DeleteChannelResult;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DeleteChannelToolTest {

    @Inject
    QhorusMcpTools tools;
    @Inject
    ChannelService channelService;
    @Inject
    MessageService messageService;

    @Test
    void deleteChannel_emptyChannel_returnsSuccessWithZeroMessages() {
        String name = "del-tool-empty-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));

        DeleteChannelResult result = QuarkusTransaction.requiringNew().call(() -> tools.deleteChannel(name, false));

        assertEquals(name, result.channelName());
        assertEquals(0L, result.messagesDeleted());
        assertEquals("deleted", result.status());
    }

    @Test
    void deleteChannel_withMessages_forceFalse_throwsWithCount() {
        String name = "del-tool-guard-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));

        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id);
        QuarkusTransaction.requiringNew()
                .run(() -> messageService.send(chId[0], "agent-a", MessageType.STATUS, "hi", null, null));

        Exception ex = assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew().run(() -> tools.deleteChannel(name, false)));
        assertTrue(ex.getMessage().contains("1"),
                "Error message should include message count: " + ex.getMessage());
    }

    @Test
    void deleteChannel_withMessages_forceTrue_deletesAllAndReturnsCount() {
        String name = "del-tool-force-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));

        UUID[] chId = new UUID[1];
        QuarkusTransaction.requiringNew().run(() -> chId[0] = channelService.findByName(name).orElseThrow().id);
        QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(chId[0], "agent-a", MessageType.STATUS, "one", null, null);
            messageService.send(chId[0], "agent-b", MessageType.STATUS, "two", null, null);
        });

        DeleteChannelResult result = QuarkusTransaction.requiringNew().call(() -> tools.deleteChannel(name, true));

        assertEquals(2L, result.messagesDeleted());
        assertEquals("deleted", result.status());
    }

    @Test
    void deleteChannel_notFound_throwsIllegalArgument() {
        assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew().run(() -> tools.deleteChannel("no-such-" + System.nanoTime(), false)));
    }

    @Test
    void deleteChannel_afterDeletion_channelNoLongerListed() {
        String name = "del-tool-gone-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));
        QuarkusTransaction.requiringNew().run(() -> tools.deleteChannel(name, false));

        QuarkusTransaction.requiringNew().run(() -> assertTrue(channelService.findByName(name).isEmpty()));
    }
}
