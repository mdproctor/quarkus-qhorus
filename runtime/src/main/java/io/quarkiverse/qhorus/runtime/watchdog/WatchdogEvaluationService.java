package io.quarkiverse.qhorus.runtime.watchdog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.config.QhorusConfig;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageService;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.message.PendingReply;
import io.quarkiverse.qhorus.runtime.store.WatchdogStore;
import io.quarkiverse.qhorus.runtime.store.query.WatchdogQuery;

/**
 * Evaluates all registered watchdog conditions and fires alert messages
 * to their notification channels when conditions are met.
 *
 * <p>
 * Called by the Quarkus Scheduler at a configurable interval, and directly
 * by tests via injection for deterministic testing without scheduler timing.
 *
 * <p>
 * Debounce rule: a watchdog does not re-fire if {@code lastFiredAt} is within
 * {@code thresholdSeconds} of now (or within 1 second for threshold=0 conditions).
 */
@ApplicationScoped
public class WatchdogEvaluationService {

    @Inject
    QhorusConfig config;

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Inject
    WatchdogStore watchdogStore;

    /** Evaluate all registered watchdogs and fire alerts for met conditions. */
    @Transactional
    public void evaluateAll() {
        if (!config.watchdog().enabled()) {
            return;
        }

        List<Watchdog> watchdogs = watchdogStore.scan(WatchdogQuery.all());
        Instant now = Instant.now();

        for (Watchdog w : watchdogs) {
            if (isDebounced(w, now)) {
                continue;
            }
            boolean fired = switch (w.conditionType) {
                case "BARRIER_STUCK" -> evaluateBarrierStuck(w, now);
                case "APPROVAL_PENDING" -> evaluateApprovalPending(w, now);
                case "AGENT_STALE" -> evaluateAgentStale(w, now);
                case "CHANNEL_IDLE" -> evaluateChannelIdle(w, now);
                case "QUEUE_DEPTH" -> evaluateQueueDepth(w, now);
                default -> false;
            };
            if (fired) {
                w.lastFiredAt = now;
            }
        }
    }

    /**
     * Debounce: skip if lastFiredAt is recent relative to threshold.
     * For threshold=0 conditions, use a 1-second window to prevent double-fire
     * within the same evaluation cycle.
     */
    private boolean isDebounced(Watchdog w, Instant now) {
        if (w.lastFiredAt == null) {
            return false;
        }
        long windowSeconds = w.thresholdSeconds != null && w.thresholdSeconds > 0
                ? w.thresholdSeconds
                : 1L;
        return w.lastFiredAt.isAfter(now.minusSeconds(windowSeconds));
    }

    private boolean evaluateBarrierStuck(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds != null ? w.thresholdSeconds : 300;
        Instant cutoff = now.minusSeconds(threshold);

        // Find BARRIER channels matching target (or all if target is "*")
        List<Channel> barriers = channelService.listAll().stream()
                .filter(ch -> ch.semantic == ChannelSemantic.BARRIER)
                .filter(ch -> "*".equals(w.targetName) || ch.name.equals(w.targetName))
                .filter(ch -> ch.lastActivityAt.isBefore(cutoff) || threshold == 0)
                .toList();

        boolean fired = false;
        for (Channel ch : barriers) {
            // Check if barrier is actually stuck (not yet released)
            List<String> required = ch.barrierContributors != null
                    ? List.of(ch.barrierContributors.split(","))
                    : List.of();
            if (required.isEmpty())
                continue;

            @SuppressWarnings("unchecked")
            List<String> written = Message.getEntityManager()
                    .createQuery("SELECT DISTINCT m.sender FROM Message m "
                            + "WHERE m.channelId = ?1 AND m.messageType != ?2")
                    .setParameter(1, ch.id)
                    .setParameter(2, MessageType.EVENT)
                    .getResultList();

            boolean allPresent = required.stream()
                    .map(String::trim)
                    .filter(r -> !r.isBlank())
                    .allMatch(written::contains);

            if (!allPresent) {
                fireAlert(w, "BARRIER_STUCK: channel='" + ch.name + "' waiting for contributors");
                fired = true;
            }
        }
        return fired;
    }

    private boolean evaluateApprovalPending(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds != null ? w.thresholdSeconds : 300;
        Instant cutoff = now.minusSeconds(threshold);

        List<PendingReply> pending = PendingReply.<PendingReply> listAll().stream()
                .filter(pr -> pr.expiresAt != null
                        && (threshold == 0 || pr.expiresAt.isBefore(cutoff.plusSeconds(pr.expiresAt.getEpochSecond()))))
                .toList();

        // Simplified: fire if any PendingReply exists older than threshold
        List<PendingReply> old = PendingReply.<PendingReply> listAll().stream()
                .filter(pr -> pr.expiresAt != null)
                .filter(pr -> threshold == 0 || pr.expiresAt.isBefore(now.plusSeconds(60 - threshold)))
                .toList();

        if (!old.isEmpty()) {
            fireAlert(w, "APPROVAL_PENDING: " + old.size() + " approval(s) awaiting human response");
            return true;
        }
        return false;
    }

    private boolean evaluateAgentStale(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds != null ? w.thresholdSeconds : 300;
        Instant cutoff = now.minusSeconds(threshold);

        long stale = io.quarkiverse.qhorus.runtime.instance.Instance
                .count("status = 'stale' AND lastSeen < ?1", cutoff);

        if (stale > 0 || threshold == 0) {
            // Re-check with threshold=0 means: fire if any stale agents exist
            long count = io.quarkiverse.qhorus.runtime.instance.Instance
                    .count("status = 'stale'");
            if (count > 0) {
                fireAlert(w, "AGENT_STALE: " + count + " stale agent(s) detected");
                return true;
            }
        }
        return false;
    }

    private boolean evaluateChannelIdle(Watchdog w, Instant now) {
        int threshold = w.thresholdSeconds != null ? w.thresholdSeconds : 600;
        Instant cutoff = now.minusSeconds(threshold);

        List<Channel> idle = channelService.listAll().stream()
                .filter(ch -> "*".equals(w.targetName) || ch.name.equals(w.targetName))
                .filter(ch -> threshold == 0 || ch.lastActivityAt.isBefore(cutoff))
                .toList();

        if (!idle.isEmpty()) {
            String names = idle.stream().map(ch -> ch.name).limit(3)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            fireAlert(w, "CHANNEL_IDLE: channel(s) idle > " + threshold + "s: " + names);
            return true;
        }
        return false;
    }

    private boolean evaluateQueueDepth(Watchdog w, Instant now) {
        int threshold = w.thresholdCount != null ? w.thresholdCount : 100;

        List<Channel> channels = channelService.listAll().stream()
                .filter(ch -> "*".equals(w.targetName) || ch.name.equals(w.targetName))
                .toList();

        for (Channel ch : channels) {
            long count = Message.count(
                    "channelId = ?1 AND messageType != ?2", ch.id, MessageType.EVENT);
            if (count >= threshold) {
                fireAlert(w, "QUEUE_DEPTH: channel='" + ch.name + "' has " + count
                        + " messages (threshold=" + threshold + ")");
                return true;
            }
        }
        return false;
    }

    private void fireAlert(Watchdog w, String alertContent) {
        Optional<Channel> notifChannel = channelService.findByName(w.notificationChannel);
        if (notifChannel.isEmpty()) {
            return; // notification channel doesn't exist — skip silently
        }
        messageService.send(notifChannel.get().id, "watchdog", MessageType.STATUS,
                alertContent, null, null, null, null);
    }
}
