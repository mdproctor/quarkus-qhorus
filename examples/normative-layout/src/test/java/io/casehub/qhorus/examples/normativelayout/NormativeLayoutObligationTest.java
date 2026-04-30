package io.casehub.qhorus.examples.normativelayout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NormativeLayoutObligationTest {

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

    private SecureCodeReviewScenario scenario() {
        return new SecureCodeReviewScenario("obl-" + UUID.randomUUID(),
                channelService, instanceService, messageService, dataService);
    }

    @Test
    void query_createsOpenCommitment() {
        SecureCodeReviewScenario s = scenario();
        String corrId = "corr-obl-query-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            messageService.send(s.workChannel().id, "reviewer-001", MessageType.QUERY,
                    "What is the root cause?", corrId, null, null, "instance:researcher-001");
        });

        // Query the commitment in a separate transaction to verify it was persisted
        Commitment[] found = new Commitment[1];
        QuarkusTransaction.requiringNew().run(() -> {
            found[0] = commitmentStore.findByCorrelationId(corrId).orElse(null);
        });

        assertThat(found[0]).isNotNull();
        assertThat(found[0].state).isEqualTo(CommitmentState.OPEN);
        assertThat(found[0].correlationId).isEqualTo(corrId);
    }

    @Test
    void response_fulfillsOpenCommitment() {
        SecureCodeReviewScenario s = scenario();
        String corrId = "corr-obl-resp-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            messageService.send(s.workChannel().id, "reviewer-001", MessageType.QUERY,
                    "Does TokenRefreshService share the same root cause?",
                    corrId, null, null, "instance:researcher-001");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.workChannel().id, "researcher-001", MessageType.RESPONSE,
                    "Yes — same interpolated SQL pattern.",
                    corrId, null);
        });

        Commitment[] found = new Commitment[1];
        QuarkusTransaction.requiringNew().run(() -> {
            found[0] = commitmentStore.findByCorrelationId(corrId).orElse(null);
        });

        assertThat(found[0]).isNotNull();
        assertThat(found[0].state).isEqualTo(CommitmentState.FULFILLED);
    }

    @Test
    void fullScenario_noStalledObligations() {
        SecureCodeReviewScenario s = scenario();

        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            s.runResearcher("corr-researcher-full-" + System.nanoTime());
            s.runReviewer("corr-q-full-" + System.nanoTime(), "corr-reviewer-full-" + System.nanoTime());
        });

        // Check that no OPEN commitments remain on the work channel
        List<Commitment>[] openCommitments = new List[1];
        QuarkusTransaction.requiringNew().run(() -> {
            openCommitments[0] = commitmentStore.findByState(CommitmentState.OPEN, s.workChannel().id);
        });

        assertThat(openCommitments[0]).isEmpty();
    }

    @Test
    void decline_dischargesObligation() {
        SecureCodeReviewScenario s = scenario();
        String corrId = "corr-obl-decline-" + System.nanoTime();

        QuarkusTransaction.requiringNew().run(() -> {
            s.setupChannels();
            messageService.send(s.workChannel().id, "reviewer-001", MessageType.QUERY,
                    "Can you audit the compliance layer?",
                    corrId, null, null, "instance:researcher-001");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            messageService.send(s.workChannel().id, "researcher-001", MessageType.DECLINE,
                    "Outside my scope — I am a security analyst, not a compliance auditor.",
                    corrId, null);
        });

        Commitment[] found = new Commitment[1];
        QuarkusTransaction.requiringNew().run(() -> {
            found[0] = commitmentStore.findByCorrelationId(corrId).orElse(null);
        });

        assertThat(found[0]).isNotNull();
        assertThat(found[0].state).isEqualTo(CommitmentState.DECLINED);
    }
}
