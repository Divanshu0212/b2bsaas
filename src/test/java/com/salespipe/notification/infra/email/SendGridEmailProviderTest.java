package com.salespipe.notification.infra.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salespipe.common.resilience.IdempotencyStore;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * T4.4: unit tests for the SendGrid provider's resilience + idempotency behaviour,
 * with the SendGrid client and idempotency store mocked (no network, no Redis).
 */
class SendGridEmailProviderTest {

    private final EmailProperties props = new EmailProperties();

    private SendGridEmailProvider provider(SendGrid sendGrid, IdempotencyStore store, CircuitBreakerRegistry cbReg) {
        return new SendGridEmailProvider(
            sendGrid, props, store,
            Executors.newSingleThreadExecutor(),
            cbReg,
            RetryRegistry.ofDefaults(),
            TimeLimiterRegistry.ofDefaults());
    }

    @Test
    void duplicateIdempotencyKeyDoesNotCallSendGrid() throws Exception {
        SendGrid sendGrid = mock(SendGrid.class);
        IdempotencyStore store = mock(IdempotencyStore.class);
        // First claim wins, second is a duplicate.
        when(store.firstSeen(any(), any())).thenReturn(false);

        provider(sendGrid, store, CircuitBreakerRegistry.ofDefaults())
            .send(new EmailMessage("owner@x.com", "s", "b"), "dup-key");

        verify(sendGrid, times(0)).api(any(Request.class));
    }

    @Test
    void firstSendCallsSendGridOnce() throws Exception {
        SendGrid sendGrid = mock(SendGrid.class);
        Response ok = new Response(202, "", null);
        when(sendGrid.api(any(Request.class))).thenReturn(ok);
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.firstSeen(any(), any())).thenReturn(true);

        provider(sendGrid, store, CircuitBreakerRegistry.ofDefaults())
            .send(new EmailMessage("owner@x.com", "s", "b"), "fresh-key");

        verify(sendGrid, times(1)).api(any(Request.class));
    }

    @Test
    void circuitBreakerOpensAfterSustainedFailures() throws Exception {
        SendGrid sendGrid = mock(SendGrid.class);
        when(sendGrid.api(any(Request.class))).thenThrow(new IOException("sendgrid down"));
        IdempotencyStore store = mock(IdempotencyStore.class);
        when(store.firstSeen(any(), any())).thenReturn(true);

        // Small window so a handful of failures opens the breaker deterministically.
        CircuitBreakerRegistry cbReg = CircuitBreakerRegistry.of(
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        SendGridEmailProvider provider = provider(sendGrid, store, cbReg);

        // send() swallows failures (email is best-effort), so drive enough attempts to
        // trip the breaker, then assert the named instance is OPEN.
        for (int i = 0; i < 10; i++) {
            provider.send(new EmailMessage("owner@x.com", "s", "b"), "k-" + i);
        }

        assertThat(cbReg.circuitBreaker(SendGridEmailProvider.INSTANCE).getState())
            .isEqualTo(CircuitBreaker.State.OPEN);
    }
}
