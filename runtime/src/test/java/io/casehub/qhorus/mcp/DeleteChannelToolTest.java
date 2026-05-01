package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.DeleteChannelResult;
import io.casehub.qhorus.runtime.message.MessageService;
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

        DeleteChannelResult result = QuarkusTransaction.requiringNew().call(() -> tools.deleteChannel(name, false, null));

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
                () -> QuarkusTransaction.requiringNew().run(() -> tools.deleteChannel(name, false, null)));
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

        DeleteChannelResult result = QuarkusTransaction.requiringNew().call(() -> tools.deleteChannel(name, true, null));

        assertEquals(2L, result.messagesDeleted());
        assertEquals("deleted", result.status());
    }

    @Test
    void deleteChannel_notFound_throwsIllegalArgument() {
        assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew().run(() -> tools.deleteChannel("no-such-" + System.nanoTime(), false, null)));
    }

    @Test
    void deleteChannel_afterDeletion_channelNoLongerListed() {
        String name = "del-tool-gone-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "Test", ChannelSemantic.APPEND, null));
        QuarkusTransaction.requiringNew().run(() -> tools.deleteChannel(name, false, null));

        QuarkusTransaction.requiringNew().run(() -> assertTrue(channelService.findByName(name).isEmpty()));
    }

    // =========================================================================
    // Admin guard — caller_instance_id + admin_instances check (#127)
    // =========================================================================

    @Test
    void deleteChannel_noAdminList_anyCallerAllowed() {
        String name = "del-admin-open-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(name, "No admin list", ChannelSemantic.APPEND, null));

        // No admin_instances — any caller (or null caller) can delete
        DeleteChannelResult result = QuarkusTransaction.requiringNew()
                .call(() -> tools.deleteChannel(name, false, null));
        assertEquals("deleted", result.status());
    }

    @Test
    void deleteChannel_withAdminList_authorizedCallerAllowed() {
        String name = "del-admin-ok-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            tools.createChannel(name, "Admin-guarded", null, null, null, null, null, null, null);
            tools.setChannelAdmins(name, "admin-agent");
        });

        DeleteChannelResult result = QuarkusTransaction.requiringNew()
                .call(() -> tools.deleteChannel(name, false, "admin-agent"));
        assertEquals("deleted", result.status());
    }

    @Test
    void deleteChannel_withAdminList_unauthorizedCallerRejected() {
        String name = "del-admin-reject-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            tools.createChannel(name, "Admin-guarded", null, null, null, null, null, null, null);
            tools.setChannelAdmins(name, "admin-agent");
        });

        Exception ex = assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew()
                        .run(() -> tools.deleteChannel(name, false, "rogue-agent")));
        assertTrue(ex.getMessage().contains("rogue-agent"),
                "Error should name the rejected caller: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("delete_channel"),
                "Error should name the tool: " + ex.getMessage());
    }

    @Test
    void deleteChannel_withAdminList_noCallerIdRejected() {
        String name = "del-admin-nocaller-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> {
            tools.createChannel(name, "Admin-guarded", null, null, null, null, null, null, null);
            tools.setChannelAdmins(name, "admin-agent");
        });

        Exception ex = assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew()
                        .run(() -> tools.deleteChannel(name, false, null)));
        assertTrue(ex.getMessage().contains("caller_instance_id"),
                "Error should indicate caller_instance_id is required: " + ex.getMessage());
    }
}
