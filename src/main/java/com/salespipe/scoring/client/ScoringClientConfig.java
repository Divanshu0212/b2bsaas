package com.salespipe.scoring.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Builds the {@link WebClient} the {@link ScoringClient} uses to reach the Python scoring
 * service (T3.4). Base URL from {@code app.scoring.base-url}. Kept as a dedicated bean so
 * the scoring module owns its own WebClient instance (timeouts/retries layered by
 * resilience4j in {@link ScoringClient}, not here).
 */
@Configuration
class ScoringClientConfig {

    @Bean
    WebClient scoringWebClient(@Value("${app.scoring.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
