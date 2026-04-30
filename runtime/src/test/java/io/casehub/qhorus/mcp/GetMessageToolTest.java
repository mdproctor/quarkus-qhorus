package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageSummary;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GetMessageToolTest {

    @Inject
    QhorusMcpTools tools;
    @Inject
    ChannelService channelService;
    @Inject
    MessageService messageService;

    @Test
    void getMessage_knownId_returnsCorrectSummary() {
        String channelName = "msg-get-ch-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> channelService.create(channelName, "Test", ChannelSemantic.APPEND, null));

        Long[] msgId = new Long[1];
        QuarkusTransaction.requiringNew().run(() -> {
            var ch = channelService.findByName(channelName).orElseThrow();
            Message msg = messageService.send(ch.id, "agent-a", MessageType.STATUS,
                    "hello world", null, null);
            msgId[0] = msg.id;
        });

        MessageSummary summary = QuarkusTransaction.requiringNew().call(() -> tools.getMessage(msgId[0]));

        assertEquals(msgId[0], summary.messageId());
        assertEquals("agent-a", summary.sender());
        assertEquals("STATUS", summary.messageType());
        assertEquals("hello world", summary.content());
    }

    @Test
    void getMessage_unknownId_throwsWithNotFoundMessage() {
        Exception ex = assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew().run(() -> tools.getMessage(Long.MAX_VALUE)));
        assertTrue(ex.getMessage().toLowerCase().contains("not found"),
                "Error should say 'not found': " + ex.getMessage());
    }
}
