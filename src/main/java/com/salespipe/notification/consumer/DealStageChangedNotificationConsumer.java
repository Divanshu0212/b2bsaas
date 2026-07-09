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
import com.salespipe.notification.infra.DealOwnerLookup;
import com.salespipe.notification.infra.NotificationRepository;
import com.salespipe.notification.infra.OwnerEmailLookup;
import com.salespipe.notification.infra.email.EmailMessage;
import com.salespipe.notification.infra.email.EmailProvider;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T2.7: minimal in-app notification stub for {@link Topics#DEAL_STAGE_CHANGED} — per
 * the plan, "Notification consumer stub for deal.stage.changed (full notifications
 * Phase 4-ish; minimal in-app {@code notifications} row now)". Notifies the deal's
 * owner (resolved via {@link DealOwnerLookup}) by inserting one {@code notifications}
 * row of {@code type = "DEAL_STAGE_CHANGED"} whose {@code payload} is the inbound
 * event's payload verbatim.
 *
 * <p>Extends {@link IdempotentConsumer} exactly like {@code
 * activity/consumer/DealStageChangedActivityConsumer} — same topic, independent
 * consumer group, so both fire on the same {@code deal.stage.changed} event and each
 * dedupes redelivery on its own {@code processed_events} row (overview §5's "Don't let
 * the notification consumer double-notify on redelivery" risk callout).
 *
 * <p>If the deal's owner cannot be resolved (deal not found, or {@code owner_id} unset)
 * the notification is still recorded with a {@code null} {@code user_id} rather than
 * failing the handler — an unassigned deal is a legitimate state, not a poison message.
 */
@Component
public class DealStageChangedNotificationConsumer extends IdempotentConsumer {

    public static final String GROUP = "notification-deal-stage-changed";
    public static final String TYPE_DEAL_STAGE_CHANGED = "DEAL_STAGE_CHANGED";

    private final NotificationRepository notifications;
    private final DealOwnerLookup dealOwnerLookup;
    private final OwnerEmailLookup ownerEmailLookup;
    private final EmailProvider emailProvider;
    private final ObjectMapper objectMapper;

    public DealStageChangedNotificationConsumer(
        InboundEventNormalizer normalizer,
        ProcessedEventRepository processedEventRepository,
        DlqPublisher dlqPublisher,
        TransactionTemplate transactionTemplate,
        RetryRegistry retryRegistry,
        NotificationRepository notifications,
        DealOwnerLookup dealOwnerLookup,
        OwnerEmailLookup ownerEmailLookup,
        EmailProvider emailProvider,
        ObjectMapper objectMapper
    ) {
        super(normalizer, processedEventRepository, dlqPublisher, transactionTemplate, retryRegistry);
        this.notifications = notifications;
        this.dealOwnerLookup = dealOwnerLookup;
        this.ownerEmailLookup = ownerEmailLookup;
        this.emailProvider = emailProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected void handle(InboundEvent event) {
        UUID orgId = UUID.fromString(event.orgId());
        UUID dealId = UUID.fromString(event.aggregateId());
        UUID ownerId = dealOwnerLookup.findOwnerId(orgId, dealId);

        JsonNode payload = event.payload() != null ? event.payload() : objectMapper.createObjectNode();

        Notification notification = new Notification(
            UUID.randomUUID(),
            orgId,
            ownerId,
            TYPE_DEAL_STAGE_CHANGED,
            payload.toString()
        );
        notifications.save(notification);

        // T4.4: email the deal owner, deduped per (group, event) so a redelivered event
        // never double-emails. Best-effort — no owner email / provider failure must not
        // fail the handler.
        String ownerEmail = ownerEmailLookup.findEmail(orgId, ownerId);
        if (ownerEmail != null) {
            emailProvider.send(
                new EmailMessage(ownerEmail, "Deal stage changed",
                    "A deal you own changed stage. Details: " + payload),
                GROUP + ":" + event.eventId());
        }
    }

    @KafkaListener(
        topics = Topics.DEAL_STAGE_CHANGED,
        groupId = GROUP,
        containerFactory = "eventKafkaListenerContainerFactory"
    )
    void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        consume(record, ack);
    }
}
