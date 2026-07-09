package com.salespipe.common.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@link RetentionProperties} always (so config binds even when the job is off) and
 * turns on {@code @Scheduled} processing only when {@code app.retention.enabled=true} — same
 * pattern as {@code PollingRelay}'s conditional scheduling, avoiding turning scheduling
 * infrastructure on app-wide just for this job.
 */
@Configuration
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfig {

    @Configuration
    @ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true")
    @EnableScheduling
    static class RetentionSchedulingConfig {}
}
