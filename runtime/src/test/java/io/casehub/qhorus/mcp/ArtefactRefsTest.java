package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageSummary;
import io.casehub.qhorus.runtime.message.Message;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ArtefactRefsTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void sendMessageWithArtefactRefsReturnsThemInCheckMessages() {
        tools.createChannel("arefs-ch-1", "Test", null, null, null, null, null, null, null);
        String uuid1 = tools.shareArtefact("aref-d1", "d", "alice", "content", false, true).artefactId().toString();
        String uuid2 = tools.shareArtefact("aref-d2", "d", "alice", "content", false, true).artefactId().toString();

        tools.sendMessage("arefs-ch-1", "alice", "status", "message with refs", null, null, List.of(uuid1, uuid2), null, null);

        CheckResult result = tools.checkMessages("arefs-ch-1", 0L, 10, null, null, null);

        assertEquals(1, result.messages().size());
        List<String> refs = result.messages().get(0).artefactRefs();
        assertEquals(2, refs.size());
        assertTrue(refs.contains(uuid1));
        assertTrue(refs.contains(uuid2));
    }

    @Test
    @TestTransaction
    void sendMessageWithNoArtefactRefsHasEmptyListInMessageSummary() {
        tools.createChannel("arefs-ch-2", "Test", null, null, null, null, null, null, null);

        tools.sendMessage("arefs-ch-2", "alice", "status", "no refs", null, null, null, null, null);

        CheckResult result = tools.checkMessages("arefs-ch-2", 0L, 10, null, null, null);

        List<String> refs = result.messages().get(0).artefactRefs();
        assertNotNull(refs, "artefactRefs must be an empty list, never null");
        assertTrue(refs.isEmpty());
    }

    @Test
    @TestTransaction
    void sendMessageWithEmptyArtefactRefsListHasEmptyListInSummary() {
        tools.createChannel("arefs-ch-3", "Test", null, null, null, null, null, null, null);

        tools.sendMessage("arefs-ch-3", "alice", "status", "empty refs", null, null, List.of(), null, null);

        CheckResult result = tools.checkMessages("arefs-ch-3", 0L, 10, null, null, null);
        assertTrue(result.messages().get(0).artefactRefs().isEmpty());
    }

    @Test
    @TestTransaction
    void sendMessageResultIncludesArtefactRefs() {
        tools.createChannel("arefs-ch-4", "Test", null, null, null, null, null, null, null);
        String uuid = tools.shareArtefact("aref-d4", "d", "alice", "content", false, true).artefactId().toString();

        MessageResult result = tools.sendMessage("arefs-ch-4", "alice", "status", "with ref", null, null, List.of(uuid), null, null);

        assertNotNull(result.artefactRefs());
        assertEquals(1, result.artefactRefs().size());
        assertEquals(uuid, result.artefactRefs().get(0));
    }

    @Test
    @TestTransaction
    void artefactRefsAppearsInGetReplies() {
        tools.createChannel("arefs-ch-5", "Test", null, null, null, null, null, null, null);
        String uuid = tools.shareArtefact("aref-d5", "d", "alice", "content", false, true).artefactId().toString();
        MessageResult request = tools.sendMessage("arefs-ch-5", "alice", "query", "Question?", null, null, null, null, null);
        tools.sendMessage("arefs-ch-5", "bob", "response", "Answer with artefact", null, request.messageId(), List.of(uuid), null, null);

        List<MessageSummary> replies = tools.getReplies(request.messageId(), null, null, null);

        assertEquals(1, replies.size());
        assertTrue(replies.get(0).artefactRefs().contains(uuid));
    }

    @Test
    @TestTransaction
    void artefactRefsAppearsInSearchMessages() {
        tools.createChannel("arefs-ch-6", "Test", null, null, null, null, null, null, null);
        String uuid = tools.shareArtefact("aref-d6", "d", "alice", "content", false, true).artefactId().toString();
        tools.sendMessage("arefs-ch-6", "alice", "status", "analysis complete", null, null, List.of(uuid), null, null);

        List<MessageSummary> results = tools.searchMessages("analysis", null, 10, null);

        assertEquals(1, results.size());
        assertTrue(results.get(0).artefactRefs().contains(uuid));
    }

    @Test
    @TestTransaction
    void nullArtefactRefsStoredAsNullNotEmptyString() {
        tools.createChannel("arefs-ch-7", "Test", null, null, null, null, null, null, null);

        tools.sendMessage("arefs-ch-7", "alice", "status", "no refs", null, null, null, null, null);

        // Verify the DB column is null, not an empty string
        Message msg = Message.<Message> find("channelId = ?1",
                io.casehub.qhorus.runtime.channel.Channel.<io.casehub.qhorus.runtime.channel.Channel> find("name",
                        "arefs-ch-7")
                        .firstResult().id)
                .firstResult();
        assertNull(msg.artefactRefs, "artefactRefs column should be null when no refs provided");
    }
}
