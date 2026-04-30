package io.casehub.qhorus.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.examples.agent.WorkerAgent;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Demonstrates structured refusal using the DECLINE message type.
 *
 * <p>
 * DECLINE = "I will not attempt this" (outside capabilities).
 * FAILURE = "I tried but could not complete" (attempted and failed).
 * These are categorically distinct — DECLINE requires a reason in content.
 *
 * <p>
 * Uses Jlama (pure Java inference, no external process). Model downloads
 * ~700MB from HuggingFace on first run and caches in ~/.jlama/.
 */
@QuarkusTest
class OutOfScopeDeclineTest {

    @Inject
    WorkerAgent worker;

    @Test
    void agentDeclinesTaskOutsideCapabilities() {
        var response = worker.handle(
                "COMMAND",
                UUID.randomUUID().toString(),
                "You are a code review specialist. Perform a full financial audit " +
                        "of the company's Q3 accounts and produce an IFRS-compliant report.");

        assertThat(response.messageType())
                .as("Agent should DECLINE a task outside its capabilities, not attempt and FAIL")
                .isEqualTo("DECLINE");

        assertThat(response.content())
                .as("DECLINE must include a reason — cannot be empty")
                .isNotBlank()
                .hasSizeGreaterThan(10);
    }

    @Test
    void declineIsDistinctFromFailure() {
        var attemptedTask = worker.handle(
                "COMMAND",
                UUID.randomUUID().toString(),
                "You attempted to compile the code but the build system is unavailable. " +
                        "Report the outcome — you tried but could not complete the compilation.");

        assertThat(attemptedTask.messageType())
                .as("A task attempted but not completed should be FAILURE, not DECLINE")
                .isIn("FAILURE", "STATUS");
    }
}
