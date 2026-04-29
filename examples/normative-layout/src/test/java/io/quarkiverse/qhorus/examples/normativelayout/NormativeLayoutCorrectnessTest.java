package io.quarkiverse.qhorus.examples.normativelayout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.data.DataService;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.store.MessageStore;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NormativeLayoutCorrectnessTest {

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
        return new SecureCodeReviewScenario("cor-" + UUID.randomUUID(),
                channelService, instanceService, messageService, dataService);
    }

    @Test
    void workChannelMessageCount_matchesExpectedProtocol() {
        // Expected work channel messages: STATUS(researcher) + DONE(researcher)
        // + QUERY(reviewer) + RESPONSE(researcher) + DONE(reviewer) = 5 messages
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher("corr-r-" + System.nanoTime());
            s.runReviewer("corr-q-" + System.nanoTime(), "corr-d-" + System.nanoTime());
        });

        List<Message>[] msgs = new List[1];
        QuarkusTransaction.requiringNew().run(() -> {
            msgs[0] = messageStore.scan(MessageQuery.builder()
                    .channelId(s.workChannel().id).build());
        });

        assertThat(msgs[0]).hasSize(5);
        List<MessageType> types = msgs[0].stream().map(m -> m.messageType).toList();
        assertThat(types).containsExactly(
                MessageType.STATUS,
                MessageType.DONE,
                MessageType.QUERY,
                MessageType.RESPONSE,
                MessageType.DONE);
    }

    @Test
    void observeChannelContainsOnlyEvents() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher(null);
        });

        List<Message>[] events = new List[1];
        QuarkusTransaction.requiringNew().run(() -> {
            events[0] = messageStore.scan(MessageQuery.builder()
                    .channelId(s.observeChannel().id).build());
        });

        assertThat(events[0]).isNotEmpty();
        assertThat(events[0]).allMatch(m -> m.messageType == MessageType.EVENT);
    }

    @Test
    void observeChannelMessageCount_matchesResearcherToolCalls() {
        // Researcher posts exactly 2 EVENTs (read_file AuthService + read_file TokenRefreshService)
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher(null);
        });

        List<Message>[] events = new List[1];
        QuarkusTransaction.requiringNew().run(() -> {
            events[0] = messageStore.scan(MessageQuery.builder()
                    .channelId(s.observeChannel().id).build());
        });

        assertThat(events[0]).hasSize(2);
    }

    @Test
    void artefactContent_isAccessibleAfterScenario() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher(null);
            s.runReviewer("corr-q-" + System.nanoTime(), "corr-d-" + System.nanoTime());
        });

        Optional<SharedData>[] analysis = new Optional[1];
        Optional<SharedData>[] report = new Optional[1];
        QuarkusTransaction.requiringNew().run(() -> {
            analysis[0] = dataService.getByKey("auth-analysis-v1-" + s.caseId);
            report[0] = dataService.getByKey("review-report-v1-" + s.caseId);
        });

        assertThat(analysis[0]).isPresent();
        assertThat(analysis[0].get().content).contains("SQL injection");

        assertThat(report[0]).isPresent();
        assertThat(report[0].get().content).contains("Root cause A");
    }

    @Test
    void doneMessages_sentByCorrectSenders() {
        SecureCodeReviewScenario s = scenario();
        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher("corr-r-" + System.nanoTime());
            s.runReviewer("corr-q-" + System.nanoTime(), "corr-d-" + System.nanoTime());
        });

        List<Message>[] dones = new List[1];
        QuarkusTransaction.requiringNew().run(() -> {
            List<Message> all = messageStore.scan(MessageQuery.builder()
                    .channelId(s.workChannel().id).build());
            dones[0] = all.stream().filter(m -> m.messageType == MessageType.DONE).toList();
        });

        assertThat(dones[0]).hasSize(2);
        List<String> senders = dones[0].stream().map(m -> m.sender).toList();
        assertThat(senders).containsExactlyInAnyOrder("researcher-001", "reviewer-001");
    }
}
