package io.quarkiverse.qhorus.examples.normativelayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.CommitmentState;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.api.message.MessageTypeViolationException;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.data.DataService;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.message.Commitment;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NormativeLayoutRobustnessTest {

    @Inject
    ChannelService channelService;
    @Inject
    InstanceService instanceService;
    @Inject
    MessageService messageService;
    @Inject
    DataService dataService;
    @Inject
    CommitmentStore commitmentStore;
    @Inject
    MessageStore messageStore;

    private SecureCodeReviewScenario scenario(String prefix) {
        return new SecureCodeReviewScenario(prefix + UUID.randomUUID(),
                channelService, instanceService, messageService, dataService);
    }

    @Test
    void directMessageService_bypassingMcpTool_serverSideStillEnforces() {
        // Even when bypassing the MCP tool layer and calling messageService.send() directly,
        // the server-side MessageTypePolicy still rejects disallowed types.
        SecureCodeReviewScenario s = scenario("rob-1-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        // observeChannel only allows EVENT — COMMAND must be rejected
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.observeChannel().id, "rogue-agent", MessageType.COMMAND,
                    "attempting to inject command", "corr-" + System.nanoTime(), null);
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void failure_dischargesObligation() {
        SecureCodeReviewScenario s = scenario("rob-2-");
        String corrId = "corr-failure-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            messageService.send(s.workChannel().id, "orchestrator", MessageType.COMMAND,
                    "Run full penetration test suite", corrId, null, null, "instance:researcher-001");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.workChannel().id, "researcher-001", MessageType.FAILURE,
                    "Penetration test runner crashed — out of memory.", corrId, null);
        });

        Commitment[] found = new Commitment[1];
        QuarkusTransaction.requiringNew().run(() -> {
            found[0] = commitmentStore.findByCorrelationId(corrId).orElse(null);
        });

        assertThat(found[0]).isNotNull();
        assertThat(found[0].state).isEqualTo(CommitmentState.FAILED);
    }

    @Test
    void allowedTypes_withWhitespace_isEnforcedCorrectly() {
        // Create a channel with allowedTypes containing leading/trailing whitespace and spaces
        String channelName = "rob-3-whitespace-" + System.nanoTime();
        Channel[] ch = new Channel[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ch[0] = channelService.create(channelName, "Whitespace test", ChannelSemantic.APPEND,
                    null, null, null, null, null, " EVENT , STATUS ");
        });

        // EVENT should be permitted
        Message[] eventMsg = new Message[1];
        QuarkusTransaction.requiringNew().run(() -> {
            eventMsg[0] = messageService.send(ch[0].id, "agent", MessageType.EVENT,
                    "{\"tool\":\"ok\"}", null, null);
        });
        assertThat(eventMsg[0]).isNotNull();

        // STATUS should be permitted
        Message[] statusMsg = new Message[1];
        QuarkusTransaction.requiringNew().run(() -> {
            statusMsg[0] = messageService.send(ch[0].id, "agent", MessageType.STATUS,
                    "still working", null, null);
        });
        assertThat(statusMsg[0]).isNotNull();

        // QUERY should be rejected
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(ch[0].id, "agent", MessageType.QUERY,
                    "any query", "corr-" + System.nanoTime(), null);
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void blankAllowedTypes_treatedAsOpenChannel() {
        // When allowedTypes is blank/whitespace, channel.allowedTypes should be null → open channel
        String channelName = "rob-4-blank-" + System.nanoTime();
        Channel[] ch = new Channel[1];
        QuarkusTransaction.requiringNew().run(() -> {
            ch[0] = channelService.create(channelName, "Blank types test", ChannelSemantic.APPEND,
                    null, null, null, null, null, "   ");
        });

        // Verify allowedTypes is null (blank was normalized away)
        assertThat(ch[0].allowedTypes).isNull();

        // COMMAND should succeed on an open channel
        Message[] msg = new Message[1];
        QuarkusTransaction.requiringNew().run(() -> {
            msg[0] = messageService.send(ch[0].id, "agent", MessageType.COMMAND,
                    "do something", "corr-" + System.nanoTime(), null,
                    null, "instance:other-001");
        });
        assertThat(msg[0]).isNotNull();
    }

    @Test
    void multipleScenarios_doNotInterfere() {
        // Two concurrent scenarios should not share observe channel events
        SecureCodeReviewScenario s1 = scenario("rob-5a-");
        SecureCodeReviewScenario s2 = scenario("rob-5b-");

        QuarkusTransaction.requiringNew().run(() -> {
            s1.setupChannels();
            s1.runResearcher(null);
        });

        QuarkusTransaction.requiringNew().run(() -> {
            s2.setupChannels();
            s2.runResearcher(null);
        });

        List<Message>[] events1 = new List[1];
        List<Message>[] events2 = new List[1];
        QuarkusTransaction.requiringNew().run(() -> {
            events1[0] = messageStore.scan(MessageQuery.builder()
                    .channelId(s1.observeChannel().id).build());
            events2[0] = messageStore.scan(MessageQuery.builder()
                    .channelId(s2.observeChannel().id).build());
        });

        // Each observe channel has exactly its own 2 EVENTs (researcher posts 2 read_file events)
        assertThat(events1[0]).hasSize(2);
        assertThat(events2[0]).hasSize(2);

        // All events in s1's observe channel have s1's observe channel ID
        assertThat(events1[0]).allMatch(m -> m.channelId.equals(s1.observeChannel().id));
        // All events in s2's observe channel have s2's observe channel ID
        assertThat(events2[0]).allMatch(m -> m.channelId.equals(s2.observeChannel().id));
    }
}
