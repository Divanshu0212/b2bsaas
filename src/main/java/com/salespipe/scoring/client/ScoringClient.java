package com.salespipe.scoring.client;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Resilient WebClient wrapper around the Python scoring service (phase-3 T3.4). The
 * single {@code POST /internal/score} call is wrapped in a Resilience4j <b>circuit
 * breaker + time limiter + bulkhead</b> (instance {@code scoringService}, configured in
 * {@code application.yml} §resilience4j). On any failure — ML service down, timeout,
 * circuit open, bulkhead full — the fallback returns {@link Optional#empty()} rather than
 * throwing, so the caller ({@link com.salespipe.scoring.recompute.ScoringService}) can
 * fall back to the last-known score and the app never surfaces an ML error to the user
 * (overview change #9, §6.3).
 *
 * <p>The three annotations compose in Resilience4j's fixed aspect order (Bulkhead →
 * TimeLimiter → CircuitBreaker → method): the bulkhead caps concurrency, the time limiter
 * bounds each call, and the circuit breaker counts timeouts/errors toward opening. The
 * method returns {@link CompletableFuture} because {@code @TimeLimiter} requires an async
 * return type; {@link #score} adapts it back to a blocking {@link Optional} for callers.
 */
@Component
public class ScoringClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringClient.class);
    static final String INSTANCE = "scoringService";

    private final WebClient webClient;

    public ScoringClient(WebClient scoringWebClient) {
        this.webClient = scoringWebClient;
    }

    /** Blocking convenience wrapper: {@link Optional#empty()} on any failure (never throws). */
    public Optional<ScoreResponse> score(ScoreRequest request) {
        try {
            return scoreAsync(request).join();
        } catch (Exception e) {
            // join() only rethrows if the fallback itself failed, which it does not.
            log.warn("Scoring call failed unexpectedly for lead {}", request.leadId(), e);
            return Optional.empty();
        }
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "fallback")
    @TimeLimiter(name = INSTANCE, fallbackMethod = "fallback")
    @Bulkhead(name = INSTANCE, fallbackMethod = "fallback")
    CompletableFuture<Optional<ScoreResponse>> scoreAsync(ScoreRequest request) {
        return webClient.post()
            .uri("/internal/score")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ScoreResponse.class)
            .map(Optional::of)
            .timeout(Duration.ofSeconds(5)) // hard ceiling behind the 2s time limiter
            .toFuture();
    }

    /** Resilience4j fallback: swallow the failure, return empty so caller uses last-known score. */
    @SuppressWarnings("unused")
    CompletableFuture<Optional<ScoreResponse>> fallback(ScoreRequest request, Throwable t) {
        log.warn("Scoring service unavailable for lead {} ({}); falling back to last-known score",
            request.leadId(), t.toString());
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
