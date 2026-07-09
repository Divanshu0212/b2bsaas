package com.salespipe.emailtracking.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.emailtracking.api.dto.SendGridWebhookEvent;
import com.salespipe.emailtracking.domain.Email;
import com.salespipe.emailtracking.domain.EmailEventType;
import com.salespipe.emailtracking.infra.EmailRepository;
import com.salespipe.emailtracking.security.RateLimiter;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider webhook ingestion for bounce/delivery events (overview §4/§6.1, plan T2.6).
 *
 * <h2>Provider shape chosen: SendGrid</h2>
 * SendGrid's Event Webhook POSTs a flat JSON array of event objects per request; SES's
 * equivalent is wrapped in an SNS notification envelope (a JSON string containing
 * further JSON, plus SNS subscription-confirmation handshake semantics). SendGrid's
 * shape is picked here as the canonical one — see {@link SendGridWebhookEvent} — since
 * it maps directly onto {@code List<Event>} without an extra unwrap/confirm step, which
 * keeps this stub focused on the mapping-to-{@code email.event.received} logic the task
 * is actually about rather than SNS handshake plumbing.
 *
 * <p>Only {@code bounce} is mapped to a domain event here (plan: "webhook ingestion
 * endpoint for provider bounce/delivery events ... maps to email.event.received
 * emission (e.g. BOUNCED)"); other SendGrid event types (delivered, open, click, spam
 * report, unsubscribe) are accepted (200'd) but not mapped to a Kafka event in this
 * stub — opens/clicks already have their own pixel/redirect-driven path via {@link
 * TrackingController}, and delivered/spam/unsubscribe aren't in this task's
 * {@code email_events.event_type} CHECK constraint (OPENED/CLICKED/BOUNCED only).
 *
 * <h2>Why no HMAC/sig gate here, unlike the pixel/click endpoints</h2>
 * SendGrid/SES webhooks don't carry this app's {@code tracking_sig} — that token is
 * embedded in URLs *we* generate for the recipient's mail client, not in provider
 * callback requests, which instead have their own provider-specific verification
 * (SendGrid: an Ed25519 request signature header; SES: SNS message signing). Wiring
 * either of those up is a real provider-integration concern, explicitly out of scope
 * here per the plan ("Provider integration is explicitly out of scope (Phase 4
 * concern)"). This endpoint instead validates that each event's {@code trackingId}
 * resolves to a real {@code emails} row (same "no DB write / no event for an unknown
 * id" discipline as the pixel endpoint) before doing anything with it — that's a much
 * weaker guarantee than real provider request-signing (it doesn't prove the request
 * came from the provider at all), which is exactly why it's documented as a gap rather
 * than presented as equivalent forgery-proofing to the HMAC-gated endpoints.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final EmailRepository emailRepository;
    private final EventPublisher eventPublisher;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public WebhookController(EmailRepository emailRepository, EventPublisher eventPublisher,
            RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.emailRepository = emailRepository;
        this.eventPublisher = eventPublisher;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/email")
    public ResponseEntity<Void> ingest(@RequestBody List<SendGridWebhookEvent> events) {
        for (SendGridWebhookEvent event : events) {
            processOne(event);
        }
        // Always 200/204 the whole batch, even if individual events were skipped
        // (unknown trackingId, unmapped event type, rate-limited) -- providers retry
        // non-2xx responses for the *entire* batch, which would cause the already-
        // processed events in the batch to be reprocessed too. Per-event outcomes are
        // logged instead.
        return ResponseEntity.noContent().build();
    }

    private void processOne(SendGridWebhookEvent event) {
        if (!"bounce".equalsIgnoreCase(event.getEvent())) {
            return;
        }
        if (event.getTrackingId() == null) {
            log.warn("Bounce webhook event missing trackingId, skipping: email={}", event.getEmail());
            return;
        }

        UUID trackingId;
        try {
            trackingId = UUID.fromString(event.getTrackingId());
        } catch (IllegalArgumentException e) {
            log.warn("Bounce webhook event has malformed trackingId, skipping: {}", event.getTrackingId());
            return;
        }

        Optional<Email> emailOpt = emailRepository.findByTrackingId(trackingId);
        if (emailOpt.isEmpty()) {
            log.warn("Bounce webhook event references unknown trackingId, skipping: {}", trackingId);
            return;
        }
        Email email = emailOpt.get();

        if (!rateLimiter.tryConsume(email.getOrgId())) {
            log.warn("Rate limit exceeded for org {} on webhook ingestion (tracking_id={})",
                email.getOrgId(), trackingId);
            return;
        }

        emitAsync(email, event);
    }

    private void emitAsync(Email email, SendGridWebhookEvent source) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("emailId", email.getId().toString());
        if (email.getLeadId() != null) payload.put("leadId", email.getLeadId().toString());
        payload.put("eventType", EmailEventType.BOUNCED.name());
        payload.put("ip", source.getIp());
        payload.put("userAgent", source.getUseragent());

        String aggregateId = email.getLeadId() != null ? email.getLeadId().toString() : email.getId().toString();

        EventEnvelope envelope = EventEnvelope.of(
            Topics.EMAIL_EVENT_RECEIVED,
            1,
            email.getOrgId().toString(),
            "lead",
            aggregateId,
            org.slf4j.MDC.get("trace_id"),
            payload
        );

        eventPublisher.publish(Topics.EMAIL_EVENT_RECEIVED, envelope)
            .exceptionally(ex -> {
                log.error("Failed to publish BOUNCED event for email {}", email.getId(), ex);
                return null;
            });
    }
}
