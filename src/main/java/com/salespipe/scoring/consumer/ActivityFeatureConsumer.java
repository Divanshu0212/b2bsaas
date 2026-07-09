package com.salespipe.scoring.consumer;

import com.salespipe.eventing.Topics;
import com.salespipe.eventing.consumer.DlqPublisher;
import com.salespipe.eventing.consumer.IdempotentConsumer;
import com.salespipe.eventing.consumer.InboundEvent;
import com.salespipe.eventing.consumer.InboundEventNormalizer;
import com.salespipe.eventing.consumer.ProcessedEventRepository;
import com.salespipe.scoring.recompute.RecomputeCoordinator;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T3.1: maintains {@code lead_features} from {@link Topics#ACTIVITY_LOGGED} (lead-entity
 * activities only — see {@link FeatureUpdater#applyActivityEvent}). Requests an async
 * recompute after the feature write.
 */
@Component
public class ActivityFeatureConsumer extends IdempotentConsumer {

    public static final String GROUP = "scoring-features-activity";

    private final FeatureUpdater updater;
    private final RecomputeCoordinator recompute;

    public ActivityFeatureConsumer(
        InboundEventNormalizer normalizer,
        ProcessedEventRepository processedEventRepository,
        DlqPublisher dlqPublisher,
        TransactionTemplate transactionTemplate,
        RetryRegistry retryRegistry,
        FeatureUpdater updater,
        RecomputeCoordinator recompute
    ) {
        super(normalizer, processedEventRepository, dlqPublisher, transactionTemplate, retryRegistry);
        this.updater = updater;
        this.recompute = recompute;
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected void handle(InboundEvent event) {
        updater.applyActivityEvent(event);
        if ("lead".equals(text(event, "entityType"))) {
            recompute.requestRecompute(UUID.fromString(event.orgId()), UUID.fromString(text(event, "entityId")));
        }
    }

    private static String text(InboundEvent event, String field) {
        var n = event.payload() != null ? event.payload().path(field) : null;
        return n != null && n.isTextual() ? n.asText() : null;
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
