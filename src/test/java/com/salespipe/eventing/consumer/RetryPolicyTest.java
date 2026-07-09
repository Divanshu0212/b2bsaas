package com.salespipe.eventing.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the DLQ decision logic (T2.4 acceptance: "Unit test: DLQ decision
 * logic (retry-count-exhausted → DLQ) in isolation"). {@link RetryPolicy} has no Kafka/
 * Resilience4j/Spring dependency, so this runs with no container/context.
 */
class RetryPolicyTest {

    @Test
    void notExhaustedBeforeMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3);

        assertThat(policy.isExhausted(1)).isFalse();
        assertThat(policy.isExhausted(2)).isFalse();
    }

    @Test
    void exhaustedOnceAllAttemptsUsed() {
        RetryPolicy policy = new RetryPolicy(3);

        assertThat(policy.isExhausted(3)).isTrue();
    }

    @Test
    void exhaustedStaysTrueBeyondMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3);

        assertThat(policy.isExhausted(4)).isTrue();
    }

    @Test
    void singleAttemptPolicyIsExhaustedImmediately() {
        RetryPolicy policy = new RetryPolicy(1);

        assertThat(policy.isExhausted(1)).isTrue();
    }

    @Test
    void rejectsNonPositiveMaxAttempts() {
        assertThatThrownBy(() -> new RetryPolicy(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
