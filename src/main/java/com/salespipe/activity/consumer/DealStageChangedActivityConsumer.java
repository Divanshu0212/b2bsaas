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
 * T2.5: appends a {@code STAGE_CHANGE} timeline row for every {@link
 * Topics#DEAL_STAGE_CHANGED} event. This is the consumer the phase-2 plan's demo
 * script exercises directly ("Drag a deal -> within moments its activity timeline
 * shows a STAGE_CHANGE entry, written by a consumer, not the request thread").
 *
 * <p>{@code entity_type = "deal"}, {@code entity_id = aggregateId} (the envelope's
 * {@code aggregate_id}, which per overview §5's partition-key column is the
 * {@code deal_id}). The full payload (whatever the T2.7 producer includes — e.g.
 * from/to stage ids) is stored verbatim via {@link ActivityRecorder}.
 */
@Component
public class DealStageChangedActivityConsumer extends IdempotentConsumer {

    public static final String GROUP = "activity-deal-stage-changed";

    private final ActivityRecorder recorder;

    public DealStageChangedActivityConsumer(
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
        recorder.record(event, "deal", UUID.fromString(event.aggregateId()), ActivityType.STAGE_CHANGE);
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
