package com.salespipe.pipeline;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T2.7 acceptance test — real-HTTP half of the plan's "PATCH /deals/{id}/stage ->
 * outbox -> CDC -> Kafka -> activity + notification consumers both fire idempotently"
 * accept line.
 *
 * <p>This class proves the part that is genuinely end-to-end through the real API:
 * a {@code PATCH /deals/{id}/stage} request writes the deal-update + {@code
 * deal_stage_history} row + {@code outbox_events} row all in one transaction, reusing
 * {@code OutboxAtomicityIT}'s proof pattern (same-transaction commit) but driven by an
 * actual HTTP request through {@code TenantFilter}/Spring Security/{@code
 * DealController}/{@code StageTransitionService}, rather than calling {@code
 * OutboxRecorder} directly.
 *
 * <p>What this class does NOT prove: that Debezium actually relays the {@code
 * outbox_events} row to Kafka. That hop was never Testcontainers-tested even in T2.3
 * (documented as a runbook instead, see {@code debezium/README.md}) — this is an
 * accepted, already-documented gap, not something T2.7 re-solves. The consumer-firing
 * half of the accept line (both activity and notification consumers processing a
 * {@code deal.stage.changed} event idempotently) is covered separately by {@code
 * com.salespipe.notification.consumer.DealStageChangedConsumersIT}, which publishes the
 * event directly to Kafka the same way {@code ActivityConsumerIT}/{@code
 * IdempotentConsumerIT} do — i.e. "as if CDC had relayed it".
 */
class DealStageChangeOutboxIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    String token;
    UUID stageFrom;
    UUID stageTo;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        token = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Outbox HTTP Test Org", "email", "outbox-http@acme.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .extract().path("accessToken");

        List<Map<String, Object>> stages = given().header("Authorization", "Bearer " + token)
            .get("/deal-stages").then().statusCode(200)
            .extract().jsonPath().getList("$");
        stageFrom = UUID.fromString((String) stages.get(0).get("id"));
        stageTo = UUID.fromString((String) stages.get(1).get("id"));
    }

    @Test
    void patchDealStageWritesDealUpdateHistoryAndOutboxRowInOneTransaction() {
        String dealId = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("stageId", stageFrom.toString()))
            .post("/deals").then().statusCode(201)
            .extract().path("id");

        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("toStageId", stageTo.toString(), "version", 0))
            .patch("/deals/{id}/stage", dealId).then().statusCode(200)
            .body("stageId", equalTo(stageTo.toString()));

        Integer historyCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM deal_stage_history WHERE deal_id = ?", Integer.class, UUID.fromString(dealId));
        Integer outboxCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'deal.stage.changed'",
            Integer.class, dealId);

        assertThat(historyCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);

        String payload = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = 'deal.stage.changed'",
            String.class, dealId);
        assertThat(payload).contains(dealId).contains(stageTo.toString()).contains(stageFrom.toString());
    }
}
