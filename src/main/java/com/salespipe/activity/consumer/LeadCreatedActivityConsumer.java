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
 * T2.5: appends a {@link ActivityType#LEAD_CREATED} timeline row for every {@link
 * Topics#LEAD_CREATED} event.
 *
 * <p>{@code lead.created} doesn't map cleanly onto the plan's illustrative
 * CALL|EMAIL|NOTE|MEETING|STAGE_CHANGE list — it's a system/lifecycle event, not a
 * sales activity a rep performed. See {@link ActivityType#LEAD_CREATED}'s javadoc for
 * the reasoning behind introducing a dedicated value rather than overloading
 * {@code NOTE}.
 *
 * <p>{@code entity_type = "lead"}, {@code entity_id = aggregateId} (the {@code lead_id}
 * per overview §5's partition-key column for this topic).
 */
@Component
public class LeadCreatedActivityConsumer extends IdempotentConsumer {

    public static final String GROUP = "activity-lead-created";

    private final ActivityRecorder recorder;

    public LeadCreatedActivityConsumer(
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
        recorder.record(event, "lead", UUID.fromString(event.aggregateId()), ActivityType.LEAD_CREATED);
    }

    @KafkaListener(
        topics = Topics.LEAD_CREATED,
        groupId = GROUP,
        containerFactory = "eventKafkaListenerContainerFactory"
    )
    void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        consume(record, ack);
    }
}
