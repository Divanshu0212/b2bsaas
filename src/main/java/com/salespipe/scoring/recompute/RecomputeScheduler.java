package com.salespipe.scoring.recompute;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Batched-mode recompute sweep (T3.4). Only active when {@code
 * app.scoring.recompute-mode=batched}; in the default {@code immediate} mode this bean is
 * not created and {@link RecomputeCoordinator} recomputes per-event after commit instead.
 *
 * <p>Every {@code app.scoring.recompute-batch-delay-ms} the pending map (de-duplicated
 * per lead) is drained and each lead recomputed once — collapsing a burst of engagement
 * events on one lead into a single ML call.
 */
@Component
@ConditionalOnProperty(name = "app.scoring.recompute-mode", havingValue = "batched")
public class RecomputeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecomputeScheduler.class);

    private final RecomputeCoordinator coordinator;
    private final ScoringService scoringService;

    public RecomputeScheduler(RecomputeCoordinator coordinator, ScoringService scoringService) {
        this.coordinator = coordinator;
        this.scoringService = scoringService;
    }

    @Scheduled(fixedDelayString = "${app.scoring.recompute-batch-delay-ms:60000}")
    public void sweep() {
        if (coordinator.pending.isEmpty()) {
            return;
        }
        // Snapshot-and-clear so events arriving mid-sweep queue for the next tick.
        for (Map.Entry<UUID, UUID> e : Map.copyOf(coordinator.pending).entrySet()) {
            UUID leadId = e.getKey();
            UUID orgId = coordinator.pending.remove(leadId);
            if (orgId == null) {
                continue;
            }
            try {
                scoringService.recompute(orgId, leadId);
            } catch (Exception ex) {
                log.warn("Batched recompute failed for lead {}", leadId, ex);
            }
        }
    }

    /**
     * Enables Spring scheduling only in batched mode (mirrors {@code
     * PollingRelay.PollingRelaySchedulingConfig}'s pattern — {@code @EnableScheduling} on
     * a dedicated conditional {@code @Configuration} rather than app-wide, so nothing
     * scheduled runs in the default immediate mode).
     */
    @Configuration
    @ConditionalOnProperty(name = "app.scoring.recompute-mode", havingValue = "batched")
    @EnableScheduling
    static class RecomputeSchedulingConfig {}
}
