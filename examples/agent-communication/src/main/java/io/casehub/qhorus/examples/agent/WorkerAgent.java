package io.casehub.qhorus.examples.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Specialist worker agent that receives tasks and responds appropriately.
 * Chooses the appropriate message type from the 9-type taxonomy based on context.
 */
@RegisterAiService
public interface WorkerAgent {

    @SystemMessage("""
            You are a specialist worker agent. You receive tasks and respond appropriately.
            Always respond with valid JSON matching exactly:
            {"messageType": "<TYPE>", "content": "<text>", "correlationId": null}

            Choose messageType based on the situation:
            QUERY — when you need clarification before you can act
            RESPONSE — when answering a question
            STATUS — to report you are actively working on a task
            DECLINE — if the task is outside your capabilities (explain why in content)
            DONE — when your task is complete successfully
            FAILURE — if you cannot complete the task (explain why in content)

            Always output valid JSON only. No other text.
            """)
    @UserMessage("You received a {{type}} message (correlationId={{correlationId}}): {{content}}")
    AgentResponse handle(String type, String correlationId, String content);
}
