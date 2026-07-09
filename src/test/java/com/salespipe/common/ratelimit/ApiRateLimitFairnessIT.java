package com.salespipe.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.support.PostgresRedisTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * T4.6: proves per-tenant rate-limit fairness — one tenant exhausting its budget does NOT
 * throttle another. Small capacity via test properties so the limit trips deterministically.
 */
@TestPropertySource(properties = {
    "app.api-rate-limit.capacity=5",
    "app.api-rate-limit.refill-per-minute=5"
})
class ApiRateLimitFairnessIT extends PostgresRedisTestBase {

    @Autowired
    ApiRateLimiter rateLimiter;

    @Test
    void oneTenantBurstDoesNotThrottleAnother() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Tenant A spends its whole budget.
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryConsume(tenantA)).as("A request %d", i).isTrue();
        }
        // A is now throttled.
        assertThat(rateLimiter.tryConsume(tenantA)).as("A over budget").isFalse();

        // B is untouched — its bucket is full and independent.
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryConsume(tenantB)).as("B request %d", i).isTrue();
        }
    }
}
