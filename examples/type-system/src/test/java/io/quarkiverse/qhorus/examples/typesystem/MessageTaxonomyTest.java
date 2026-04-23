package io.quarkiverse.qhorus.examples.typesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Regression tests for the 9-type speech-act message taxonomy.
 *
 * <p>
 * Runs in CI with no LLM or model download. Verifies that the deontic
 * constraints from ADR-0005 are enforced by infrastructure:
 *
 * <ul>
 * <li>DECLINE and FAILURE require non-empty content (refusal must be explained)</li>
 * <li>HANDOFF requires a non-null target (delegation needs a destination)</li>
 * <li>QUERY and COMMAND auto-generate a correlationId (obligation tracking)</li>
 * <li>EVENT is excluded from agent context (perlocutionary record only)</li>
 * </ul>
 */
@QuarkusTest
class MessageTaxonomyTest {

    @Inject
    QhorusMcpTools tools;

    // --- Enum structure ---

    @Test
    void taxonomyHasNineTypes() {
        assertThat(MessageType.values()).hasSize(9);
    }

    @Test
    void onlyEventIsNotAgentVisible() {
        for (MessageType t : MessageType.values()) {
            if (t == MessageType.EVENT) {
                assertThat(t.isAgentVisible()).as("%s should not be agent-visible", t).isFalse();
            } else {
                assertThat(t.isAgentVisible()).as("%s should be agent-visible", t).isTrue();
            }
        }
    }

    @Test
    void onlyQueryAndCommandRequireCorrelationId() {
        assertThat(MessageType.QUERY.requiresCorrelationId()).isTrue();
        assertThat(MessageType.COMMAND.requiresCorrelationId()).isTrue();
        for (MessageType t : MessageType.values()) {
            if (t != MessageType.QUERY && t != MessageType.COMMAND) {
                assertThat(t.requiresCorrelationId())
                        .as("%s should not require correlationId", t).isFalse();
            }
        }
    }

    @Test
    void onlyDeclineAndFailureRequireContent() {
        assertThat(MessageType.DECLINE.requiresContent()).isTrue();
        assertThat(MessageType.FAILURE.requiresContent()).isTrue();
        for (MessageType t : MessageType.values()) {
            if (t != MessageType.DECLINE && t != MessageType.FAILURE) {
                assertThat(t.requiresContent())
                        .as("%s should not require content", t).isFalse();
            }
        }
    }

    @Test
    void onlyHandoffRequiresTarget() {
        assertThat(MessageType.HANDOFF.requiresTarget()).isTrue();
        for (MessageType t : MessageType.values()) {
            if (t != MessageType.HANDOFF) {
                assertThat(t.requiresTarget())
                        .as("%s should not require target", t).isFalse();
            }
        }
    }

    @Test
    void handoffDoneAndFailureAreTerminal() {
        assertThat(MessageType.HANDOFF.isTerminal()).isTrue();
        assertThat(MessageType.DONE.isTerminal()).isTrue();
        assertThat(MessageType.FAILURE.isTerminal()).isTrue();
        for (MessageType t : MessageType.values()) {
            if (t != MessageType.HANDOFF && t != MessageType.DONE && t != MessageType.FAILURE) {
                assertThat(t.isTerminal()).as("%s should not be terminal", t).isFalse();
            }
        }
    }

    // --- Infrastructure enforcement ---

    // sendMessage full signature:
    // (channelName, sender, type, content, correlationId, inReplyTo, artefactRefs, target, deadline)

    @Test
    @TestTransaction
    void declineWithoutContentIsRejected() {
        tools.createChannel("ts-decline-empty", "DECLINE without content", null, null);
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("ts-decline-empty", "agent-a", "decline", "", null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void failureWithoutContentIsRejected() {
        tools.createChannel("ts-failure-blank", "FAILURE without content", null, null);
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("ts-failure-blank", "agent-a", "failure", "   ", null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void handoffWithoutTargetIsRejected() {
        tools.createChannel("ts-handoff-notarget", "HANDOFF without target", null, null);
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("ts-handoff-notarget", "agent-a", "handoff",
                        "please take over", null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void queryAutoGeneratesCorrelationId() {
        tools.createChannel("ts-query-corr", "QUERY correlation", null, null);
        MessageResult result = tools.sendMessage("ts-query-corr", "agent-a", "query",
                "what is the row count?", null, null, null, null, null);
        assertThat(result).isNotNull();
        assertThat(result.correlationId()).as("QUERY must auto-generate a correlationId").isNotBlank();
    }

    @Test
    @TestTransaction
    void commandAutoGeneratesCorrelationId() {
        tools.createChannel("ts-command-corr", "COMMAND correlation", null, null);
        MessageResult result = tools.sendMessage("ts-command-corr", "orchestrator", "command",
                "review the auth module for vulnerabilities", null, null, null, null, null);
        assertThat(result).isNotNull();
        assertThat(result.correlationId()).as("COMMAND must auto-generate a correlationId").isNotBlank();
    }

    @Test
    @TestTransaction
    void validDeclineWithReasonIsAccepted() {
        tools.createChannel("ts-decline-ok", "Valid DECLINE", null, null);
        MessageResult result = tools.sendMessage("ts-decline-ok", "agent-a", "decline",
                "this task is outside my capabilities as a code review agent", null, null, null, null, null);
        assertThat(result).isNotNull();
    }

    @Test
    @TestTransaction
    void validHandoffWithTargetIsAccepted() {
        tools.createChannel("ts-handoff-ok", "Valid HANDOFF", null, null);
        MessageResult result = tools.sendMessage("ts-handoff-ok", "agent-a", "handoff",
                "delegating to compliance specialist", null, null, null, "capability:compliance-review", null);
        assertThat(result).isNotNull();
    }
}
