package io.quarkiverse.qhorus.api.spi;

/**
 * Maps a Qhorus {@code instanceId} (session-scoped, e.g. {@code claudony-worker-abc123}) to
 * a ledger {@code actorId} (persona-scoped, e.g. {@code claude:analyst@v1}).
 *
 * <p>
 * Default implementation is a no-op identity function.
 * Replace with {@code @Alternative @Priority} to provide session-to-persona mapping.
 *
 * <p>
 * Refs #124.
 */
@FunctionalInterface
public interface InstanceActorIdProvider {

    /**
     * Resolve a Qhorus instanceId to a ledger actorId.
     * Return the instanceId unchanged if no mapping is known. Never return null.
     *
     * @param instanceId the Qhorus instance identifier (e.g. {@code message.sender})
     * @return the ledger actorId to use; never null
     */
    String resolve(String instanceId);
}
