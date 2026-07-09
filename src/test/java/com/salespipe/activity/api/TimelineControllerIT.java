package com.salespipe.activity.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T2.5 acceptance test: "timeline endpoint returns merged, paginated, tenant-scoped
 * feed" (phase-2 plan T2.5 accept line).
 *
 * <p>Follows the same MockMvc-via-RestAssured convention as {@code LeadApiIT}: register
 * a real org/user through {@code /auth/register} to get a working JWT, then create a
 * lead through the real {@code POST /leads} endpoint. Activity rows and a linked deal
 * are inserted directly via JDBC — the point of this test is the read/merge/pagination
 * behavior of {@code GET /leads/{id}/timeline}, not re-proving the consumer write path
 * (that's {@code ActivityConsumerIT}).
 *
 * <p>Covers: (1) the lead's own activities and its linked deal's activities are merged
 * into one {@code created_at DESC} feed ("merged" per this task's interpretation — see
 * {@link TimelineController}'s javadoc); (2) pagination works ({@code size}/{@code page}
 * params, {@code totalElements}); (3) a second org's activities never appear (tenant
 * isolation) even though nothing but the JWT distinguishes the two requests.
 */
class TimelineControllerIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    String token;
    UUID orgId;
    UUID leadId;
    UUID dealId;
    UUID otherOrgId;

    @BeforeEach
    void setup() {
        RestAssured.port = port;

        // Unique email per test method: users.email is only unique per-(org_id, email)
        // (V1__init.sql), not globally, so a fixed literal here would make the
        // org_id lookup below ambiguous (IncorrectResultSizeDataAccessException) once
        // more than one test in this class has registered.
        String email = "timeline-" + UUID.randomUUID() + "@acme.com";
        Map<String, Object> registration = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Timeline Test Org", "email", email, "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .extract().jsonPath().getMap("$");
        token = (String) registration.get("accessToken");

        orgId = jdbc.queryForObject(
            "SELECT org_id FROM users WHERE email = ?", UUID.class, email);

        leadId = UUID.fromString(
            given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
                .body(Map.of("status", "NEW", "source", "web"))
                .post("/leads").then().statusCode(201)
                .extract().path("id"));

        // Linked deal: satisfy deals' FK chain (stage_id -> deal_stages). /auth/register
        // already seeds a default 5-stage pipeline for the new org (DealStageSeeder), so
        // reuse one of those rather than inserting a colliding (org_id, position) row.
        UUID stageId = jdbc.queryForObject(
            "SELECT id FROM deal_stages WHERE org_id = ? ORDER BY position LIMIT 1", UUID.class, orgId);
        dealId = UUID.randomUUID();
        jdbc.update("INSERT INTO deals (id, org_id, lead_id, stage_id) VALUES (?, ?, ?, ?)",
            dealId, orgId, leadId, stageId);

        // A second org with its own activity on an entity_id that happens to collide
        // with neither leadId nor dealId — proves cross-tenant rows are excluded purely
        // by org_id, not by accident of disjoint ids.
        otherOrgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan) VALUES (?, ?, ?)",
            otherOrgId, "Other Org", "FREE");
        insertActivity(otherOrgId, "lead", leadId, "NOTE", OffsetDateTime.now());
    }

    @Test
    void timelineMergesLeadAndLinkedDealActivitiesTenantScopedAndPaginated() {
        OffsetDateTime base = OffsetDateTime.now().minusMinutes(10);
        insertActivity(orgId, "lead", leadId, "LEAD_CREATED", base.plusMinutes(1));
        insertActivity(orgId, "deal", dealId, "STAGE_CHANGE", base.plusMinutes(2));
        insertActivity(orgId, "lead", leadId, "NOTE", base.plusMinutes(3));
        insertActivity(orgId, "deal", dealId, "STAGE_CHANGE", base.plusMinutes(4));

        given().header("Authorization", "Bearer " + token)
            .get("/leads/" + leadId + "/timeline?page=0&size=20")
            .then().statusCode(200)
            .body("totalElements", equalTo(4))
            // Newest first: the most recent insert (deal STAGE_CHANGE at +4m) leads.
            .body("content[0].entityType", equalTo("deal"))
            .body("content[0].activityType", equalTo("STAGE_CHANGE"))
            .body("content[3].activityType", equalTo("LEAD_CREATED"));
    }

    @Test
    void timelinePaginates() {
        OffsetDateTime base = OffsetDateTime.now().minusMinutes(10);
        for (int i = 0; i < 5; i++) {
            insertActivity(orgId, "lead", leadId, "NOTE", base.plusMinutes(i));
        }

        given().header("Authorization", "Bearer " + token)
            .get("/leads/" + leadId + "/timeline?page=0&size=2")
            .then().statusCode(200)
            .body("totalElements", equalTo(5))
            .body("content.size()", equalTo(2))
            .body("totalPages", equalTo(3));
    }

    @Test
    void timelineExcludesOtherOrgsActivities() {
        // Only the other org's row exists (inserted in setup); this org's own feed for
        // the same leadId must come back empty, not leak the other org's NOTE.
        given().header("Authorization", "Bearer " + token)
            .get("/leads/" + leadId + "/timeline")
            .then().statusCode(200)
            .body("totalElements", equalTo(0));
    }

    private void insertActivity(UUID orgId, String entityType, UUID entityId, String activityType, OffsetDateTime createdAt) {
        jdbc.update(
            "INSERT INTO activities (id, org_id, entity_type, entity_id, activity_type, payload, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
            UUID.randomUUID(), orgId, entityType, entityId, activityType, "{}", createdAt
        );
    }
}
