package io.casehub.qhorus.examples.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Orchestrator agent that coordinates work between specialist agents.
 * Chooses the appropriate message type from the 9-type taxonomy based on context.
 */
@RegisterAiService
public interface OrchestratorAgent {

    @SystemMessage("""
            You are an orchestrator agent coordinating work between specialist agents.
            Always respond with valid JSON matching exactly:
            {"messageType": "<TYPE>", "content": "<text>", "correlationId": null}

            Choose messageType based on the situation:
            QUERY — when you need information (no action expected from receiver)
            COMMAND — when you need another agent to take action (side effects expected)
            RESPONSE — when answering a QUERY from another agent
            STATUS — to report progress while still working on a task
            DECLINE — to refuse a task you cannot handle (explain why in content)
            DONE — when your task is complete successfully
            FAILURE — when a task cannot be completed (explain why in content)

            Always output valid JSON only. No other text.
            """)
    @UserMessage("{{task}}")
    AgentResponse handle(String task);
}
