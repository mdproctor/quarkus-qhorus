package io.quarkiverse.qhorus.runtime.api;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.config.QhorusConfig;
import io.quarkiverse.qhorus.runtime.mcp.ReactiveQhorusMcpTools;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link A2AResource} — active only when
 * {@code quarkus.qhorus.reactive.enabled=true}.
 *
 * <p>
 * {@code POST /a2a/message:send} uses {@link ReactiveQhorusMcpTools} and returns
 * {@code Uni<Response>}. {@code GET /a2a/tasks/{id}} uses {@code @Blocking} with
 * the blocking message / channel services because {@code findAllByCorrelationId}
 * is not yet exposed via the reactive service layer.
 *
 * @see A2AResource
 */
@IfBuildProperty(name = "quarkus.qhorus.reactive.enabled", stringValue = "true")
@Path("/a2a")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ReactiveA2AResource {

    private static final Response A2A_DISABLED = Response
            .status(Response.Status.NOT_IMPLEMENTED)
            .entity("{\"error\":\"A2A endpoint is disabled. Set quarkus.qhorus.a2a.enabled=true to activate.\"}")
            .type(MediaType.APPLICATION_JSON)
            .build();

    @Inject
    QhorusConfig config;

    @Inject
    ReactiveQhorusMcpTools tools;

    @Inject
    MessageService messageService;

    @Inject
    ChannelService channelService;

    @POST
    @Path("/message:send")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> sendMessage(A2AResource.SendMessageRequest request) {
        if (!config.a2a().enabled()) {
            return Uni.createFrom().item(A2A_DISABLED);
        }

        if (request == null || request.message() == null) {
            return Uni.createFrom().item(error400("message is required"));
        }
        A2AResource.A2AMessage msg = request.message();

        if (msg.contextId() == null || msg.contextId().isBlank()) {
            return Uni.createFrom().item(error400("message.contextId (channel name) is required"));
        }
        if (msg.parts() == null || msg.parts().isEmpty()) {
            return Uni.createFrom().item(error400("message.parts must contain at least one text part"));
        }
        String text = msg.parts().stream()
                .filter(p -> "text".equals(p.kind()) && p.text() != null)
                .map(A2AResource.A2APart::text)
                .findFirst()
                .orElse(null);
        if (text == null) {
            return Uni.createFrom().item(
                    error400("message.parts must contain at least one text part with kind=text"));
        }

        String correlationId = (msg.taskId() != null && !msg.taskId().isBlank())
                ? msg.taskId()
                : UUID.randomUUID().toString();
        String sender = (msg.role() != null && !msg.role().isBlank()) ? msg.role() : "agent";

        final String finalCorrelationId = correlationId;
        final String finalContextId = msg.contextId();

        return tools.sendMessage(finalContextId, sender, "request", text,
                finalCorrelationId, null, null, null)
                .map(ignored -> {
                    A2AResource.Task task = new A2AResource.Task(
                            finalCorrelationId, finalContextId,
                            new A2AResource.TaskStatus("submitted"), null);
                    return Response.ok(new A2AResource.SendMessageResponse(task)).build();
                })
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(e -> error400(e.getMessage()))
                .onFailure(ToolCallException.class)
                .recoverWithItem(e -> {
                    String m = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    return error400(m);
                });
    }

    @GET
    @Path("/tasks/{id}")
    @Blocking
    public Uni<Response> getTask(@PathParam("id") String taskId) {
        if (!config.a2a().enabled()) {
            return Uni.createFrom().item(A2A_DISABLED);
        }

        return Uni.createFrom().item(() -> {
            List<Message> messages = messageService.findAllByCorrelationId(taskId);
            if (messages.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Task not found: " + taskId + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            Channel channel = channelService.findById(messages.get(0).channelId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Channel not found for task " + taskId));

            String state = deriveState(messages);

            List<A2AResource.A2AMessage> history = messages.stream()
                    .map(m -> new A2AResource.A2AMessage(
                            m.sender,
                            m.content != null
                                    ? List.of(new A2AResource.A2APart("text", m.content))
                                    : List.of(),
                            null,
                            m.correlationId,
                            channel.name))
                    .toList();

            return Response.ok(
                    new A2AResource.Task(taskId, channel.name,
                            new A2AResource.TaskStatus(state), history))
                    .build();
        });
    }

    private static String deriveState(List<Message> messages) {
        MessageType lastType = null;
        for (Message m : messages) {
            lastType = m.messageType;
        }
        if (lastType == null)
            return "submitted";
        return switch (lastType) {
            case RESPONSE, DONE -> "completed";
            case FAILURE, DECLINE -> "failed";
            case STATUS -> "working";
            default -> "submitted";
        };
    }

    private static Response error400(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + message + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
