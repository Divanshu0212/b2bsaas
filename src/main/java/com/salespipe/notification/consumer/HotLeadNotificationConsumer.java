package com.salespipe.notification.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.consumer.DlqPublisher;
import com.salespipe.eventing.consumer.IdempotentConsumer;
import com.salespipe.eventing.consumer.InboundEvent;
import com.salespipe.eventing.consumer.InboundEventNormalizer;
import com.salespipe.eventing.consumer.ProcessedEventRepository;
import com.salespipe.notification.domain.Notification;
import com.salespipe.notification.infra.LeadOwnerLookup;
import com.salespipe.notification.infra.NotificationRepository;
import com.salespipe.notification.infra.OwnerEmailLookup;
import com.salespipe.notification.infra.email.EmailMessage;
import com.salespipe.notification.infra.email.EmailProvider;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T3.5: fires a "hot lead" notification when a {@link Topics#LEAD_SCORE_UPDATED} event
 * reports a score at/above {@code app.scoring.hot-lead-threshold}. Idempotent — the
 * {@code processed_events} row keyed on {@code (group, event_id)} means a redelivered
 * score-updated event never double-notifies (the T3.5 accept criterion: "crossing
 * threshold produces a notification (idempotently)").
 *
 * <p>Below-threshold events are consumed and dedupe-recorded but produce no notification.
 * The lead's owner is resolved via {@link LeadOwnerLookup}; an unassigned lead still gets
 * a {@code null}-user notification row rather than failing the handler.
 */
@Component
public class HotLeadNotificationConsumer extends IdempotentConsumer {

    public static final String GROUP = "notification-hot-lead";
    public static final String TYPE_HOT_LEAD = "HOT_LEAD";

    private final NotificationRepository notifications;
    private final LeadOwnerLookup leadOwnerLookup;
    private final OwnerEmailLookup ownerEmailLookup;
    private final EmailProvider emailProvider;
    private final ObjectMapper objectMapper;
    private final double threshold;

    public HotLeadNotificationConsumer(
        InboundEventNormalizer normalizer,
        ProcessedEventRepository processedEventRepository,
        DlqPublisher dlqPublisher,
        TransactionTemplate transactionTemplate,
        RetryRegistry retryRegistry,
        NotificationRepository notifications,
        LeadOwnerLookup leadOwnerLookup,
        OwnerEmailLookup ownerEmailLookup,
        EmailProvider emailProvider,
        ObjectMapper objectMapper,
        @Value("${app.scoring.hot-lead-threshold:0.75}") double threshold
    ) {
        super(normalizer, processedEventRepository, dlqPublisher, transactionTemplate, retryRegistry);
        this.notifications = notifications;
        this.leadOwnerLookup = leadOwnerLookup;
        this.ownerEmailLookup = ownerEmailLookup;
        this.emailProvider = emailProvider;
        this.objectMapper = objectMapper;
        this.threshold = threshold;
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected void handle(InboundEvent event) {
        JsonNode payload = event.payload() != null ? event.payload() : objectMapper.createObjectNode();
        double score = payload.path("score").asDouble(-1);
        if (score < threshold) {
            return; // consumed + deduped, but not hot enough to notify.
        }

        UUID orgId = UUID.fromString(event.orgId());
        UUID leadId = UUID.fromString(event.aggregateId());
        UUID ownerId = leadOwnerLookup.findOwnerId(orgId, leadId);

        notifications.save(new Notification(
            UUID.randomUUID(), orgId, ownerId, TYPE_HOT_LEAD, payload.toString()
        ));

        // T4.4: email the owner. Idempotency key is stable per (group, event) so a
        // redelivered score-updated event never double-emails. Best-effort — a missing
        // owner email or provider failure must not fail (and DLQ) the notification.
        String ownerEmail = ownerEmailLookup.findEmail(orgId, ownerId);
        if (ownerEmail != null) {
            emailProvider.send(
                new EmailMessage(ownerEmail, "Hot lead alert",
                    "A lead crossed the hot-lead threshold. Score payload: " + payload),
                GROUP + ":" + event.eventId());
        }
    }

    @KafkaListener(
        topics = Topics.LEAD_SCORE_UPDATED,
        groupId = GROUP,
        containerFactory = "eventKafkaListenerContainerFactory"
    )
    void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        consume(record, ack);
    }
}
