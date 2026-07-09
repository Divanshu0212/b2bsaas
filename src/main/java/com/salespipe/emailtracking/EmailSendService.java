package com.salespipe.emailtracking;

import com.salespipe.emailtracking.domain.Email;
import com.salespipe.emailtracking.infra.EmailRepository;
import com.salespipe.emailtracking.security.TrackingSigner;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbound tracked-email row creation stub (plan T2.6: "Outbound send stub — provider
 * integration is Phase 4 resilience concern"). Given org/deal/lead context, creates the
 * {@code emails} row with a freshly generated {@code tracking_id}/{@code tracking_sig}
 * and {@code sent_at = now()}. Actually dispatching the email via a provider (SendGrid/
 * SES/etc, with the circuit breaker called out in overview §6.3) is out of scope here.
 *
 * <h2>Outbox vs. direct write for this insert</h2>
 * The plan is explicit that the *inbound* tracking endpoints (open/click/webhook) emit
 * {@code email.event.received} async and directly via {@code EventPublisher}, not
 * through the outbox — those aren't originating a business transaction, they're
 * reacting to an external HTTP hit. This send-stub insert is different: it IS a business
 * write initiated by our own code (composing/sending an email), which is exactly the
 * shape the outbox pattern exists for (write + event atomically, same transaction).
 *
 * <p>That said, this method deliberately does NOT go through {@code OutboxRecorder} here.
 * Reasoning: there is no consumer anywhere in this codebase (checked activity/, scoring/,
 * notification/) that reacts to "an email was sent" as a domain event — the only event
 * this task's topic (`email.event.received`) cares about is OPENED/CLICKED/BOUNCED,
 * i.e. things that happen *after* send, driven by the tracking endpoints, not send
 * itself. Recording an outbox row with no consumer and no defined event type/schema
 * would be speculative plumbing with nothing to route to. If a future phase needs an
 * `email.sent` event (e.g. for a "sent emails" activity-timeline entry), that's a new
 * topic + schema + consumer to add deliberately, not something to half-wire here. The
 * row write itself is still atomic via the surrounding `@Transactional` — just without
 * an accompanying outbox event, since there's nothing downstream to notify yet.
 */
@Service
public class EmailSendService {

    private final EmailRepository emailRepository;
    private final TrackingSigner trackingSigner;

    public EmailSendService(EmailRepository emailRepository, TrackingSigner trackingSigner) {
        this.emailRepository = emailRepository;
        this.trackingSigner = trackingSigner;
    }

    @Transactional
    public Email createTrackedEmail(UUID orgId, UUID dealId, UUID leadId, String subject) {
        UUID trackingId = trackingSigner.newTrackingId();
        String sig = trackingSigner.sign(trackingId, orgId);
        Email email = new Email(UUID.randomUUID(), orgId, dealId, leadId, subject, trackingId, sig);
        return emailRepository.save(email);
    }
}
