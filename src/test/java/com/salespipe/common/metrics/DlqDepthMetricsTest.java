package com.salespipe.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class DlqDepthMetricsTest {

    @Test
    void bindsGaugePerDlqTopic() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        DlqDepthMetrics.DlqDepthSource source = topic -> 7L;
        new DlqDepthMetrics(List.of("deals.events.DLQ"), source).bindTo(reg);
        assertThat(reg.get("salespipe.dlq.depth").tag("topic", "deals.events.DLQ").gauge().value())
            .isEqualTo(7.0);
    }
}
