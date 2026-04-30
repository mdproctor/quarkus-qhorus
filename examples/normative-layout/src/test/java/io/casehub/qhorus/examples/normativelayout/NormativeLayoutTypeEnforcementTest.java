package io.casehub.qhorus.examples.normativelayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NormativeLayoutTypeEnforcementTest {

    @Inject
    ChannelService channelService;
    @Inject
    InstanceService instanceService;
    @Inject
    MessageService messageService;
    @Inject
    DataService dataService;

    private SecureCodeReviewScenario scenario(String prefix) {
        return new SecureCodeReviewScenario(prefix + System.nanoTime(),
                channelService, instanceService, messageService, dataService);
    }

    @Test
    void observeChannel_rejectsQuery_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-1-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.observeChannel().id, "agent-x", MessageType.QUERY,
                    "some query", "corr-" + System.nanoTime(), null);
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void observeChannel_rejectsCommand_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-2-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.observeChannel().id, "agent-x", MessageType.COMMAND,
                    "some command", "corr-" + System.nanoTime(), null);
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void observeChannel_permitsEvent_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-3-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        Message[] result = new Message[1];
        QuarkusTransaction.requiringNew().run(() -> {
            result[0] = messageService.send(s.observeChannel().id, "researcher-001", MessageType.EVENT,
                    "{\"tool\":\"permitted_event\"}", null, null);
        });
        assertThat(result[0]).isNotNull();
        assertThat(result[0].messageType).isEqualTo(MessageType.EVENT);
    }

    @Test
    void oversightChannel_rejectsEvent_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-4-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.oversightChannel().id, "agent-x", MessageType.EVENT,
                    "{\"tool\":\"blocked\"}", null, null);
        })).isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void oversightChannel_permitsQuery_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-5-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        Message[] result = new Message[1];
        QuarkusTransaction.requiringNew().run(() -> {
            result[0] = messageService.send(s.oversightChannel().id, "human-001", MessageType.QUERY,
                    "What is the current analysis status?", "corr-" + System.nanoTime(), null,
                    null, "instance:researcher-001");
        });
        assertThat(result[0]).isNotNull();
        assertThat(result[0].messageType).isEqualTo(MessageType.QUERY);
    }

    @Test
    void oversightChannel_permitsCommand_serverSide() {
        SecureCodeReviewScenario s = scenario("enf-6-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        Message[] result = new Message[1];
        QuarkusTransaction.requiringNew().run(() -> {
            result[0] = messageService.send(s.oversightChannel().id, "human-001", MessageType.COMMAND,
                    "Halt analysis immediately", "corr-" + System.nanoTime(), null,
                    null, "instance:researcher-001");
        });
        assertThat(result[0]).isNotNull();
        assertThat(result[0].messageType).isEqualTo(MessageType.COMMAND);
    }

    @Test
    void workChannel_permitsAllNineTypes() {
        SecureCodeReviewScenario s = scenario("enf-7-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        for (MessageType t : MessageType.values()) {
            QuarkusTransaction.requiringNew().run(() -> {
                String corrId = t.requiresCorrelationId() ? "corr-" + System.nanoTime() : null;
                String target = (t == MessageType.HANDOFF) ? "instance:other-001" : null;
                String content = (t == MessageType.DECLINE || t == MessageType.FAILURE)
                        ? "required non-empty content"
                        : "payload for " + t;
                messageService.send(s.workChannel().id, "agent-test", t,
                        content, corrId, null, null, target);
            });
        }
    }

    @Test
    void violationException_messageContainsChannelNameAndType() {
        SecureCodeReviewScenario s = scenario("enf-8-");
        QuarkusTransaction.requiringNew().run(s::setupChannels);

        // STATUS is not in "EVENT" — sending it to observe channel should throw with
        // channel name and type name in the message
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.observeChannel().id, "agent-x", MessageType.STATUS,
                    "still working", null, null);
        }))
                .isInstanceOf(MessageTypeViolationException.class)
                .hasMessageContaining(s.observeChannel)
                .hasMessageContaining("STATUS");
    }
}
