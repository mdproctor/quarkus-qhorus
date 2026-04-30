package io.casehub.qhorus.runtime.api;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * Serves the A2A Agent Card at the standard well-known URL.
 * Makes every Qhorus deployment self-describing and discoverable
 * by A2A orchestrators, Claudony, and any ecosystem tool that reads agent cards.
 *
 * @see <a href="https://google.github.io/A2A/">Google A2A Protocol</a>
 */
@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)
@Path("/.well-known")
@ApplicationScoped
public class AgentCardResource {

    @Inject
    QhorusConfig config;

    @GET
    @Path("/agent-card.json")
    @Produces(MediaType.APPLICATION_JSON)
    public AgentCard getAgentCard() {
        QhorusConfig.AgentCard cfg = config.agentCard();
        return new AgentCard(
                cfg.name(),
                cfg.description(),
                cfg.url().orElse(""),
                cfg.version(),
                buildSkills(),
                new AgentCapabilities(true, true));
    }

    private List<AgentSkill> buildSkills() {
        return List.of(
                new AgentSkill(
                        "channel-messaging",
                        "Channel Messaging",
                        "Send and receive typed messages on named channels with declared semantics"
                                + " (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE)"),
                new AgentSkill(
                        "shared-data",
                        "Shared Data Store",
                        "Store and retrieve large artefacts by key with UUID references,"
                                + " claim/release lifecycle, and chunked streaming"),
                new AgentSkill(
                        "presence",
                        "Agent Presence",
                        "Register agents with capability tags and discover online peers"
                                + " by capability tag or role broadcast"),
                new AgentSkill(
                        "wait-for-reply",
                        "Correlation-based Wait",
                        "Wait for a response with a specific correlation ID —"
                                + " safe under concurrent requests via UUID-keyed CommitmentStore"));
    }

    // -----------------------------------------------------------------------
    // Model records — serialised directly to JSON by Jackson
    // -----------------------------------------------------------------------

    public record AgentCard(
            String name,
            String description,
            String url,
            String version,
            List<AgentSkill> skills,
            AgentCapabilities capabilities) {
    }

    public record AgentSkill(
            String id,
            String name,
            String description) {
    }

    public record AgentCapabilities(
            boolean streaming,
            boolean mcp) {
    }
}
