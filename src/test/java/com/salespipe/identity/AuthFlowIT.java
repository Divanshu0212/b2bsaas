package com.salespipe.identity;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

class AuthFlowIT extends PostgresRedisTestBase {

    @LocalServerPort int port;

    @BeforeEach
    void setPort() { RestAssured.port = port; }

    private Map<String, String> register(String org, String email) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("orgName", org, "email", email, "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .extract().as(Map.class);
    }

    @Test
    void registerLoginRefreshThenReuseRevokesFamily() {
        var t = register("Acme", "admin@acme.com");
        String refresh = t.get("refreshToken");

        // rotate once
        var rotated = given().contentType(ContentType.JSON)
            .body(Map.of("refreshToken", refresh))
            .post("/auth/refresh").then().statusCode(200)
            .extract().as(Map.class);

        // replay the OLD refresh token -> reuse -> 401
        given().contentType(ContentType.JSON)
            .body(Map.of("refreshToken", refresh))
            .post("/auth/refresh").then().statusCode(401);

        // the rotated token is now also revoked (family nuked) -> 401
        given().contentType(ContentType.JSON)
            .body(Map.of("refreshToken", rotated.get("refreshToken")))
            .post("/auth/refresh").then().statusCode(401);
    }

    @Test
    void loginWorks() {
        register("Beta", "u@beta.com");
        given().contentType(ContentType.JSON)
            .body(Map.of("email", "u@beta.com", "password", "password123"))
            .post("/auth/login").then().statusCode(200)
            .body("accessToken", notNullValue());
    }
}
