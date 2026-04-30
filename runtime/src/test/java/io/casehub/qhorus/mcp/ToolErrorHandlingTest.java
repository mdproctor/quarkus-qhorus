package io.casehub.qhorus.mcp;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Verifies {@code @WrapBusinessError} error handling across the full MCP pipeline.
 *
 * <p>
 * {@code @WrapBusinessError({IllegalArgumentException.class, IllegalStateException.class})}
 * on {@link QhorusMcpTools} causes the quarkus-mcp-server interceptor to convert
 * those exceptions into {@link ToolCallException}, which the library then serialises
 * as {@code {"result": {"isError": true, "content": [{"type": "text", "text": "..."}]}}}
 * rather than a JSON-RPC {@code -32603} protocol error.
 *
 * <p>
 * The CDI-level unit tests verify the interceptor fires. The HTTP-level tests verify
 * the full pipeline: exception → ToolCallException → isError:true MCP response.
 *
 * <p>
 * Refs #56, ADR-0001.
 */
@QuarkusTest
class ToolErrorHandlingTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // CDI-level — interceptor wraps the exception
    // =========================================================================

    @Test
    @TestTransaction
    void pauseChannel_nonExistent_throwsToolCallException() {
        // @WrapBusinessError converts IllegalArgumentException → ToolCallException
        // at the CDI proxy level — visible before MCP protocol serialisation
        org.junit.jupiter.api.Assertions.assertThrows(
                ToolCallException.class,
                () -> tools.pauseChannel("wrap-test-nonexistent", "any-caller"));
    }

    @Test
    @TestTransaction
    void sendMessage_pausedChannel_throwsToolCallException() {
        // IllegalStateException (channel paused) also wrapped
        tools.createChannel("wrap-test-paused", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("wrap-test-paused", "inst-1", null, null, null, null, null);
        tools.pauseChannel("wrap-test-paused", "inst-1");

        org.junit.jupiter.api.Assertions.assertThrows(
                ToolCallException.class,
                () -> tools.sendMessage("wrap-test-paused", "inst-1", "status", "hello",
                        null, null, null, null));
    }

    // =========================================================================
    // HTTP-level — isError:true appears in MCP response (not a JSON-RPC error)
    // =========================================================================

    private String initMcpSession() {
        return given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":0,"method":"initialize",
                         "params":{"protocolVersion":"2024-11-05","capabilities":{},
                                   "clientInfo":{"name":"test","version":"1"}}}
                        """)
                .when().post("/mcp")
                .then().statusCode(200)
                .extract().header("Mcp-Session-Id");
    }

    private RequestSpecification mcp(final String sessionId) {
        return given()
                .contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId);
    }

    @Test
    void pauseChannel_nonExistentChannel_returnsIsErrorTrue() {
        final String sid = initMcpSession();

        mcp(sid)
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call",
                         "params":{"name":"pause_channel",
                                   "arguments":{"channel_name":"http-test-nonexistent-1234",
                                                "caller_instance_id":"any"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                // Tool-level error — NOT a JSON-RPC protocol error
                .body("error", nullValue())
                .body("result.isError", equalTo(true))
                .body("result.content[0].type", equalTo("text"))
                .body("result.content[0].text", containsString("http-test-nonexistent-1234"));
    }

    @Test
    void sendMessage_nonExistentChannel_returnsIsErrorTrue() {
        final String sid = initMcpSession();

        mcp(sid)
                .body("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call",
                         "params":{"name":"send_message",
                                   "arguments":{"channel_name":"http-test-nonexistent-5678",
                                                "sender":"agent-1",
                                                "type":"status",
                                                "content":"hello"}}}
                        """)
                .when().post("/mcp")
                .then()
                .statusCode(200)
                .body("error", nullValue())
                .body("result.isError", equalTo(true))
                .body("result.content[0].text", containsString("http-test-nonexistent-5678"));
    }
}
