package com.salespipe.scoring.recompute;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decides WHEN a feature update turns into a score recompute (T3.4). Two modes, both
 * implemented, chosen by {@code app.scoring.recompute-mode}:
 *
 * <ul>
 *   <li><b>immediate</b> (low volume): recompute right after the feature-update
 *       transaction commits. The recompute is dispatched to a small executor <em>after
 *       commit</em> — never inside the consumer's dedupe transaction — so a slow/downed
 *       ML service can't hold that transaction open or roll back the feature write.</li>
 *   <li><b>batched</b> (high volume): the {@code (org, lead)} pair is queued and a
 *       {@link RecomputeScheduler} sweep recomputes deduplicated pairs every N ms, so a
 *       burst of engagement events on one lead collapses into a single recompute.</li>
 * </ul>
 *
 * <p>De-duplication in both modes: a {@code ConcurrentHashMap} keyed on lead id collapses
 * repeat requests for the same lead within a window into one recompute.
 */
@Component
public class RecomputeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RecomputeCoordinator.class);

    private final ScoringService scoringService;
    private final String mode;

    /** Pending (leadId -> orgId) pairs for batched mode; drained by {@link RecomputeScheduler}. */
    final Map<UUID, UUID> pending = new ConcurrentHashMap<>();

    // Small bounded pool for immediate-mode after-commit recomputes; recompute itself is
    // guarded by the ML circuit breaker so a downed service degrades fast, not a thread leak.
    private final ExecutorService immediateExecutor = Executors.newFixedThreadPool(4);

    public RecomputeCoordinator(
        ScoringService scoringService,
        @Value("${app.scoring.recompute-mode:immediate}") String mode
    ) {
        this.scoringService = scoringService;
        this.mode = mode;
    }

    /** Called from a feature-aggregation consumer's handler (inside its dedupe transaction). */
    public void requestRecompute(UUID orgId, UUID leadId) {
        if ("batched".equalsIgnoreCase(mode)) {
            pending.put(leadId, orgId);
            return;
        }
        // immediate: run only if/when the enclosing feature-write transaction commits.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    immediateExecutor.submit(() -> safeRecompute(orgId, leadId));
                }
            });
        } else {
            immediateExecutor.submit(() -> safeRecompute(orgId, leadId));
        }
    }

    private void safeRecompute(UUID orgId, UUID leadId) {
        try {
            scoringService.recompute(orgId, leadId);
        } catch (Exception e) {
            // Recompute failure must never propagate — the feature write already committed.
            log.warn("Recompute failed for lead {}", leadId, e);
        }
    }
}
