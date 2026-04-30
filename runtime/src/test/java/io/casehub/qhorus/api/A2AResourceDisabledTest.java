package io.casehub.qhorus.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #33 — A2A config guard and REST resource skeleton.
 *
 * <p>
 * Verifies that all A2A endpoints return HTTP 501 Not Implemented when
 * {@code casehub.qhorus.a2a.enabled=false} (the default). This protects existing
 * deployments from accidentally exposing A2A endpoints.
 *
 * <p>
 * The default test profile does NOT set {@code casehub.qhorus.a2a.enabled},
 * so these tests exercise the default-disabled behaviour.
 *
 * <p>
 * Refs #33, Epic #32.
 */
@QuarkusTest
class A2AResourceDisabledTest {

    // -----------------------------------------------------------------------
    // POST /a2a/message:send — disabled
    // -----------------------------------------------------------------------

    // Note: urlEncodingEnabled(false) is required because RestAssured encodes ':' to '%3A'
    // by default, which prevents matching the A2A path pattern "message:send".
    // RFC 3986 permits ':' in path segments after the first — it is not a reserved character
    // in that position, so the literal colon is correct and the encoding must be suppressed.

    @Test
    void sendEndpointReturns501WhenA2ADisabled() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"message\":{\"role\":\"user\",\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}],\"contextId\":\"ch\"}}")
                .when().post("/a2a/message:send")
                .then()
                .statusCode(501);
    }

    @Test
    void sendEndpointReturns501WithInformativeMessage() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/a2a/message:send")
                .then()
                .statusCode(501)
                .body(containsString("a2a"));
    }

    // -----------------------------------------------------------------------
    // GET /a2a/tasks/{id} — disabled
    // -----------------------------------------------------------------------

    @Test
    void tasksGetEndpointReturns501WhenA2ADisabled() {
        given()
                .when().get("/a2a/tasks/some-task-id")
                .then()
                .statusCode(501);
    }

    @Test
    void tasksGetEndpointReturns501WithInformativeMessage() {
        given()
                .when().get("/a2a/tasks/any-id")
                .then()
                .statusCode(501)
                .body(containsString("a2a"));
    }
}
