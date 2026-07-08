package com.salespipe.common.tenant;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;

class TenantIsolationIT extends PostgresRedisTestBase {

    @LocalServerPort int port;

    @BeforeEach
    void setPort() { RestAssured.port = port; }

    private String tokenFor(String org, String email) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("orgName", org, "email", email, "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .extract().path("accessToken");
    }

    @Test
    void orgACannotReadOrgBLead() {
        String tokenB = tokenFor("OrgB", "b@b.com");
        String leadId = given().header("Authorization", "Bearer " + tokenB)
            .contentType(ContentType.JSON)
            .body(Map.of("status", "NEW"))
            .post("/leads").then().statusCode(201)
            .extract().path("id");

        String tokenA = tokenFor("OrgA", "a@a.com");
        // Same id, different tenant -> 404 (filtered out, not visible).
        given().header("Authorization", "Bearer " + tokenA)
            .get("/leads/" + leadId).then().statusCode(404);
    }
}
