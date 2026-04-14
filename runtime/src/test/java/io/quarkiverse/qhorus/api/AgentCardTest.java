package io.quarkiverse.qhorus.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Agent Card tests at three levels:
 * - Unit: AgentCard model structure and field population
 * - Integration: @QuarkusTest full HTTP stack via RestAssured
 * - End-to-end: A2A compatibility validation (required fields, content-type, structure)
 */
@QuarkusTest
class AgentCardTest {

    static final String ENDPOINT = "/.well-known/agent-card.json";

    // -----------------------------------------------------------------------
    // Integration — HTTP-level (RestAssured against running QuarkusTest server)
    // -----------------------------------------------------------------------

    @Test
    void agentCardEndpointReturns200() {
        given()
                .when().get(ENDPOINT)
                .then().statusCode(200);
    }

    @Test
    void agentCardContentTypeIsJson() {
        given()
                .when().get(ENDPOINT)
                .then()
                .contentType("application/json");
    }

    @Test
    void agentCardNameIsPresent() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("name", not(emptyOrNullString()));
    }

    @Test
    void agentCardDescriptionIsPresent() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("description", not(emptyOrNullString()));
    }

    @Test
    void agentCardVersionIsPresent() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("version", not(emptyOrNullString()));
    }

    @Test
    void agentCardHasDefaultName() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("name", equalTo("Qhorus Agent Mesh"));
    }

    @Test
    void agentCardHasDefaultVersion() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("version", equalTo("1.0.0"));
    }

    // -----------------------------------------------------------------------
    // End-to-end — A2A compatibility (required structure for external discovery)
    // -----------------------------------------------------------------------

    @Test
    void agentCardSkillsArrayIsPresent() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("skills", notNullValue())
                .body("skills", not(empty()));
    }

    @Test
    void agentCardSkillsContainChannelMessaging() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("skills.id", hasItem("channel-messaging"));
    }

    @Test
    void agentCardSkillsContainSharedData() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("skills.id", hasItem("shared-data"));
    }

    @Test
    void agentCardSkillsContainPresence() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("skills.id", hasItem("presence"));
    }

    @Test
    void agentCardSkillsContainWaitForReply() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("skills.id", hasItem("wait-for-reply"));
    }

    @Test
    void agentCardEachSkillHasIdNameAndDescription() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("skills.id", everyItem(not(emptyOrNullString())))
                .body("skills.name", everyItem(not(emptyOrNullString())))
                .body("skills.description", everyItem(not(emptyOrNullString())));
    }

    @Test
    void agentCardCapabilitiesStreamingIsTrue() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("capabilities.streaming", equalTo(true));
    }

    @Test
    void agentCardCapabilitiesMcpIsTrue() {
        given()
                .when().get(ENDPOINT)
                .then()
                .body("capabilities.mcp", equalTo(true));
    }

    @Test
    void agentCardHasExactlyFourSkills() {
        // channel-messaging, shared-data, presence, wait-for-reply
        given()
                .when().get(ENDPOINT)
                .then()
                .body("skills", hasSize(4));
    }

    @Test
    void agentCardIsValidJsonObject() {
        // Full structure validation — all top-level keys present
        given()
                .when().get(ENDPOINT)
                .then()
                .body("$", hasKey("name"))
                .body("$", hasKey("description"))
                .body("$", hasKey("version"))
                .body("$", hasKey("skills"))
                .body("$", hasKey("capabilities"));
    }

    @Test
    void agentCardEndpointAllowsGetOnly() {
        // POST to the well-known URL should not be 200
        given()
                .when().post(ENDPOINT)
                .then()
                .statusCode(not(200));
    }

    // -----------------------------------------------------------------------
    // Unit — AgentCard model correctness (via HTTP round-trip as JSON)
    // -----------------------------------------------------------------------

    @Test
    void agentCardSkillChannelMessagingHasCorrectId() {
        io.restassured.response.Response response = given()
                .when().get(ENDPOINT)
                .then().extract().response();

        List<Map<String, Object>> skills = response.jsonPath().getList("skills");
        Map<String, Object> channelSkill = skills.stream()
                .filter(s -> "channel-messaging".equals(s.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("channel-messaging skill not found"));

        assertEquals("Channel Messaging", channelSkill.get("name"),
                "channel-messaging skill should have a human-readable name");
        assertNotNull(channelSkill.get("description"),
                "channel-messaging skill should have a description");
    }

    @Test
    void agentCardCapabilitiesObjectIsComplete() {
        io.restassured.response.Response response = given()
                .when().get(ENDPOINT)
                .then().extract().response();

        Map<String, Object> caps = response.jsonPath().getMap("capabilities");
        assertTrue((Boolean) caps.get("streaming"), "streaming must be true");
        assertTrue((Boolean) caps.get("mcp"), "mcp must be true");
        assertEquals(2, caps.size(), "capabilities should have exactly 2 fields");
    }

    @Test
    void agentCardUrlFieldIsPresent() {
        // url may be empty string if not configured — must not be null/absent
        given()
                .when().get(ENDPOINT)
                .then()
                .body("$", hasKey("url"));
    }

    @Test
    void agentCardResponseIsIdempotent() {
        // Calling the endpoint twice must return the same card
        String first = given().when().get(ENDPOINT).then().extract().body().asString();
        String second = given().when().get(ENDPOINT).then().extract().body().asString();
        assertEquals(first, second, "Agent Card must be deterministic across calls");
    }
}
