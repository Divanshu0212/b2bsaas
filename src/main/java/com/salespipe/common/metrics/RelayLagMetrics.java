package com.salespipe.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * T4.1: binds {@code salespipe.outbox.relay.lag} — the count of {@code outbox_events}
 * rows not yet relayed ({@code published = false}). Meaningful under the polling-relay
 * fallback (see {@link com.salespipe.eventing.outbox.OutboxEvent} javadoc); under the
 * default CDC path this stays near zero since Debezium doesn't consult the column, but
 * the gauge is still cheap to expose either way.
 */
public class RelayLagMetrics implements MeterBinder {

    @FunctionalInterface
    public interface RelayLagSource { long unpublishedCount(); }

    private final RelayLagSource source;

    public RelayLagMetrics(RelayLagSource source) {
        this.source = source;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("salespipe.outbox.relay.lag", source::unpublishedCount)
            .description("Outbox rows not yet relayed to Kafka (published = false)")
            .register(registry);
    }
}
