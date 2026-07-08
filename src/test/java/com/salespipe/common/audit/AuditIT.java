package com.salespipe.common.audit;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class AuditIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    String token;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        token = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Aud", "email", "a@aud.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201).extract().path("accessToken");
    }

    @Test
    void mutatingLeadWritesAuditRow() {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("status", "NEW")).post("/leads").then().statusCode(201);

        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE entity_type='LeadController'", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}
