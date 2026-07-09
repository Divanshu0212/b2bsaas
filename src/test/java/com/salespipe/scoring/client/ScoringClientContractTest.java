package com.salespipe.scoring.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * T3.3 Java-side contract test for {@code POST /internal/score}. WireMock stubs the
 * Python scoring service; asserts (a) the request body ScoringClient sends matches the
 * agreed snake_case contract ({@code ml/app/schemas.py}) and (b) the response is mapped
 * into {@link ScoreResponse} including {@code model_version}/{@code top_factors}. The
 * pytest side ({@code ml/tests/test_contract.py}) asserts the same shape from Python, so
 * both ends are pinned to one contract.
 *
 * <p>Constructs {@link ScoringClient} directly against a WebClient pointed at WireMock —
 * the resilience4j annotations are inert without a Spring proxy here, which is fine: this
 * test targets the request/response mapping; the fallback/circuit behavior is covered by
 * {@code ScoringServiceTest}.
 */
class ScoringClientContractTest {

    WireMockServer wm;
    ScoringClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(0);
        wm.start();
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wm.port()).build();
        client = new ScoringClient(webClient);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void sendsContractRequestAndMapsResponse() {
        wm.stubFor(post(urlEqualTo("/internal/score"))
            .withRequestBody(equalToJson("""
                {
                  "lead_id": "lead-1",
                  "text_features": ["hot inbound"],
                  "structured_features": {"email_click_count": 3}
                }
                """, true, true))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "score": 0.812,
                      "model_version": "leadscore/Production/v7",
                      "top_factors": [
                        {"feature": "email_click_count", "impact": 0.18}
                      ]
                    }
                    """)));

        ScoreRequest req = new ScoreRequest("lead-1", List.of("hot inbound"), Map.of("email_click_count", 3));
        Optional<ScoreResponse> response = client.score(req);

        assertThat(response).isPresent();
        assertThat(response.get().score()).isEqualTo(0.812);
        assertThat(response.get().modelVersion()).isEqualTo("leadscore/Production/v7");
        assertThat(response.get().topFactors()).singleElement()
            .satisfies(f -> {
                assertThat(f.feature()).isEqualTo("email_click_count");
                assertThat(f.impact()).isEqualTo(0.18);
            });
        verify(postRequestedFor(urlEqualTo("/internal/score")));
    }

    @Test
    void serviceErrorMapsToEmptyFallback() {
        wm.stubFor(post(urlEqualTo("/internal/score"))
            .willReturn(aResponse().withStatus(503)));

        Optional<ScoreResponse> response =
            client.score(new ScoreRequest("lead-2", List.of(), Map.of()));

        assertThat(response).isEmpty(); // 5xx -> empty, caller uses last-known score
    }
}
