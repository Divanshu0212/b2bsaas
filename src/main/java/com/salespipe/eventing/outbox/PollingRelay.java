package com.salespipe.eventing.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.producer.EventPublisher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fallback outbox relay for environments without Debezium (T2.3, plan
 * "Fallback: a {@code @Scheduled} polling relay ... behind a config flag"). Only
 * active when {@code app.eventing.relay-mode=polling} (default is {@code cdc}, in
 * which case Debezium reads the WAL and this bean does not even get created — see
 * {@link ConditionalOnProperty}).
 *
 * <p>Reads {@code outbox_events} rows directly via {@link JdbcTemplate} rather than
 * through JPA/{@link OutboxEventRepository} — same reasoning as documented on
 * {@link OutboxEventRepository}: this is a relay reading the table from outside the
 * ORM, not a tenant-scoped domain read, so the request-scoped Hibernate tenant filter
 * (which only applies inside an HTTP request, see {@code TenantFilterAspect}) doesn't
 * apply and shouldn't be worked around.
 *
 * <p>Batch read + publish + mark, in {@code created_at} order, using the partial
 * index {@code idx_outbox_events_unpublished} from
 * {@code V3__outbox_published_column.sql}. Kept intentionally simple per the task:
 * no complex batching/backpressure beyond a bounded page size.
 */
@Component
@ConditionalOnProperty(name = "app.eventing.relay-mode", havingValue = "polling")
public class PollingRelay {

    /**
     * Enables {@code @Scheduled} processing, scoped to only when the polling relay
     * itself is active — avoids turning on scheduling infrastructure app-wide (which
     * would affect unrelated future {@code @Scheduled} beans' assumptions) just for
     * this one fallback path. Separate {@code @Configuration} class (rather than
     * putting {@code @EnableScheduling} directly on {@link PollingRelay}) because
     * {@code @EnableScheduling} is documented/intended for configuration classes.
     */
    @Configuration
    @ConditionalOnProperty(name = "app.eventing.relay-mode", havingValue = "polling")
    @EnableScheduling
    public static class PollingRelaySchedulingConfig {}

    private static final Logger log = LoggerFactory.getLogger(PollingRelay.class);

    /** Bounded page size per tick — simple, not tuned for throughput (see class javadoc). */
    private static final int PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;
    private final ObjectMapper objectMapper;

    public PollingRelay(JdbcTemplate jdbc, EventPublisher publisher, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Fixed-delay poll (delay counted from the end of the previous run, so a slow
     * publish doesn't cause overlapping ticks). Short interval per the plan
     * ("fixed delay, short e.g. 2-5s").
     */
    @Scheduled(fixedDelayString = "${app.eventing.polling.fixed-delay-ms:3000}")
    public void relay() {
        List<UnpublishedRow> rows = fetchUnpublished();
        for (UnpublishedRow row : rows) {
            publishAndMark(row);
        }
    }

    private List<UnpublishedRow> fetchUnpublished() {
        return jdbc.query(
            """
            SELECT id, org_id, aggregate_type, aggregate_id, event_type, payload, trace_id, created_at
            FROM outbox_events
            WHERE published = false
            ORDER BY created_at ASC
            LIMIT ?
            """,
            ROW_MAPPER,
            PAGE_SIZE
        );
    }

    private void publishAndMark(UnpublishedRow row) {
        try {
            JsonNode payload = objectMapper.readTree(row.payload());
            EventEnvelope envelope = new EventEnvelope(
                row.id(),
                row.eventType(),
                1,
                row.orgId().toString(),
                row.aggregateType(),
                row.aggregateId(),
                row.createdAt(),
                row.traceId(),
                payload
            );

            // Same topic-per-event-type convention as the Debezium outbox-event-router
            // SMT config (debezium/outbox-connector.json): the row's event_type value
            // IS the topic name. Blocking get() here is deliberate — this method must
            // not mark the row published until the send is acknowledged, or a crash
            // between "mark" and "actually delivered" would silently drop the event.
            publisher.publish(row.eventType(), envelope).get(30, TimeUnit.SECONDS);

            int updated = jdbc.update("UPDATE outbox_events SET published = true WHERE id = ?", row.id());
            if (updated == 0) {
                log.warn("Polling relay: outbox row {} disappeared before it could be marked published", row.id());
            }
        } catch (Exception e) {
            // Leave the row unpublished — it's picked up again next tick. No DLQ here;
            // that's a T2.4 concern for consumer-side poison messages, not producer-side
            // relay retries (a transient Kafka/broker blip should just be retried).
            log.error("Polling relay: failed to publish outbox row {} (event_type={}), will retry next tick",
                row.id(), row.eventType(), e);
        }
    }

    private static final RowMapper<UnpublishedRow> ROW_MAPPER = (rs, rowNum) -> new UnpublishedRow(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("org_id")),
        rs.getString("aggregate_type"),
        rs.getString("aggregate_id"),
        rs.getString("event_type"),
        rs.getString("payload"),
        rs.getString("trace_id"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant()
    );

    private record UnpublishedRow(
        UUID id,
        UUID orgId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        String traceId,
        Instant createdAt
    ) {}
}
