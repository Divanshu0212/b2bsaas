package com.salespipe.eventing.metrics;

import com.salespipe.common.metrics.ConsumerLagMetrics;
import com.salespipe.common.metrics.DlqDepthMetrics;
import com.salespipe.common.metrics.RelayLagMetrics;
import com.salespipe.eventing.EventingProperties;
import com.salespipe.eventing.Topics;
import com.salespipe.eventing.outbox.OutboxEventRepository;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T4.1: wires the {@code AdminClient}-backed sources for {@link DlqDepthMetrics} and
 * {@link ConsumerLagMetrics}, plus the outbox-repository-backed source for {@link
 * RelayLagMetrics}. Registered as {@code MeterBinder} beans — Spring Boot's actuator
 * autoconfiguration finds and binds every {@code MeterBinder} bean to the app's {@code
 * MeterRegistry} automatically (no manual {@code bindTo} call needed here).
 *
 * <p>Lives in {@code eventing.metrics}, not {@code common.metrics} (where the
 * {@code MeterBinder} classes themselves live): this class depends on {@code
 * eventing.Topics}/{@code EventingProperties}/{@code eventing.outbox.OutboxEventRepository},
 * and {@code eventing} already depends on {@code common} (e.g. {@code common.tenant}) —
 * putting this wiring in {@code common} would create a {@code common <-> eventing}
 * module cycle that Spring Modulith's {@code ApplicationModules.verify()} (see {@code
 * ModuleBoundaryTest}) rejects. One-way {@code eventing -> common.metrics} is fine.
 *
 * <p>Depth/lag queries are best-effort: a broker hiccup during a Prometheus scrape
 * should degrade to a stale/zero reading, not break the whole {@code
 * /actuator/prometheus} response (see per-method try/catch below) — same "never fail
 * the scrape" posture as {@link com.salespipe.eventing.schema.SchemaRegistrationRunner}
 * takes for startup schema registration.
 */
@Configuration
@EnableConfigurationProperties(EventingProperties.class)
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    /** Admin client timeout for each metrics-collection call — must stay well under the Prometheus scrape interval. */
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(5);

    @Bean(destroyMethod = "close")
    public AdminClient metricsAdminClient(EventingProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) ADMIN_TIMEOUT.toMillis());
        return AdminClient.create(config);
    }

    @Bean
    public DlqDepthMetrics dlqDepthMetrics(AdminClient metricsAdminClient) {
        List<String> dlqTopics = Topics.ALL.stream().map(Topics::dlqFor).toList();
        DlqDepthMetrics.DlqDepthSource source = dlqTopic -> endOffsetTotal(metricsAdminClient, dlqTopic);
        return new DlqDepthMetrics(dlqTopics, source);
    }

    @Bean
    public ConsumerLagMetrics consumerLagMetrics(AdminClient metricsAdminClient) {
        ConsumerLagMetrics.ConsumerLagSource source = () -> lagByGroup(metricsAdminClient);
        return new ConsumerLagMetrics(source);
    }

    @Bean
    public RelayLagMetrics relayLagMetrics(OutboxEventRepository outboxEventRepository) {
        RelayLagMetrics.RelayLagSource source = outboxEventRepository::countByPublishedFalse;
        return new RelayLagMetrics(source);
    }

    /**
     * DLQ topics have no dedicated consumer group tracking committed offsets in the
     * common case (they're drained manually/by a future replay tool), so "depth" here
     * is the raw end-offset sum across partitions — the total number of messages ever
     * routed to the DLQ topic. Falls back to {@code 0} (rather than throwing) if the
     * topic doesn't exist yet (no DLQ traffic so far) or the broker is unreachable.
     */
    private long endOffsetTotal(AdminClient admin, String topic) {
        try {
            Map<String, org.apache.kafka.clients.admin.TopicDescription> description =
                admin.describeTopics(List.of(topic)).allTopicNames().get(
                    ADMIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            List<TopicPartition> partitions = description.get(topic).partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();
            Map<TopicPartition, OffsetSpec> request = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResultInfo> offsets = admin.listOffsets(request).all().get(
                ADMIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return offsets.values().stream().mapToLong(ListOffsetsResultInfo::offset).sum();
        } catch (Exception e) {
            log.debug("Could not compute DLQ depth for topic {} (broker unreachable or topic not yet created): {}",
                topic, e.getMessage());
            return 0L;
        }
    }

    /**
     * Sum of (end-offset − committed-offset) across every partition each known
     * consumer group is subscribed to. Groups are discovered fresh on every call via
     * {@code listConsumerGroups} rather than hardcoded, so newly added {@code
     * @KafkaListener}s show up without touching this class.
     */
    private Map<String, Long> lagByGroup(AdminClient admin) {
        try {
            List<String> groupIds = admin.listConsumerGroups().all()
                .get(ADMIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .stream()
                .map(ConsumerGroupListing::groupId)
                .toList();

            Map<String, Long> result = new HashMap<>();
            for (String groupId : groupIds) {
                result.put(groupId, groupLag(admin, groupId));
            }
            return result;
        } catch (Exception e) {
            log.debug("Could not list consumer groups (broker unreachable): {}", e.getMessage());
            return Map.of();
        }
    }

    private long groupLag(AdminClient admin, String groupId) {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(ADMIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (committed.isEmpty()) {
                return 0L;
            }
            Map<TopicPartition, OffsetSpec> request = committed.keySet().stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResultInfo> endOffsets = admin.listOffsets(request).all()
                .get(ADMIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            long lag = 0L;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
                long endOffset = Optional.ofNullable(endOffsets.get(entry.getKey()))
                    .map(ListOffsetsResultInfo::offset)
                    .orElse(entry.getValue().offset());
                lag += Math.max(0L, endOffset - entry.getValue().offset());
            }
            return lag;
        } catch (Exception e) {
            log.debug("Could not compute lag for group {}: {}", groupId, e.getMessage());
            return 0L;
        }
    }
}
