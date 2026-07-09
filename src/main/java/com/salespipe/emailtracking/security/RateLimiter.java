package com.salespipe.emailtracking.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Per-tenant Bucket4j rate limiting, Redis-backed (overview §6.1, plan T2.6), for the
 * public email tracking/webhook endpoints.
 *
 * <h2>Bucketing key: why per-org, derived after sig validation</h2>
 * These endpoints are unauthenticated (external mail clients / ESP webhook callers hit
 * them with no JWT, no tenant header — {@code SecurityConfig} explicitly
 * {@code permitAll()}s {@code /emails/**}), so there is no populated
 * {@code TenantContext} to key a bucket off of before the request is even parsed, unlike
 * every other rate-limited endpoint in a normal authenticated flow.
 *
 * <p>The plan (T2.6 task text) asks for per-tenant bucketing but also mandates
 * "invalid sig -> no DB write" as the forgery-proofing invariant — so org identity is
 * only knowable *after* looking up the {@code emails} row by {@code tracking_id} and
 * validating the signature against it. This class is therefore called from
 * {@code TrackingController}/{@code WebhookController} AFTER the {@code tracking_id} ->
 * {@code Email} lookup (a cheap indexed read, not a "write") but the bucket key used is
 * deliberately the resolved {@code org_id}, not the raw {@code tracking_id} or caller
 * IP: bucketing by {@code tracking_id} would let an attacker spray many distinct
 * (guessed or leaked) tracking ids to dodge the limit entirely, while {@code org_id}
 * bucketing means every open/click/webhook hit against the same tenant shares one
 * budget regardless of which specific email or IP it comes from — matching "per-tenant"
 * literally. IP-based limiting is intentionally not used as the primary key: many real
 * opens legitimately share IPs (corporate NAT, ESP image-proxying/relay services like
 * Gmail's proxy, which fetch pixels from a small set of Google IPs on behalf of many
 * distinct recipients/orgs), which would cause false-positive throttling unrelated to
 * any single tenant's traffic.
 *
 * <p>The one path where org identity is NOT yet known — a {@code tracking_id} that
 * doesn't resolve to any {@code emails} row at all (pure enumeration guess) — is not
 * separately bucketed here; it 404s immediately in the controller before reaching this
 * class, and the HMAC signature space (2^256 possibilities) already makes blind
 * enumeration infeasible regardless of rate limiting.
 */
@Component
public class RateLimiter {

    private final ProxyManager<byte[]> proxyManager;
    private final TrackingProperties properties;

    public RateLimiter(StatefulRedisConnection<byte[], byte[]> redisConnection, TrackingProperties properties) {
        this.proxyManager = LettuceBasedProxyManager.builderFor(redisConnection)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
            .build();
        this.properties = properties;
    }

    /**
     * Attempts to consume one token from the org's bucket. Returns {@code true} if the
     * request is allowed, {@code false} if the tenant's budget is exhausted.
     *
     * <p>Callers here ({@code TrackingController}/{@code WebhookController}) do NOT
     * turn a {@code false} return into an HTTP 429: the pixel/redirect response has
     * already been decided by signature validation at that point, and still gets
     * served normally (a real recipient's mail client shouldn't see a broken image or
     * a failed redirect just because their org is hot). What's gated on this result is
     * only the downstream {@code email.event.received} publish — an exhausted budget
     * means the event is silently dropped (logged) rather than the HTTP response being
     * degraded. This protects the Kafka/consumer pipeline from being hammered without
     * affecting the end-user-visible behavior of the tracking endpoints.
     */
    public boolean tryConsume(java.util.UUID orgId) {
        byte[] key = ("email-tracking:" + orgId).getBytes(StandardCharsets.UTF_8);
        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
            .addLimit(Bandwidth.builder()
                .capacity(properties.getRateLimitCapacity())
                .refillGreedy(properties.getRateLimitRefillPerMinute(), Duration.ofMinutes(1))
                .build())
            .build();
        return proxyManager.builder().build(key, configSupplier).tryConsume(1);
    }
}
