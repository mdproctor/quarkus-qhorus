package io.casehub.qhorus.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Issue #35 — GET /a2a/tasks/{id} returns A2A Task status via correlation_id lookup.
 *
 * <p>
 * State derivation rules:
 * <ul>
 * <li>No messages → 404 Not Found</li>
 * <li>Only QUERY/COMMAND messages → {@code "submitted"}</li>
 * <li>Any STATUS messages present → {@code "working"}</li>
 * <li>Any RESPONSE or DONE message → {@code "completed"}</li>
 * <li>Any FAILURE or DECLINE message → {@code "failed"}</li>
 * </ul>
 *
 * <p>
 * Three test levels:
 * <ul>
 * <li>Unit: each state transition, 404, history mapping</li>
 * <li>Integration: task created via send endpoint, retrieved via get</li>
 * <li>End-to-end: full A2A lifecycle — send → check submitted → respond → check completed</li>
 * </ul>
 *
 * <p>
 * Note: HTTP tests avoid {@code @TestTransaction} — see A2ASendMessageTest for explanation.
 *
 * <p>
 * Refs #35, Epic #32.
 */
@QuarkusTest
@TestProfile(A2AEnabledProfile.class)
class A2AGetTaskTest {

    @Inject
    QhorusMcpTools tools;

    private static final String SEND_PATH = "/a2a/message:send";
    private static final String TASKS_PATH = "/a2a/tasks/";

    // -----------------------------------------------------------------------
    // Helper — send an A2A message and return the task id
    // -----------------------------------------------------------------------

    private String sendA2A(String channel, String role, String text, String taskId) {
        String taskPart = taskId != null ? "\"taskId\":\"" + taskId + "\"," : "";
        String body = "{\"message\":{\"role\":\"" + role + "\","
                + taskPart
                + "\"contextId\":\"" + channel + "\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"" + text + "\"}]}}";

        return given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(body)
                .when().post(SEND_PATH)
                .then()
                .statusCode(200)
                .extract().path("task.id");
    }

    // -----------------------------------------------------------------------
    // Unit — state derivation and 404
    // -----------------------------------------------------------------------

    @Test
    void unknownTaskIdReturns404() {
        given()
                .when().get(TASKS_PATH + UUID.randomUUID())
                .then()
                .statusCode(404);
    }

    @Test
    void taskWithOnlyRequestMessageIsSubmitted() {
        tools.createChannel("a2a-gt-1", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();
        sendA2A("a2a-gt-1", "user", "initial request", taskId);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("submitted"))
                .body("id", equalTo(taskId))
                .body("contextId", equalTo("a2a-gt-1"));
    }

    @Test
    void taskWithStatusMessageIsWorking() {
        tools.createChannel("a2a-gt-2", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();

        // Request message (submitted)
        sendA2A("a2a-gt-2", "user", "initial request", taskId);

        // Agent sends a status update — transitions to working
        tools.sendMessage("a2a-gt-2", "agent", "status", "processing...",
                taskId, null);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("working"));
    }

    @Test
    void taskWithResponseMessageIsCompleted() {
        tools.createChannel("a2a-gt-3", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();

        sendA2A("a2a-gt-3", "user", "request", taskId);
        tools.sendMessage("a2a-gt-3", "agent", "response", "here is the answer",
                taskId, null);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("completed"));
    }

    @Test
    void taskWithDoneMessageIsCompleted() {
        tools.createChannel("a2a-gt-4", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();

        sendA2A("a2a-gt-4", "user", "request", taskId);
        tools.sendMessage("a2a-gt-4", "agent", "done", "task finished",
                taskId, null);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("completed"));
    }

    @Test
    void taskWithFailureMessageIsFailed() {
        tools.createChannel("a2a-gt-4b", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();

        sendA2A("a2a-gt-4b", "user", "request", taskId);
        tools.sendMessage("a2a-gt-4b", "agent", "failure",
                "could not complete the requested action", taskId, null);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("failed"));
    }

