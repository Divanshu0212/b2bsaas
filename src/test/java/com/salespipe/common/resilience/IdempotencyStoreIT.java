package com.salespipe.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.support.PostgresRedisTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T4.4: the Redis-backed idempotency store must let the first caller through and reject
 * every later caller for the same key — the invariant that makes "duplicate email-send
 * with the same idempotency key sends once" hold.
 */
class IdempotencyStoreIT extends PostgresRedisTestBase {

    @Autowired
    IdempotencyStore store;

    @Test
    void firstCallerWinsAndDuplicatesAreRejected() {
        String key = "email:" + UUID.randomUUID();
        assertThat(store.firstSeen(key, Duration.ofHours(1))).isTrue();
        assertThat(store.firstSeen(key, Duration.ofHours(1))).isFalse();
        assertThat(store.firstSeen(key, Duration.ofHours(1))).isFalse();
    }

    @Test
    void distinctKeysAreIndependent() {
        assertThat(store.firstSeen("email:a-" + UUID.randomUUID(), Duration.ofHours(1))).isTrue();
        assertThat(store.firstSeen("email:b-" + UUID.randomUUID(), Duration.ofHours(1))).isTrue();
    }
}
