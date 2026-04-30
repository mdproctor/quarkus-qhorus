package io.casehub.qhorus.runtime.watchdog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.quarkus.scheduler.Scheduled;

/**
 * Drives periodic watchdog evaluation.
 * The scheduler always runs; the evaluation service checks the enabled flag
 * at the start of each run and returns immediately when disabled.
 */
@ApplicationScoped
public class WatchdogScheduler {

    private static final Logger LOG = Logger.getLogger(WatchdogScheduler.class);

    @Inject
    QhorusConfig config;

    @Inject
    WatchdogEvaluationService evaluationService;

    @Scheduled(every = "${casehub.qhorus.watchdog.check-interval-seconds:60}s", identity = "watchdog-evaluation")
    public void evaluate() {
        if (!config.watchdog().enabled()) {
            return;
        }
        LOG.debug("Watchdog evaluation triggered");
        evaluationService.evaluateAll();
    }
}
