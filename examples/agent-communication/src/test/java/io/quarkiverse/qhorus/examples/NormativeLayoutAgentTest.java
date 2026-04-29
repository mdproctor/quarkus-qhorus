package io.quarkiverse.qhorus.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.examples.agent.OrchestratorAgent;
import io.quarkiverse.qhorus.examples.agent.WorkerAgent;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Demonstrates real LLM agents reasoning about message types within a 3-channel normative layout.
 *
 * <p>
 * Uses Jlama (pure Java inference, no external process). Model downloads
 * ~700MB from HuggingFace on first run and caches in ~/.jlama/.
 *
 * <p>
 * Verifies that:
 * <ul>
 * <li>Channels are created with correct {@code allowedTypes} constraints</li>
 * <li>An orchestrator agent classifies a "researcher completing analysis" scenario as DONE</li>
 * <li>A worker agent classifies a "peer answering a clarification query" scenario as RESPONSE</li>
 * </ul>
 */
@QuarkusTest
class NormativeLayoutAgentTest {

    @Inject
    ChannelService channelService;

    @Inject
    OrchestratorAgent orchestrator;

    @Inject
    WorkerAgent worker;

    @Test
    void normativeChannelsCreatedWithCorrectAllowedTypes() {
        String caseId = "agent-layout-" + UUID.randomUUID();
        String workName = "case-" + caseId + "/work";
        String observeName = "case-" + caseId + "/observe";
        String oversightName = "case-" + caseId + "/oversight";

        QuarkusTransaction.requiringNew().run(() -> {
            channelService.create(workName, "Worker coordination", ChannelSemantic.APPEND,
                    null, null, null, null, null, null);
            channelService.create(observeName, "Telemetry", ChannelSemantic.APPEND,
                    null, null, null, null, null, "EVENT");
            channelService.create(oversightName, "Human governance", ChannelSemantic.APPEND,
                    null, null, null, null, null, "QUERY,COMMAND");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Channel work = channelService.findByName(workName).orElseThrow();
            assertThat(work.allowedTypes).isNull();

            Channel observe = channelService.findByName(observeName).orElseThrow();
            assertThat(observe.allowedTypes).isEqualTo("EVENT");

            Channel oversight = channelService.findByName(oversightName).orElseThrow();
            assertThat(oversight.allowedTypes).isEqualTo("QUERY,COMMAND");
        });
    }

    @Test
    void orchestratorClassifiesResearcherCompletionAsDone() {
        var response = orchestrator.handle(
                "A researcher has completed their security analysis of the authentication module. " +
                        "They have finished all investigation work and produced a final report. " +
                        "Send the appropriate message to signal task completion.");

        assertThat(response.messageType()).isEqualTo("DONE");
        assertThat(response.content()).isNotBlank();
    }

    @Test
    void workerClassifiesPeerClarificationAnswerAsResponse() {
        String correlationId = UUID.randomUUID().toString();
        var response = worker.handle(
                "QUERY",
                correlationId,
                "Does the SQL injection in TokenRefreshService.java share the same root cause as the one in AuthService.java?");

        assertThat(response.messageType()).isEqualTo("RESPONSE");
        assertThat(response.content()).isNotBlank();
    }
}
