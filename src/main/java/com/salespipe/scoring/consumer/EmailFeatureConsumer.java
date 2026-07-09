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
 * T3.1: maintains {@code lead_features} from {@link Topics#EMAIL_EVENT_RECEIVED}. One of
 * three feature-aggregation consumers (see {@link FeatureUpdater}) each binding a
 * distinct topic/group. Feature write shares the idempotent-consumer dedupe transaction,
 * then requests an async score recompute (T3.4 — recompute is the source of truth).
 */
@Component
public class EmailFeatureConsumer extends IdempotentConsumer {

    public static final String GROUP = "scoring-features-email";

    private final FeatureUpdater updater;
    private final RecomputeCoordinator recompute;

    public EmailFeatureConsumer(
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
        updater.applyEmailEvent(event);
        recompute.requestRecompute(UUID.fromString(event.orgId()), leadId(event));
    }

    private UUID leadId(InboundEvent event) {
        var node = event.payload() != null ? event.payload().path("leadId") : null;
        return node != null && node.isTextual()
            ? UUID.fromString(node.asText())
            : UUID.fromString(event.aggregateId());
    }

    @KafkaListener(
        topics = Topics.EMAIL_EVENT_RECEIVED,
        groupId = GROUP,
        containerFactory = "eventKafkaListenerContainerFactory"
    )
    void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        consume(record, ack);
    }
}
