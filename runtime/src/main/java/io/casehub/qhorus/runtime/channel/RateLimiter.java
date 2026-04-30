package io.casehub.qhorus.runtime.channel;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory sliding-window rate limiter for channel message throughput.
 *
 * <p>
 * Tracks per-channel and per-instance send timestamps in a 60-second sliding window.
 * Windows are pruned on each access (request-path cleanup — no background scheduler).
 * State is in-memory only — limits reset on application restart.
 *
 * <p>
 * Thread safety: ConcurrentHashMap for map operations; per-key Deques are accessed
 * under synchronization on the Deque itself.
 */
@ApplicationScoped
public class RateLimiter {

    private static final long WINDOW_SECONDS = 60L;

    /** channelId → sliding window of send timestamps (all senders). */
    private final ConcurrentHashMap<UUID, Deque<Instant>> channelWindows = new ConcurrentHashMap<>();

    /** "channelId:sender" → sliding window of send timestamps for that sender. */
    private final ConcurrentHashMap<String, Deque<Instant>> instanceWindows = new ConcurrentHashMap<>();

    /**
     * Check per-channel and per-instance limits for the given send attempt.
     * Does NOT record the attempt — call {@link #recordSend} on success.
     *
     * @return null if allowed; a descriptive error message string if rate-limited
     */
    public String check(UUID channelId, String channelName, String sender, Integer limitPerChannel,
            Integer limitPerInstance) {
        Instant now = Instant.now();

        if (limitPerChannel != null) {
            Deque<Instant> window = channelWindows.computeIfAbsent(channelId, k -> new ArrayDeque<>());
            synchronized (window) {
                pruneOlderThan(window, now);
                if (window.size() >= limitPerChannel) {
                    return "Rate limit exceeded for channel '" + channelName
                            + "': max " + limitPerChannel + " messages per minute across all senders.";
                }
            }
        }

        if (limitPerInstance != null) {
            String key = channelId + ":" + sender;
            Deque<Instant> window = instanceWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (window) {
                pruneOlderThan(window, now);
                if (window.size() >= limitPerInstance) {
                    return "Rate limit exceeded for sender '" + sender
                            + "': max " + limitPerInstance + " messages per minute on channel '" + channelName + "'.";
                }
            }
        }

        return null; // allowed
    }

    /**
     * Record a successful send. Must be called after {@link #check} returns null
     * and the message has been persisted.
     */
    public void recordSend(UUID channelId, String sender, Integer limitPerChannel, Integer limitPerInstance) {
        Instant now = Instant.now();

        if (limitPerChannel != null) {
            Deque<Instant> window = channelWindows.computeIfAbsent(channelId, k -> new ArrayDeque<>());
            synchronized (window) {
                window.addLast(now);
            }
        }

        if (limitPerInstance != null) {
            String key = channelId + ":" + sender;
            Deque<Instant> window = instanceWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (window) {
                window.addLast(now);
            }
        }
    }

    private void pruneOlderThan(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minusSeconds(WINDOW_SECONDS);
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
            window.pollFirst();
        }
    }
}
