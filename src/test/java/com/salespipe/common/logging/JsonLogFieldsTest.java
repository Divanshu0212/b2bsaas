package com.salespipe.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * T4.3: the JSON log encoder must surface the three correlation fields as
 * {@code org_id} / {@code user_id} / {@code trace_id} (snake_case) — the keys
 * {@code logback-spring.xml} declares via {@code <includeMdcKeyName>} and the keys Loki's
 * derived-field trace correlation looks for. This guards against a producer (HTTP filter,
 * Kafka consumer) putting the value under a different-cased MDC key, which would silently
 * drop the field from every log line.
 */
class JsonLogFieldsTest {

    @Test
    void encoderEmitsSnakeCaseCorrelationFieldsFromMdc() throws Exception {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.addIncludeMdcKeyName("org_id");
        encoder.addIncludeMdcKeyName("user_id");
        encoder.addIncludeMdcKeyName("trace_id");
        encoder.start();

        Logger logger = (Logger) LoggerFactory.getLogger(JsonLogFieldsTest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        MDC.put("org_id", "11111111-1111-1111-1111-111111111111");
        MDC.put("user_id", "22222222-2222-2222-2222-222222222222");
        MDC.put("trace_id", "0af7651916cd43dd8448eb211c80319c");
        try {
            logger.info("correlated log line");
        } finally {
            MDC.clear();
        }

        assertThat(appender.list).hasSize(1);
        String json = new String(encoder.encode(appender.list.get(0)));
        assertThat(json).contains("\"org_id\":\"11111111-1111-1111-1111-111111111111\"");
        assertThat(json).contains("\"user_id\":\"22222222-2222-2222-2222-222222222222\"");
        assertThat(json).contains("\"trace_id\":\"0af7651916cd43dd8448eb211c80319c\"");
    }
}
