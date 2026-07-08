package com.salespipe.crmcore;

import com.salespipe.identity.domain.Role;
import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.JwtProvider;
import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

class RbacIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @Autowired JwtProvider jwt;
    String adminToken;
    UUID orgId;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        var body = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Rbac", "email", "admin@rbac.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201).extract();
        adminToken = body.path("accessToken");
        // decode org from the admin token to mint a SALES_REP token for the same org
        orgId = jwt.orgId(jwt.parse(adminToken).getPayload());
    }

    @Test
    void salesRepCannotDeleteLead() {
        String leadId = given().header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON).body(Map.of("status", "NEW"))
            .post("/leads").then().statusCode(201).extract().path("id");

        // Mint a SALES_REP access token for the same org (no self-service rep signup in Phase 1).
        String repToken = jwt.createAccessToken(UUID.randomUUID(), orgId, Role.SALES_REP);

        given().header("Authorization", "Bearer " + repToken)
            .delete("/leads/" + leadId).then().statusCode(403);

        given().header("Authorization", "Bearer " + adminToken)
            .delete("/leads/" + leadId).then().statusCode(204);
    }
}
