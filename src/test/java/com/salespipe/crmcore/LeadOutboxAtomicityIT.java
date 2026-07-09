package com.salespipe.crmcore;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T2.7 acceptance test — lead-creation half of the task: {@code POST /leads} records
 * exactly one {@code outbox_events} row in the SAME transaction as the lead insert.
 * Mirrors {@code eventing.outbox.OutboxAtomicityIT}'s same-transaction proof pattern,
 * but driven through the real HTTP path (which is how lead creation actually happens —
 * see {@code LeadController}/{@code LeadService}) rather than a hand-built {@code
 * @Transactional} test helper, since {@code LeadService.create} is now itself the
 * production transactional boundary being proven, not a stand-in for one.
 */
class LeadOutboxAtomicityIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    String token;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        token = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Lead Outbox Test Org", "email", "lead-outbox@acme.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .extract().path("accessToken");
    }

    @Test
    void creatingALeadRecordsExactlyOneOutboxRowInTheSameTransaction() {
        String leadId = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("status", "NEW", "source", "web"))
            .post("/leads").then().statusCode(201)
            .extract().path("id");

        Integer leadCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM leads WHERE id = ?", Integer.class, UUID.fromString(leadId));
        Integer outboxCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'lead.created'",
            Integer.class, leadId);

        assertThat(leadCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);

        String payload = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = 'lead.created'",
            String.class, leadId);
        assertThat(payload).contains(leadId).contains("\"source\"").contains("\"web\"").contains("\"NEW\"");
    }
}
