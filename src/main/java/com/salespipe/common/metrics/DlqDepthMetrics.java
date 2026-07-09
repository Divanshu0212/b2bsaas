package com.salespipe.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;

/**
 * T4.1: binds one {@code salespipe.dlq.depth{topic=...}} gauge per DLQ topic (naming
 * per {@link com.salespipe.eventing.Topics#dlqFor(String)}), sourced from a pluggable
 * {@link DlqDepthSource} so this class stays independent of how depth is actually
 * computed (see {@code MetricsConfig} for the Kafka-{@code AdminClient}-backed source).
 */
public class DlqDepthMetrics implements MeterBinder {

    @FunctionalInterface
    public interface DlqDepthSource { long depth(String dlqTopic); }

    private final List<String> dlqTopics;
    private final DlqDepthSource source;

    public DlqDepthMetrics(List<String> dlqTopics, DlqDepthSource source) {
        this.dlqTopics = dlqTopics;
        this.source = source;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String topic : dlqTopics) {
            Gauge.builder("salespipe.dlq.depth", () -> source.depth(topic))
                .tag("topic", topic)
                .description("Messages currently sitting in the DLQ topic")
                .register(registry);
        }
    }
}
