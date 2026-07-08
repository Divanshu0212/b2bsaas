package com.salespipe.crmcore;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

class LeadApiIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    String token;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        token = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Acme", "email", "crm@acme.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .extract().path("accessToken");
    }

    @Test
    void createListFilterLead() {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("status", "NEW", "source", "web"))
            .post("/leads").then().statusCode(201);

        given().header("Authorization", "Bearer " + token)
            .get("/leads?status=NEW&page=0&size=10").then().statusCode(200)
            .body("totalElements", equalTo(1));

        given().header("Authorization", "Bearer " + token)
            .get("/leads?status=QUALIFIED").then().statusCode(200)
            .body("totalElements", equalTo(0));
    }
}
