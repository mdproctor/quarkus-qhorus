package io.casehub.qhorus.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.examples.agent.OrchestratorAgent;
import io.casehub.qhorus.examples.agent.WorkerAgent;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Demonstrates a code review pipeline using typed Qhorus message types.
 *
 * <p>
 * Flow: Orchestrator sends COMMAND → Worker sends STATUS or QUERY
 * → Orchestrator sends RESPONSE → Worker sends DONE.
 *
 * <p>
 * Uses Jlama (pure Java inference, no external process). Model downloads
 * ~700MB from HuggingFace on first run and caches in ~/.jlama/.
 */
@QuarkusTest
class CodeReviewPipelineTest {

    @Inject
    OrchestratorAgent orchestrator;

    @Inject
    WorkerAgent worker;

    @Test
    void codeReviewPipelineUsesCorrectMessageTypes() {
        var orchestratorDecision = orchestrator.handle(
                "Delegate a code review of the authentication module to the worker agent. " +
                        "You need them to check for security vulnerabilities.");

        assertThat(orchestratorDecision.messageType()).isEqualTo("COMMAND");
        assertThat(orchestratorDecision.content()).isNotBlank();

        var workerStatus = worker.handle(
                "COMMAND",
                UUID.randomUUID().toString(),
                orchestratorDecision.content());

        assertThat(workerStatus.messageType()).isIn("STATUS", "QUERY");

        if ("QUERY".equals(workerStatus.messageType())) {
            assertThat(workerStatus.content())
                    .as("A QUERY should ask for information, not issue a command")
                    .isNotBlank();

            var orchestratorResponse = orchestrator.handle(
                    "The worker asked: " + workerStatus.content() +
                            ". Answer their question: the auth module uses JWT tokens with RS256 algorithm.");
            assertThat(orchestratorResponse.messageType()).isEqualTo("RESPONSE");
        }

        var workerDone = worker.handle(
                "COMMAND",
                UUID.randomUUID().toString(),
                "Complete your code review of the authentication module. " +
                        "It uses JWT with RS256. Report your findings.");

        assertThat(workerDone.messageType()).isIn("DONE", "STATUS");
        assertThat(workerDone.content()).isNotBlank();
    }
}
