package com.salespipe.eventing.consumer;

/**
 * DLQ decision logic (retry-count-exhausted → DLQ), pulled out as a standalone,
 * dependency-free class so it's unit-testable in isolation from the Kafka/Resilience4j
 * plumbing (per the T2.4 acceptance criteria: "Unit test: DLQ decision logic ... in
 * isolation if that logic is cleanly separable").
 *
 * <p>{@code maxAttempts} mirrors {@code resilience4j.retry.instances.idempotentConsumer
 * .max-attempts} (see {@code application.yml}) — kept in sync manually since
 * Resilience4j's {@code RetryRegistry} config isn't itself unit-testable without a
 * Spring context.
 */
public final class RetryPolicy {

    private final int maxAttempts;

    public RetryPolicy(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * @param attemptsMade number of handler invocations that have already been made
     *                     (including the failing one being evaluated)
     * @return true once every configured attempt has been used up and the message
     *         should be routed to the DLQ instead of retried again
     */
    public boolean isExhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }
}
