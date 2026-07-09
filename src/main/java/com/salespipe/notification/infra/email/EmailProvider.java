package com.salespipe.notification.infra.email;

/**
 * Outbound email port (T4.4). Two implementations: {@link SendGridEmailProvider} (real
 * client, behind circuit-breaker + retry + timeout) and {@link NoopEmailProvider} (logs
 * only, wired when {@code app.email.enabled=false} so tests / the cold-start demo run
 * without a SendGrid API key). The choice is made in {@link EmailProviderConfig}.
 */
public interface EmailProvider {

    /**
     * Sends {@code message}, deduped on {@code idempotencyKey} so a redelivered
     * notification event (which produces the same key) sends the email exactly once.
     * Implementations must not throw on a duplicate key — a duplicate is a successful
     * no-op, not an error the caller should retry or DLQ.
     */
    void send(EmailMessage message, String idempotencyKey);
}
