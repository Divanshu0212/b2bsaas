package com.salespipe.emailtracking.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App-wide HMAC secret for email tracking tokens (see {@link TrackingSigner} javadoc for
 * why this is a single app-wide secret rather than a per-org one) plus the per-tenant
 * rate-limit budget for the public tracking/webhook endpoints (overview §6.1, plan T2.6).
 */
@ConfigurationProperties(prefix = "app.tracking")
public class TrackingProperties {

    private String secret;
    private int rateLimitCapacity = 30;
    private int rateLimitRefillPerMinute = 30;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public int getRateLimitCapacity() { return rateLimitCapacity; }
    public void setRateLimitCapacity(int v) { this.rateLimitCapacity = v; }
    public int getRateLimitRefillPerMinute() { return rateLimitRefillPerMinute; }
    public void setRateLimitRefillPerMinute(int v) { this.rateLimitRefillPerMinute = v; }
}
