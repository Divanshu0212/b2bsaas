package com.salespipe.notification.infra.email;

import com.salespipe.common.resilience.IdempotencyStore;
import com.sendgrid.SendGrid;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * T4.4: wires the active {@link EmailProvider}. With {@code app.email.enabled=true} the
 * real {@link SendGridEmailProvider} is used; otherwise (the default, including all tests)
 * a {@link NoopEmailProvider}. Either way the same {@code EmailProvider} bean is present,
 * so the notification consumers depend only on the interface.
 */
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailProviderConfig {

    /**
     * Bounded executor the SendGrid call runs on — a bulkhead separating slow email I/O
     * from consumer threads. Small and fixed: a SendGrid stall parks at most this many
     * threads, never the whole consumer pool.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService emailExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "email-sender");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public SendGrid sendGrid(EmailProperties properties) {
        // Constructed even when disabled (cheap, no network on construction); the
        // NoopEmailProvider simply never calls it. Real key comes from app.email.api-key.
        return new SendGrid(properties.getApiKey());
    }

    @Bean
    public EmailProvider emailProvider(
        EmailProperties properties,
        SendGrid sendGrid,
        IdempotencyStore idempotencyStore,
        ExecutorService emailExecutor,
        CircuitBreakerRegistry circuitBreakerRegistry,
        RetryRegistry retryRegistry,
        TimeLimiterRegistry timeLimiterRegistry
    ) {
        if (properties.isEnabled()) {
            return new SendGridEmailProvider(
                sendGrid, properties, idempotencyStore, emailExecutor,
                circuitBreakerRegistry, retryRegistry, timeLimiterRegistry);
        }
        return new NoopEmailProvider(
            idempotencyStore, Duration.ofSeconds(properties.getIdempotencyTtlSeconds()));
    }
}
