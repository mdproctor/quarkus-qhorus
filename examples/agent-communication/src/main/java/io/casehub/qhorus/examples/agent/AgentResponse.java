package io.casehub.qhorus.examples.agent;

/**
 * Structured response from an agent specifying the message type and content.
 * LLMs populate this from context — the message type is chosen automatically
 * based on the situation described.
 */
public record AgentResponse(
        /** One of: QUERY COMMAND RESPONSE STATUS DECLINE HANDOFF DONE FAILURE */
        String messageType,
        /** The natural language content of the message */
        String content,
        /** Correlation ID to reply to (for RESPONSE, DONE, FAILURE, STATUS) — may be null */
        String correlationId) {
}
