package com.salespipe.activity.consumer;

import com.salespipe.activity.domain.ActivityType;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.consumer.DlqPublisher;
import com.salespipe.eventing.consumer.IdempotentConsumer;
import com.salespipe.eventing.consumer.InboundEvent;
import com.salespipe.eventing.consumer.InboundEventNormalizer;
import com.salespipe.eventing.consumer.ProcessedEventRepository;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T2.5: appends a timeline row for every {@link Topics#ACTIVITY_LOGGED} event.
 *
 * <p>Per overview §5, {@code activity.logged}'s producer is "multiple" — this is the
 * generic topic any module publishes to when it wants something appended to an
 * entity's timeline without writing to {@code activities} directly (e.g. a rep manually
 * logging a CALL/EMAIL/NOTE/MEETING from the CRM UI, or a future feature). The overview
 * §5 table lists only {@code notification} as a consumer, but the phase-2 T2.5 task
 * text explicitly requires this activity-module consumer too — treated as the more
 * authoritative/current source here (see {@code docs/plan/00-overview.md} §1 change
 * log's note that the overview intentionally records decisions, and T2.5's own task
 * text as the operative spec for this task).
 *
 * <p>Unlike {@link DealStageChangedActivityConsumer} / {@link
 * LeadCreatedActivityConsumer} (which impose one fixed {@code activity_type}), the
 * producer here is expected to already carry the intended {@code activity_type} in its
 * own payload (e.g. {@code {"activityType": "CALL", ...}}) since {@code activity.logged}
 * is explicitly the generic "log an activity" topic. Falls back to {@link
 * ActivityType#NOTE} — the plan's own generic bucket — if the field is absent, rather
 * than failing the message.
 *
 * <p>{@code entity_type}/{@code entity_id} come from the envelope's {@code
 * aggregate_type}/{@code aggregate_id} (partition key for this topic is {@code
 * entity_id} per overview §5 — the polymorphic entity the logged activity belongs to).
 */
@Component
public class ActivityLoggedConsumer extends IdempotentConsumer {

    public static final String GROUP = "activity-activity-logged";

    private final ActivityRecorder recorder;

    public ActivityLoggedConsumer(
        InboundEventNormalizer normalizer,
        ProcessedEventRepository processedEventRepository,
        DlqPublisher dlqPublisher,
        TransactionTemplate transactionTemplate,
        RetryRegistry retryRegistry,
        ActivityRecorder recorder
    ) {
        super(normalizer, processedEventRepository, dlqPublisher, transactionTemplate, retryRegistry);
        this.recorder = recorder;
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected void handle(InboundEvent event) {
        String entityType = event.aggregateType() != null ? event.aggregateType() : "unknown";
        String activityType = event.payload() != null
            ? event.payload().path("activityType").asText(ActivityType.NOTE)
            : ActivityType.NOTE;
        recorder.record(event, entityType, UUID.fromString(event.aggregateId()), activityType);
    }

    @KafkaListener(
        topics = Topics.ACTIVITY_LOGGED,
        groupId = GROUP,
        containerFactory = "eventKafkaListenerContainerFactory"
    )
    void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        consume(record, ack);
    }
}
