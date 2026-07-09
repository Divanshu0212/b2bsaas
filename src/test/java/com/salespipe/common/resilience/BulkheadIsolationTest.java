package com.salespipe.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * T4.4: proves the bulkhead isolation invariant — a saturated {@code scoringService}
 * (slow ML) bulkhead does NOT consume the {@code dbCalls} budget, so DB-bound work stays
 * responsive while ML calls are all blocked. Same instance names and semantics as the
 * resilience4j config in application.yml.
 *
 * <p>Resilience4j-level test (not full stack): the isolation guarantee lives entirely in
 * having two independent, separately-sized bulkheads, which is exactly what this asserts.
 */
class BulkheadIsolationTest {

    @Test
    void saturatedScoringBulkheadDoesNotBlockDbCalls() throws Exception {
        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        // Mirror application.yml intent: small ML pool, larger DB pool, short wait so a
        // rejected acquire fails fast rather than queueing.
        Bulkhead scoring = registry.bulkhead("scoringService", BulkheadConfig.custom()
            .maxConcurrentCalls(4)
            .maxWaitDuration(Duration.ofMillis(50))
            .build());
        Bulkhead dbCalls = registry.bulkhead("dbCalls", BulkheadConfig.custom()
            .maxConcurrentCalls(32)
            .maxWaitDuration(Duration.ofMillis(50))
            .build());

        // Saturate the scoring bulkhead: fill every permit with a call that blocks until
        // released, so the ML pool is fully occupied for the duration of the test.
        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allAcquired = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> scoring.executeRunnable(() -> {
                allAcquired.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertThat(allAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        // Scoring pool is now full — a further ML call cannot acquire a permit.
        assertThat(scoring.tryAcquirePermission()).isFalse();

        // But DB-bound work is completely unaffected: dbCalls permits are all available.
        AtomicInteger dbCompleted = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            dbCalls.executeRunnable(dbCompleted::incrementAndGet);
        }
        assertThat(dbCompleted.get()).isEqualTo(10);

        release.countDown();
        pool.shutdownNow();
    }
}
