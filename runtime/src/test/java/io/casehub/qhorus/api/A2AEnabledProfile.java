package io.casehub.qhorus.api;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile that enables the A2A endpoint for integration/e2e tests.
 * Used by @TestProfile on A2A test classes that exercise the enabled path.
 */
public class A2AEnabledProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();
        config.put("casehub.qhorus.a2a.enabled", "true");
        // Named 'qhorus' datasource — required when Quarkus restarts for this profile.
        // The reactive provider attempts to register a pool for the 'qhorus' PU on boot;
        // quarkus.datasource.qhorus.reactive=false suppresses that attempt.
        config.put("quarkus.datasource.qhorus.db-kind", "h2");
        config.put("quarkus.datasource.qhorus.jdbc.url", "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        config.put("quarkus.datasource.qhorus.username", "sa");
        config.put("quarkus.datasource.qhorus.password", "");
        config.put("quarkus.datasource.qhorus.reactive", "false");
        config.put("quarkus.hibernate-orm.qhorus.database.generation", "drop-and-create");
        return config;
    }
}
