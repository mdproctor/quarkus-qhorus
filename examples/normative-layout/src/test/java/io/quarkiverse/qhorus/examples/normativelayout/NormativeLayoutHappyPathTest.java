package io.quarkiverse.qhorus.examples.normativelayout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.data.DataService;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NormativeLayoutHappyPathTest {

    @Inject
    ChannelService channelService;
    @Inject
    InstanceService instanceService;
    @Inject
    MessageService messageService;
    @Inject
    DataService dataService;
    @Inject
    MessageStore messageStore;

    private SecureCodeReviewScenario scenario() {
        return new SecureCodeReviewScenario("scr-" + UUID.randomUUID(),
                channelService, instanceService, messageService, dataService);
    }

    @Test
    void researcherPostsDoneOnWorkChannel() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            Message done = s.runResearcher("corr-researcher-done");
            assertThat(done.messageType).isEqualTo(MessageType.DONE);
            assertThat(done.content).contains("auth-analysis-v1");
        });
    }

    @Test
    void reviewerPostsDoneAfterResearcher() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher(null);
            Message done = s.runReviewer("corr-q-001", "corr-reviewer-done");
            assertThat(done.messageType).isEqualTo(MessageType.DONE);
            assertThat(done.content).contains("review-report-v1");
        });
    }

    @Test
    void artefactsStoredAndRetrievable() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher(null);
            s.runReviewer("corr-q", "corr-rev-done");
            assertThat(dataService.getByKey("auth-analysis-v1-" + s.caseId)).isPresent();
            assertThat(dataService.getByKey("review-report-v1-" + s.caseId)).isPresent();
        });
    }

    @Test
    void observeChannelReceivesEventMessages() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher(null);
            List<Message> events = messageStore.scan(MessageQuery.builder()
                    .channelId(s.observeChannel().id).build());
            assertThat(events).isNotEmpty();
            assertThat(events).allMatch(m -> m.messageType == MessageType.EVENT);
        });
    }

    @Test
    void workChannelContainsStatusQueryResponseDone() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher(null);
            s.runReviewer("corr-q", "corr-done");
            List<Message> msgs = messageStore.scan(MessageQuery.builder()
                    .channelId(s.workChannel().id).build());
            List<MessageType> types = msgs.stream().map(m -> m.messageType).toList();
            assertThat(types).contains(MessageType.STATUS, MessageType.DONE,
                    MessageType.QUERY, MessageType.RESPONSE);
        });
    }
}
