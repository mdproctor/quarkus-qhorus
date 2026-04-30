package io.casehub.qhorus.runtime.api;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link AgentCardResource} — active only when
 * {@code casehub.qhorus.reactive.enabled=true}.
 * Returns {@code Uni<Response>} so the Vert.x event loop is not blocked.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@Path("/.well-known")
@ApplicationScoped
public class ReactiveAgentCardResource {

    @Inject
    QhorusConfig config;

    @GET
    @Path("/agent-card.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> getAgentCard() {
        QhorusConfig.AgentCard cfg = config.agentCard();
        AgentCardResource.AgentCard card = new AgentCardResource.AgentCard(
                cfg.name(),
                cfg.description(),
                cfg.url().orElse(""),
                cfg.version(),
                buildSkills(),
                new AgentCardResource.AgentCapabilities(true, true));
        return Uni.createFrom().item(Response.ok(card).build());
    }

    private List<AgentCardResource.AgentSkill> buildSkills() {
        return List.of(
                new AgentCardResource.AgentSkill(
                        "channel-messaging",
                        "Channel Messaging",
                        "Send and receive typed messages on named channels with declared semantics"
                                + " (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE)"),
                new AgentCardResource.AgentSkill(
                        "shared-data",
                        "Shared Data Store",
                        "Store and retrieve large artefacts by key with UUID references,"
                                + " claim/release lifecycle, and chunked streaming"),
                new AgentCardResource.AgentSkill(
                        "presence",
                        "Agent Presence",
                        "Register agents with capability tags and discover online peers"
                                + " by capability tag or role broadcast"),
                new AgentCardResource.AgentSkill(
                        "wait-for-reply",
                        "Correlation-based Wait",
                        "Wait for a response with a specific correlation ID —"
                                + " safe under concurrent requests via UUID-keyed CommitmentStore"));
    }
}
