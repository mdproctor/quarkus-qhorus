package io.quarkiverse.qhorus.runtime.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.quarkiverse.qhorus.runtime.store.DataStore;
import io.quarkiverse.qhorus.runtime.store.query.DataQuery;

@ApplicationScoped
public class DataService {

    @Inject
    DataStore dataStore;

    /**
     * Store or update a shared data artefact.
     *
     * @param key human-readable key (unique)
     * @param description optional description (ignored on append chunks)
     * @param createdBy owner instance ID
     * @param content content to store or append
     * @param append if true, append to existing content; if false, create/overwrite
     * @param lastChunk if true, mark the artefact as complete
     */
    @Transactional
    public SharedData store(String key, String description, String createdBy,
            String content, boolean append, boolean lastChunk) {
        SharedData data = dataStore.findByKey(key).orElse(null);

        if (data == null || !append) {
            if (data == null) {
                data = new SharedData();
                data.key = key;
                data.createdBy = createdBy;
            }
            if (description != null) {
                data.description = description;
            }
            data.content = content;
        } else {
            // append chunk to existing content
            data.content = (data.content != null ? data.content : "") + content;
        }

        data.complete = lastChunk;
        data.sizeBytes = data.content != null ? data.content.length() : 0;
        dataStore.put(data);
        return data;
    }

    public Optional<SharedData> getByKey(String key) {
        return dataStore.findByKey(key);
    }

    public Optional<SharedData> getByUuid(UUID id) {
        return dataStore.find(id);
    }

    public List<SharedData> listAll() {
        return dataStore.scan(DataQuery.all());
    }

    @Transactional
    public void claim(UUID artefactId, UUID instanceId) {
        // Idempotent: network retries must not create duplicate claim rows
        if (ArtefactClaim.count("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId) == 0) {
            ArtefactClaim claim = new ArtefactClaim();
            claim.artefactId = artefactId;
            claim.instanceId = instanceId;
            dataStore.putClaim(claim);
        }
    }

    @Transactional
    public void release(UUID artefactId, UUID instanceId) {
        dataStore.deleteClaim(artefactId, instanceId);
    }

    /**
     * An artefact is GC-eligible when it is complete AND has no active claims.
     */
    public boolean isGcEligible(UUID artefactId) {
        return dataStore.find(artefactId)
                .filter(d -> d.complete)
                .map(d -> dataStore.countClaims(artefactId) == 0)
                .orElse(false);
    }
}