    @Test
    void taskIdAndContextIdPresentInResponse() {
        tools.createChannel("a2a-gt-5", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();
        sendA2A("a2a-gt-5", "user", "hello", taskId);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("id", equalTo(taskId))
                .body("contextId", equalTo("a2a-gt-5"));
    }

    // -----------------------------------------------------------------------
    // History — messages in task
    // -----------------------------------------------------------------------

    @Test
    void historyContainsSentMessage() {
        tools.createChannel("a2a-gt-6", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();
        sendA2A("a2a-gt-6", "user", "the content", taskId);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("history", hasSize(1))
                .body("history[0].parts[0].text", equalTo("the content"))
                .body("history[0].role", equalTo("user"));
    }

    @Test
    void historyContainsAllMessagesInOrder() {
        tools.createChannel("a2a-gt-7", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();

        sendA2A("a2a-gt-7", "user", "request message", taskId);
        tools.sendMessage("a2a-gt-7", "agent", "status", "processing",
                taskId, null);
        tools.sendMessage("a2a-gt-7", "agent", "response", "final answer",
                taskId, null);

        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("history", hasSize(3))
                .body("history[0].role", equalTo("user"))
                .body("history[1].role", equalTo("agent"))
                .body("history[2].parts[0].text", equalTo("final answer"));
    }

    // -----------------------------------------------------------------------
    // Integration — task created via A2A send then retrieved
    // -----------------------------------------------------------------------

    @Test
    void taskCreatedViaSendIsRetrievableViaGet() {
        tools.createChannel("a2a-gt-8", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();

        // Create via POST
        String returnedId = sendA2A("a2a-gt-8", "orchestrator", "do the work", taskId);
        assertEquals(taskId, returnedId);

        // Retrieve via GET
        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("id", equalTo(taskId))
                .body("status.state", equalTo("submitted"))
                .body("contextId", equalTo("a2a-gt-8"))
                .body("history", hasSize(1));
    }

    // -----------------------------------------------------------------------
    // End-to-end — full A2A lifecycle
    // -----------------------------------------------------------------------

    @Test
    void e2eFullA2ALifecycleSubmittedWorkingCompleted() {
        tools.createChannel("a2a-e2e-gt-1", "Test", "APPEND", null);
        String taskId = UUID.randomUUID().toString();

        // 1. External orchestrator sends task via A2A
        sendA2A("a2a-e2e-gt-1", "orchestrator", "analyse this data", taskId);

        // 2. Orchestrator polls — task is submitted
        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("submitted"));

        // 3. Internal agent (MCP) picks up task, sends status update
        tools.sendMessage("a2a-e2e-gt-1", "analyst-agent", "status",
                "I'm working on it", taskId, null);

        // 4. Orchestrator polls again — task is working
        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("working"));

        // 5. Agent completes task
        tools.sendMessage("a2a-e2e-gt-1", "analyst-agent", "response",
                "Analysis complete: 42", taskId, null);

        // 6. Orchestrator polls — task is completed with full history
        given()
                .when().get(TASKS_PATH + taskId)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("completed"))
                .body("history", hasSize(3))
                .body("history[2].parts[0].text", equalTo("Analysis complete: 42"));
    }

    @Test
    void e2eAutoGeneratedTaskIdRoundtrip() {
        tools.createChannel("a2a-e2e-gt-2", "Test", "APPEND", null);

        // 1. Send without explicit taskId
        String generatedId = sendA2A("a2a-e2e-gt-2", "user", "work without explicit id", null);
        assertNotNull(generatedId);
        assertDoesNotThrow(() -> UUID.fromString(generatedId));

        // 2. Retrieve using the auto-generated id
        given()
                .when().get(TASKS_PATH + generatedId)
                .then()
                .statusCode(200)
                .body("id", equalTo(generatedId))
                .body("status.state", equalTo("submitted"));
    }
}
