package com.salespipe.notification.infra.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * T4.4 email config (prefix {@code app.email}). {@code enabled=false} by default so the
 * app boots and tests run with no SendGrid account; flip it on plus supply {@code api-key}
 * and {@code from} to actually send.
 */
@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

    /** When false, a NoopEmailProvider is wired (logs instead of sending). */
    private boolean enabled = false;
    /** SendGrid API key. Never hard-coded — supplied via env (SENDGRID_API_KEY). */
    private String apiKey = "";
    /** Verified sender address SendGrid sends from. */
    private String from = "no-reply@salespipe.local";
    /** TTL (seconds) for the idempotency-key dedupe record. */
    private long idempotencyTtlSeconds = 86_400;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public long getIdempotencyTtlSeconds() { return idempotencyTtlSeconds; }
    public void setIdempotencyTtlSeconds(long idempotencyTtlSeconds) { this.idempotencyTtlSeconds = idempotencyTtlSeconds; }
}
