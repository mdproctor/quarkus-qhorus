package io.quarkiverse.qhorus.examples.normativelayout;

import java.util.List;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.data.DataService;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;

/**
 * Canonical Layer 1 Secure Code Review scenario.
 * Two agents coordinate through 3 normative channels; no LLM required.
 * Importable by Claudony and CaseHub as the Layer 1 reference.
 */
public class SecureCodeReviewScenario {

    public final String caseId;
    public final String workChannel;
    public final String observeChannel;
    public final String oversightChannel;

    private final ChannelService channelService;
    private final InstanceService instanceService;
    private final MessageService messageService;
    private final DataService dataService;

    public SecureCodeReviewScenario(String caseId,
            ChannelService channelService,
            InstanceService instanceService,
            MessageService messageService,
            DataService dataService) {
        this.caseId = caseId;
        this.workChannel = "case-" + caseId + "/work";
        this.observeChannel = "case-" + caseId + "/observe";
        this.oversightChannel = "case-" + caseId + "/oversight";
        this.channelService = channelService;
        this.instanceService = instanceService;
        this.messageService = messageService;
        this.dataService = dataService;
    }

    /** Create the 3-channel normative layout for this case. */
    public void setupChannels() {
        channelService.create(workChannel, "Worker coordination", ChannelSemantic.APPEND,
                null, null, null, null, null, null);
        channelService.create(observeChannel, "Telemetry", ChannelSemantic.APPEND,
                null, null, null, null, null, "EVENT");
        channelService.create(oversightChannel, "Human governance", ChannelSemantic.APPEND,
                null, null, null, null, null, "QUERY,COMMAND");
    }

    public Channel workChannel() {
        return channelService.findByName(workChannel).orElseThrow();
    }

    public Channel observeChannel() {
        return channelService.findByName(observeChannel).orElseThrow();
    }

    public Channel oversightChannel() {
        return channelService.findByName(oversightChannel).orElseThrow();
    }

    /** Researcher registers, runs analysis, shares artefact, posts DONE. */
    public Message runResearcher(String correlationId) {
        instanceService.register("researcher-001", "Security analyst",
                List.of("security", "code-analysis"), null);

        Channel work = workChannel();
        Channel observe = observeChannel();

        messageService.send(work.id, "researcher-001", MessageType.STATUS,
                "Starting security analysis of AuthService.java", null, null);
        messageService.send(observe.id, "researcher-001", MessageType.EVENT,
                "{\"tool\":\"read_file\",\"path\":\"AuthService.java\"}", null, null);
        messageService.send(observe.id, "researcher-001", MessageType.EVENT,
                "{\"tool\":\"read_file\",\"path\":\"TokenRefreshService.java\"}", null, null);

        dataService.store("auth-analysis-v1-" + caseId, "Security analysis artefact", "researcher-001",
                "## Security Analysis\nFinding 1: SQL injection — HIGH\nFinding 2: Stale token — MEDIUM",
                false, true);

        return messageService.send(work.id, "researcher-001", MessageType.DONE,
                "Analysis complete. 3 findings. Report: shared-data:auth-analysis-v1", correlationId, null);
    }

    /**
     * Reviewer picks up DONE, queries researcher, receives RESPONSE, shares report, posts DONE.
     */
    public Message runReviewer(String queryCorrelationId, String doneCorrelationId) {
        instanceService.register("reviewer-001", "Security reviewer",
                List.of("review", "security"), null);

        Channel work = workChannel();

        messageService.send(work.id, "reviewer-001", MessageType.QUERY,
                "Finding #3: does TokenRefreshService.java:142 share the same root cause?",
                queryCorrelationId, null, null, "instance:researcher-001");

        messageService.send(work.id, "researcher-001", MessageType.RESPONSE,
                "Yes — same interpolated SQL pattern. One root cause, two surfaces.",
                queryCorrelationId, null);

        dataService.store("review-report-v1-" + caseId, "Code review report artefact", "reviewer-001",
                "## Code Review Report\nRoot cause A: SQL injection (CRITICAL)\nRoot cause B: Stale token (HIGH)",
                false, true);

        return messageService.send(work.id, "reviewer-001", MessageType.DONE,
                "Review complete. Final report: shared-data:review-report-v1", doneCorrelationId, null);
    }
}
