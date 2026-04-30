package io.casehub.qhorus.examples;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.examples.agent.OrchestratorAgent;
import io.casehub.qhorus.examples.agent.WorkerAgent;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Baseline classification accuracy test.
 *
 * <p>
 * Validates that Llama-3.2-1B-Instruct (via Jlama) correctly classifies Qhorus
 * message types from natural language context. Target: >= 80% accuracy per category.
 *
 * <p>
 * Results inform the journal paper evaluation: the taxonomy is correctly
 * granular if LLMs can classify it reliably from context alone.
 *
 * <p>
 * Uses Jlama (pure Java inference, no external process). Model downloads
 * ~700MB from HuggingFace on first run and caches in ~/.jlama/.
 * Switch to a larger model in application.properties if accuracy is insufficient.
 */
@QuarkusTest
class ClassificationAccuracyTest {

    @Inject
    OrchestratorAgent orchestrator;

    @Inject
    WorkerAgent worker;

    record Scenario(String prompt, String expectedType, String description) {
    }

    @Test
    void classifiesQueryCorrectly() {
        var scenarios = new Scenario[] {
                new Scenario("What is the current row count in the orders table?", "QUERY", "information request"),
                new Scenario("How many agents are currently registered?", "QUERY", "count query"),
                new Scenario("Is the compliance report ready?", "QUERY", "status question"),
        };
        assertAccuracy(scenarios, 0.8, "QUERY");
    }

    @Test
    void classifiesCommandCorrectly() {
        var scenarios = new Scenario[] {
                new Scenario("Generate the monthly compliance report and send it to the finance team.", "COMMAND",
                        "action request"),
                new Scenario("Process all pending refund requests in the queue.", "COMMAND", "batch action"),
                new Scenario("Run the database migration for the new schema.", "COMMAND", "execution request"),
        };
        assertAccuracy(scenarios, 0.8, "COMMAND");
    }

    @Test
    void classifiesDeclineCorrectly() {
        var scenarios = new Scenario[] {
                new Scenario(
                        "You are a code review agent. You received a COMMAND to perform a financial audit. " +
                                "This is outside your domain. Respond appropriately.",
                        "DECLINE", "out of scope"),
                new Scenario(
                        "You are a read-only reporting agent. You received a COMMAND to delete customer records. " +
                                "You do not have write permissions. Respond appropriately.",
                        "DECLINE", "permission refused"),
        };
        assertAccuracy(scenarios, 0.8, "DECLINE");
    }

    private void assertAccuracy(Scenario[] scenarios, double minAccuracy, String category) {
        int correct = 0;
        for (Scenario scenario : scenarios) {
            var response = orchestrator.handle(scenario.prompt());
            if (scenario.expectedType().equals(response.messageType())) {
                correct++;
            } else {
                System.out.printf("MISS [%s / %s]: expected=%s actual=%s%n",
                        category, scenario.description(), scenario.expectedType(), response.messageType());
            }
        }
        double accuracy = (double) correct / scenarios.length;
        System.out.printf("Accuracy [%s]: %.0f%% (%d/%d)%n", category, accuracy * 100, correct, scenarios.length);
        assertThat(accuracy)
                .as("Classification accuracy for %s should be >= %.0f%%", category, minAccuracy * 100)
                .isGreaterThanOrEqualTo(minAccuracy);
    }
}
