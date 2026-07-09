package com.salespipe.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Map;

/**
 * T4.1: binds one {@code salespipe.consumer.lag{group=...}} gauge per consumer group,
 * sourced from a pluggable {@link ConsumerLagSource} — same shape as {@link
 * DlqDepthMetrics}, decoupled from how lag is actually computed (see {@code
 * MetricsConfig} for the {@code AdminClient}-backed source that diffs each group's
 * committed offsets against topic end-offsets).
 *
 * <p>Groups are looked up fresh on every scrape (via {@link ConsumerLagSource#lagByGroup()})
 * rather than bound once at startup, since consumer groups can appear/disappear as
 * listener containers start/stop.
 */
public class ConsumerLagMetrics implements MeterBinder {

    @FunctionalInterface
    public interface ConsumerLagSource {
        /** Total lag (sum across partitions) per consumer group, keyed by group id. */
        Map<String, Long> lagByGroup();
    }

    private final ConsumerLagSource source;

    public ConsumerLagMetrics(ConsumerLagSource source) {
        this.source = source;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("salespipe.consumer.lag.total", () -> source.lagByGroup().values().stream()
                .mapToLong(Long::longValue).sum())
            .description("Sum of consumer lag across all known groups")
            .register(registry);

        // Per-group gauges are registered lazily as groups are discovered — Micrometer's
        // Gauge holds a weak reference to this class, so the small perpetual cache below
        // (one entry per group id ever seen) is what keeps each per-group gauge alive.
        for (String group : source.lagByGroup().keySet()) {
            registerGroupGauge(registry, group);
        }
    }

    private void registerGroupGauge(MeterRegistry registry, String group) {
        Gauge.builder("salespipe.consumer.lag", () -> source.lagByGroup().getOrDefault(group, 0L))
            .tag("group", group)
            .description("Consumer lag (records behind end-offset) for this group")
            .register(registry);
    }
}
