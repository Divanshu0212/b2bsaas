package com.salespipe.notification.infra.email;

import com.salespipe.common.resilience.IdempotencyStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op {@link EmailProvider} used when {@code app.email.enabled=false} (T4.4). Logs the
 * message instead of calling SendGrid, so tests and the cold-start demo run without an
 * API key. Still honours the idempotency key — a duplicate is logged once as skipped —
 * so behaviour matches the real provider for the "sends once" assertion.
 */
public class NoopEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopEmailProvider.class);

    private final IdempotencyStore idempotencyStore;
    private final Duration ttl;

    public NoopEmailProvider(IdempotencyStore idempotencyStore, Duration ttl) {
        this.idempotencyStore = idempotencyStore;
        this.ttl = ttl;
    }

    @Override
    public void send(EmailMessage message, String idempotencyKey) {
        if (!idempotencyStore.firstSeen(idempotencyKey, ttl)) {
            log.debug("Email suppressed (duplicate idempotencyKey={})", idempotencyKey);
            return;
        }
        log.info("[email disabled] would send to={} subject=\"{}\" (idempotencyKey={})",
            message.toEmail(), message.subject(), idempotencyKey);
    }
}
