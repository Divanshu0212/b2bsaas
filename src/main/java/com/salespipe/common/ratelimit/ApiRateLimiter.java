package com.salespipe.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Per-tenant API quota (T4.6), Redis-backed via Bucket4j — broadens the per-tenant
 * limiting the email-tracking endpoints already use to the authenticated business API.
 * Each tenant has its own bucket keyed on {@code api:<orgId>}, so one tenant exhausting
 * its budget cannot throttle another (the fairness invariant).
 *
 * <p>Reuses the raw byte-codec Lettuce connection wired for the tracking limiter
 * ({@code trackingRedisConnection}) — same connection coordinates, no second client.
 */
@Component
public class ApiRateLimiter {

    private final ProxyManager<byte[]> proxyManager;
    private final ApiRateLimitProperties properties;

    public ApiRateLimiter(
        StatefulRedisConnection<byte[], byte[]> trackingRedisConnection,
        ApiRateLimitProperties properties
    ) {
        this.proxyManager = LettuceBasedProxyManager.builderFor(trackingRedisConnection)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
            .build();
        this.properties = properties;
    }

    /** Tries to consume one token from {@code orgId}'s bucket; true if allowed, false if the tenant's budget is exhausted. */
    public boolean tryConsume(UUID orgId) {
        byte[] key = ("api:" + orgId).getBytes(StandardCharsets.UTF_8);
        Supplier<BucketConfiguration> config = () -> BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(properties.getCapacity())
                .refillGreedy(properties.getRefillPerMinute(), Duration.ofMinutes(1))
                .build())
            .build();
        return proxyManager.builder().build(key, config).tryConsume(1);
    }
}
