package com.salespipe.emailtracking.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salespipe.emailtracking.domain.Email;
import com.salespipe.emailtracking.domain.EmailEventType;
import com.salespipe.emailtracking.infra.EmailRepository;
import com.salespipe.emailtracking.security.RateLimiter;
import com.salespipe.emailtracking.security.TrackingSigner;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.producer.EventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated — see {@code SecurityConfig}'s {@code /emails/**} permitAll)
 * pixel-open and link-click tracking endpoints (overview §4/§6.1, plan T2.6).
 *
 * <p><b>Forgery-proofing invariant, load-bearing for every handler here</b>: signature
 * validation happens strictly before any database write or event emission. An invalid
 * {@code sig} short-circuits to a 204/404 response with zero side effects — this is what
 * makes tracking-id enumeration and forged opens/clicks unproductive for an attacker.
 */
@RestController
public class TrackingController {

    private static final Logger log = LoggerFactory.getLogger(TrackingController.class);

    // Well-known 1x1 transparent GIF, hardcoded per the plan ("hardcode the bytes, tiny
    // well-known GIF"). GIF89a header, 1x1 logical screen, global color table with a
    // single transparent color, no image data beyond the minimum required trailer.
    private static final byte[] TRANSPARENT_PIXEL_GIF = java.util.Base64.getDecoder().decode(
        "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBTAA7"
    );

    private final EmailRepository emailRepository;
    private final TrackingSigner trackingSigner;
    private final EventPublisher eventPublisher;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public TrackingController(EmailRepository emailRepository, TrackingSigner trackingSigner,
            EventPublisher eventPublisher, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.emailRepository = emailRepository;
        this.trackingSigner = trackingSigner;
        this.eventPublisher = eventPublisher;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/emails/{trackingId}/open", produces = MediaType.IMAGE_GIF_VALUE)
    public ResponseEntity<byte[]> open(@PathVariable UUID trackingId, @RequestParam String sig,
            HttpServletRequest request) {
        Optional<Email> validated = validate(trackingId, sig);
        if (validated.isEmpty()) {
            // No DB write, no event -- sig failed (or tracking_id unknown) before any
            // side effect. 204 rather than 404: 404 could leak "this tracking_id exists
            // but sig is wrong" vs "tracking_id doesn't exist" as distinguishable
            // signals to an attacker probing ids; 204 gives a uniform response either
            // way while still not rendering a broken-image icon in the mail client.
            return ResponseEntity.noContent().build();
        }
        Email email = validated.get();

        if (rateLimiter.tryConsume(email.getOrgId())) {
            emitAsync(email, EmailEventType.OPENED, request, null);
        } else {
            log.warn("Rate limit exceeded for org {} on pixel open (tracking_id={})", email.getOrgId(), trackingId);
        }
        // The pixel itself is still served even when the org's event-emission budget is
        // exhausted -- rate limiting protects the event/consumer pipeline from being
        // hammered, not the recipient's mail-client rendering experience.

        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
            .body(TRANSPARENT_PIXEL_GIF);
    }

    @GetMapping("/emails/{trackingId}/click")
    public ResponseEntity<Void> click(@PathVariable UUID trackingId, @RequestParam String sig,
            @RequestParam String url, HttpServletRequest request) {
        Optional<Email> validated = validate(trackingId, sig);
        if (validated.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        Email email = validated.get();

        URI target = safeRedirectTarget(url);
        if (target == null) {
            // Sig was valid but the redirect target itself is not a safe absolute
            // http(s) URL -- reject without redirecting (and without emitting a CLICKED
            // event for a click that isn't actually going anywhere legitimate).
            return ResponseEntity.badRequest().build();
        }

        if (rateLimiter.tryConsume(email.getOrgId())) {
            emitAsync(email, EmailEventType.CLICKED, request, url);
        } else {
            log.warn("Rate limit exceeded for org {} on link click (tracking_id={})", email.getOrgId(), trackingId);
        }

        return ResponseEntity.status(HttpStatus.FOUND)
            .location(target)
            .build();
    }

    /**
     * Validates {@code (trackingId, sig)} against the {@code emails} row, if any.
     * Returns empty for both "no such tracking_id" and "signature mismatch" -- callers
     * must not distinguish the two in their response (see {@link #open} javadoc).
     */
    private Optional<Email> validate(UUID trackingId, String sig) {
        Optional<Email> email = emailRepository.findByTrackingId(trackingId);
        if (email.isEmpty()) return Optional.empty();
        if (!trackingSigner.isValid(trackingId, email.get().getOrgId(), sig)) return Optional.empty();
        return email;
    }

    /**
     * Minimal open-redirect sanity check (plan T2.6: "validate url is a reasonable
     * absolute URL ... minimal sanity check, don't over-engineer"). Requires an
     * absolute {@code http}/{@code https} URL with a host -- rejects
     * {@code javascript:}, {@code data:}, relative paths, and other schemes that
     * could be used for XSS/local-file tricks via a redirect. Deliberately does NOT
     * allowlist specific hosts: the sig-validation gate is what makes this endpoint
     * trustworthy in the first place (a forged request can't reach this code at all),
     * so the URL check only needs to guard against a *legitimate* sender embedding a
     * dangerous scheme, not against a hostile third party.
     */
    private URI safeRedirectTarget(String url) {
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute()) return null;
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void emitAsync(Email email, EmailEventType eventType, HttpServletRequest request, String clickedUrl) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("emailId", email.getId().toString());
        if (email.getLeadId() != null) payload.put("leadId", email.getLeadId().toString());
        payload.put("eventType", eventType.name());
        payload.put("ip", clientIp(request));
        payload.put("userAgent", request.getHeader(HttpHeaders.USER_AGENT));
        if (clickedUrl != null) payload.put("url", clickedUrl);

        // aggregateId is leadId per the schema (email.event.received.json:
        // aggregateType const "lead"); fall back to emailId if this tracked email
        // isn't associated with a lead (e.g. deal-only sends) so the envelope is
        // always populated with *something* stable rather than left null.
        String aggregateId = email.getLeadId() != null ? email.getLeadId().toString() : email.getId().toString();

        EventEnvelope event = EventEnvelope.of(
            Topics.EMAIL_EVENT_RECEIVED,
            1,
            email.getOrgId().toString(),
            "lead",
            aggregateId,
            org.slf4j.MDC.get("trace_id"),
            payload
        );

        // Fire-and-forget: must not block the pixel/redirect response on the Kafka
        // round-trip (plan: "must not block the pixel response on the publish"). Errors
        // are logged, not thrown -- there is no request thread left waiting to surface
        // them to by the time the send completes.
        eventPublisher.publish(Topics.EMAIL_EVENT_RECEIVED, event)
            .exceptionally(ex -> {
                log.error("Failed to publish {} event for email {}", eventType, email.getId(), ex);
                return null;
            });
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
