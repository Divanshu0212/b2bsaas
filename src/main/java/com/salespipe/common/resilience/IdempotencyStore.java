package com.salespipe.common.resilience;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed dedupe for client-retryable side effects — currently outbound email
 * (T4.4, overview §6.3 "Idempotency keys on client-retryable writes"). The first caller
 * for a given key wins; every later caller within the TTL is rejected, so a retried
 * email-send with the same idempotency key sends exactly once.
 *
 * <p>Uses Redis {@code SET key value NX PX ttl} (via {@code setIfAbsent}) — a single
 * atomic round-trip, so two concurrent callers cannot both observe "not seen" (unlike a
 * GET-then-SET). The TTL is mandatory: without it the dedupe set grows unbounded (the
 * exact "Idempotency-key store needs TTL/cleanup or it grows unbounded" gotcha from the
 * plan). Keys expire on their own; there is no separate cleanup job.
 */
@Component
public class IdempotencyStore {

    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redis;

    public IdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Atomically claims {@code key}. Returns {@code true} if this is the first time the
     * key has been seen (the caller should proceed with the side effect) or {@code false}
     * if it was already claimed within its TTL (the caller should skip — it's a duplicate).
     */
    public boolean firstSeen(String key, Duration ttl) {
        Boolean claimed = redis.opsForValue().setIfAbsent(KEY_PREFIX + key, "1", ttl);
        return Boolean.TRUE.equals(claimed);
    }
}
