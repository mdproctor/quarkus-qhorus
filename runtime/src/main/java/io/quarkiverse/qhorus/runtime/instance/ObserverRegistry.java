package io.quarkiverse.qhorus.runtime.instance;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory registry of read-only observer subscriptions.
 *
 * <p>
 * Observers subscribe to one or more channels to receive {@code EVENT} messages
 * without creating an {@link Instance} record. They are invisible to other agents
 * and cannot send messages. Registrations are ephemeral — they reset on restart.
 */
@ApplicationScoped
public class ObserverRegistry {

    /** observerId → set of subscribed channel names */
    private final ConcurrentHashMap<String, Set<String>> registrations = new ConcurrentHashMap<>();

    /**
     * Register an observer for the given channels. Replaces any previous subscription
     * for the same observer ID.
     */
    public void register(String observerId, List<String> channelNames) {
        Set<String> channels = ConcurrentHashMap.newKeySet();
        channels.addAll(channelNames);
        registrations.put(observerId, channels);
    }

    /**
     * Remove the observer registration. Returns true if the observer was registered.
     */
    public boolean deregister(String observerId) {
        return registrations.remove(observerId) != null;
    }

    /**
     * Returns true if the given ID is a registered observer.
     */
    public boolean isObserver(String id) {
        return registrations.containsKey(id);
    }

    /**
     * Returns true if the observer is registered and subscribed to the given channel.
     */
    public boolean isSubscribedTo(String observerId, String channelName) {
        Set<String> channels = registrations.get(observerId);
        return channels != null && channels.contains(channelName);
    }

    /**
     * Returns the set of channel names the observer is subscribed to, or null if not registered.
     */
    public Set<String> getSubscriptions(String observerId) {
        return registrations.get(observerId);
    }
}
