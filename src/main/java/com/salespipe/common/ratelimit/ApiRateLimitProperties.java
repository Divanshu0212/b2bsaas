package com.salespipe.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T4.6 per-tenant API quota config (prefix {@code app.api-rate-limit}). Separate budget
 * from the email-tracking pixel limiter — this one covers authenticated business API calls.
 */
@ConfigurationProperties(prefix = "app.api-rate-limit")
public class ApiRateLimitProperties {

    /** Master switch; off disables the filter entirely (e.g. for load tests measuring raw throughput). */
    private boolean enabled = true;
    /** Bucket capacity (burst) per tenant. */
    private long capacity = 100;
    /** Tokens refilled per minute per tenant. */
    private long refillPerMinute = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }

    public long getRefillPerMinute() { return refillPerMinute; }
    public void setRefillPerMinute(long refillPerMinute) { this.refillPerMinute = refillPerMinute; }
}
