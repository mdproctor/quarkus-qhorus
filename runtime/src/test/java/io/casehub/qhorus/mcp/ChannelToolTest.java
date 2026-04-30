package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ChannelDetail;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChannelToolTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageService messageService;

    @Test
    @TestTransaction
    void createChannelDefaultsToAppendSemantic() {
        ChannelDetail ch = tools.createChannel("auth-review", "Auth code review thread", null, null);

        assertEquals("auth-review", ch.name());
        assertEquals("Auth code review thread", ch.description());
        assertEquals("APPEND", ch.semantic());
        assertNotNull(ch.channelId());
    }

    @Test
    @TestTransaction
    void createChannelWithExplicitSemantic() {
        ChannelDetail ch = tools.createChannel("findings", "Research findings", "COLLECT", null);

        assertEquals("COLLECT", ch.semantic());
    }

    @Test
    @TestTransaction
    void createChannelWithBarrierContributors() {
        ChannelDetail ch = tools.createChannel("sync-point", "All must contribute",
                "BARRIER", "alice,bob,carol");

        assertEquals("BARRIER", ch.semantic());
        assertEquals("alice,bob,carol", ch.barrierContributors());
    }

    @Test
    void createDuplicateChannelNameThrowsException() {
        String name = "dup-tool-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> tools.createChannel(name, "First", null, null));
        try {
            assertThrows(Exception.class,
                    () -> QuarkusTransaction.requiringNew().run(() -> tools.createChannel(name, "Second", null, null)));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> Channel.delete("name", name));
        }
    }

    @Test
    @TestTransaction
    void createChannelWithInvalidSemanticThrowsDescriptiveError() {
        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.createChannel("bad-sem-ch", "Test", "RUBBISH", null));

        assertTrue(ex.getMessage().contains("RUBBISH"),
                "error message should mention the invalid value");
        assertTrue(ex.getMessage().contains("APPEND"),
                "error message should list valid values");
    }

    @Test
    @TestTransaction
    void listChannelsIncludesCreatedChannels() {
        tools.createChannel("list-ch-1", "First", null, null);
        tools.createChannel("list-ch-2", "Second", "LAST_WRITE", null);

        List<ChannelDetail> channels = tools.listChannels();

        assertTrue(channels.stream().anyMatch(c -> "list-ch-1".equals(c.name())));
        assertTrue(channels.stream().anyMatch(c -> "list-ch-2".equals(c.name())));
    }

    @Test
    @TestTransaction
    void listChannelsIncludesMessageCount() {
        ChannelDetail ch = tools.createChannel("counted-ch", "Count test", null, null);
        // Send messages directly via MessageService to set up state
        messageService.send(ch.channelId(), "alice", MessageType.STATUS, "msg1", null, null);
        messageService.send(ch.channelId(), "bob", MessageType.STATUS, "msg2", null, null);

        List<ChannelDetail> channels = tools.listChannels();
        ChannelDetail counted = channels.stream()
                .filter(c -> "counted-ch".equals(c.name())).findFirst().orElseThrow();

        assertEquals(2, counted.messageCount());
    }

    @Test
    @TestTransaction
    void findChannelMatchesByName() {
        tools.createChannel("auth-refactor", "Auth refactoring", null, null);
        tools.createChannel("unrelated-ch", "Something else", null, null);

        List<ChannelDetail> found = tools.findChannel("auth");

        assertEquals(1, found.size());
        assertEquals("auth-refactor", found.get(0).name());
    }

    @Test
    @TestTransaction
    void findChannelMatchesByDescriptionCaseInsensitive() {
        tools.createChannel("my-channel", "Security review thread", null, null);

        List<ChannelDetail> found = tools.findChannel("SECURITY");

        assertEquals(1, found.size());
        assertEquals("my-channel", found.get(0).name());
    }

    @Test
    @TestTransaction
    void findChannelReturnsEmptyWhenNoMatch() {
        tools.createChannel("some-channel", "Some description", null, null);

        List<ChannelDetail> found = tools.findChannel("xyzzy-no-match");

        assertTrue(found.isEmpty());
    }
}
