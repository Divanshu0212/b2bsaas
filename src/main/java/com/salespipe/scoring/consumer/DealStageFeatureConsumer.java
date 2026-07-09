package com.salespipe.scoring.consumer;

import com.salespipe.eventing.Topics;
import com.salespipe.eventing.consumer.DlqPublisher;
import com.salespipe.eventing.consumer.IdempotentConsumer;
import com.salespipe.eventing.consumer.InboundEvent;
import com.salespipe.eventing.consumer.InboundEventNormalizer;
import com.salespipe.eventing.consumer.ProcessedEventRepository;
import com.salespipe.scoring.infra.FeatureLookup;
import com.salespipe.scoring.recompute.RecomputeCoordinator;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T3.1: recomputes {@code deal_velocity_days} from {@link Topics#DEAL_STAGE_CHANGED} for
 * the deal's lead. Independent group from {@code activity}/{@code notification}'s
 * deal-stage consumers, so all fire on the same event and each dedupes on its own
 * {@code processed_events} row.
 */
@Component
public class DealStageFeatureConsumer extends IdempotentConsumer {

    public static final String GROUP = "scoring-features-deal-stage";

    private final FeatureUpdater updater;
    private final FeatureLookup lookup;
    private final RecomputeCoordinator recompute;

    public DealStageFeatureConsumer(
        InboundEventNormalizer normalizer,
        ProcessedEventRepository processedEventRepository,
        DlqPublisher dlqPublisher,
        TransactionTemplate transactionTemplate,
        RetryRegistry retryRegistry,
        FeatureUpdater updater,
        FeatureLookup lookup,
        RecomputeCoordinator recompute
    ) {
        super(normalizer, processedEventRepository, dlqPublisher, transactionTemplate, retryRegistry);
        this.updater = updater;
        this.lookup = lookup;
        this.recompute = recompute;
    }

    @Override
    protected String consumerGroup() {
        return GROUP;
    }

    @Override
    protected void handle(InboundEvent event) {
        updater.applyDealStageEvent(event);
        UUID orgId = UUID.fromString(event.orgId());
        UUID leadId = lookup.leadIdForDeal(orgId, UUID.fromString(event.aggregateId()));
        if (leadId != null) {
            recompute.requestRecompute(orgId, leadId);
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
