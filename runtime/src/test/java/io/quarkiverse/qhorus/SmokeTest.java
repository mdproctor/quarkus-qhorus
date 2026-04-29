package io.quarkiverse.qhorus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.config.QhorusConfig;
import io.quarkiverse.qhorus.runtime.data.DataService;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Smoke test: Quarkus boots, Flyway V1 migration runs, all four domain services
 * are injectable and functional. This is the acceptance gate for Phase 1.
 */
@QuarkusTest
class SmokeTest {

    @Inject
    QhorusConfig config;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Inject
    InstanceService instanceService;

    @Inject
    DataService dataService;

    @Test
    void configBindsCorrectly() {
        assertEquals(120, config.cleanup().staleInstanceSeconds());
        assertEquals(7, config.cleanup().dataRetentionDays());
    }

    @Test
    @TestTransaction
    void fullMeshWorkflow() throws InterruptedException {
        // 1. Register two agents
        Instance alice = instanceService.register("smoke-alice", "Alice agent", List.of("code-review"));
        Instance bob = instanceService.register("smoke-bob", "Bob agent", List.of("testing"));

        assertNotNull(alice.id);
        assertNotNull(bob.id);
        assertEquals("online", alice.status);

        // 2. Create a channel — capture lastActivityAt at creation time
        Channel channel = channelService.create("smoke-channel", "Smoke test channel",
                ChannelSemantic.APPEND, null);
        var channelCreatedAt = channel.lastActivityAt;

        // Brief pause so lastActivityAt can advance after messaging
        Thread.sleep(5);

        assertNotNull(channel.id);
        assertEquals(ChannelSemantic.APPEND, channel.semantic);

        // 3. Share an artefact
        SharedData artefact = dataService.store("smoke-analysis", "Analysis result", "smoke-alice",
                "Found 3 issues in auth module", false, true);

        assertNotNull(artefact.id);
        assertTrue(artefact.complete);

        // 4. Alice delegates auth review work
        Message request = messageService.send(channel.id, "smoke-alice", MessageType.COMMAND,
                "Please review auth issues", "smoke-corr-001", null);

        assertNotNull(request.id);
        assertEquals(MessageType.COMMAND, request.messageType);

        // 5. Bob polls and replies
        List<Message> polled = messageService.pollAfter(channel.id, 0L, 10);
        assertFalse(polled.isEmpty());

        Message reply = messageService.send(channel.id, "smoke-bob", MessageType.RESPONSE,
                "Reviewed — 2 are critical", "smoke-corr-001", request.id);

        // 6. Verify reply incremented parent replyCount
        Message refreshedRequest = messageService.findById(request.id).orElseThrow();
        assertEquals(1, refreshedRequest.replyCount);

        // 7. Bob claims then releases the artefact
        dataService.claim(artefact.id, bob.id);
        assertFalse(dataService.isGcEligible(artefact.id));

        dataService.release(artefact.id, bob.id);
        assertTrue(dataService.isGcEligible(artefact.id));

        // 8. Channel last activity advanced after messaging
        Optional<Channel> updatedChannel = channelService.findByName("smoke-channel");
        assertTrue(updatedChannel.isPresent());
        assertTrue(updatedChannel.get().lastActivityAt.isAfter(channelCreatedAt),
                "channel.lastActivityAt should advance after messages are sent");
    }
}
