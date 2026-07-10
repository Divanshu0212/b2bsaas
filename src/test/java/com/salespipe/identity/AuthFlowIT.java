package com.salespipe.identity;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * T6.3: refresh token now rides an httpOnly {@code refresh_token} cookie, not the JSON body.
 * The access token is still returned in the body; the body must NOT expose a refresh token.
 */
class AuthFlowIT extends PostgresRedisTestBase {

    @LocalServerPort int port;

    @BeforeEach
    void setPort() { RestAssured.port = port; }

    private Response register(String org, String email) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("orgName", org, "email", email, "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .body("accessToken", notNullValue())
            // refresh token must NOT be in the body anymore
            .body("refreshToken", nullValue())
            .cookie("refresh_token", notNullValue())
            .extract().response();
    }

    @Test
    void registerLoginRefreshThenReuseRevokesFamily() {
        var reg = register("Acme", "admin@acme.com");
        String refresh = reg.getCookie("refresh_token");

        // rotate once (send the cookie, no body)
        String rotated = given().cookie("refresh_token", refresh)
            .post("/auth/refresh").then().statusCode(200)
            .body("accessToken", notNullValue())
            .cookie("refresh_token", notNullValue())
            .extract().cookie("refresh_token");

        // replay the OLD refresh cookie -> reuse -> 401
        given().cookie("refresh_token", refresh)
            .post("/auth/refresh").then().statusCode(401);

        // the rotated token is now also revoked (family nuked) -> 401
        given().cookie("refresh_token", rotated)
            .post("/auth/refresh").then().statusCode(401);
    }

    @Test
    void loginSetsRefreshCookieAndAccessBody() {
        register("Beta", "u@beta.com");
        given().contentType(ContentType.JSON)
            .body(Map.of("email", "u@beta.com", "password", "password123"))
            .post("/auth/login").then().statusCode(200)
            .body("accessToken", notNullValue())
            .cookie("refresh_token", notNullValue());
    }

    @Test
    void logoutExpiresCookieAndRevokes() {
        var reg = register("Gamma", "g@gamma.com");
        String refresh = reg.getCookie("refresh_token");

        given().cookie("refresh_token", refresh)
            .post("/auth/logout").then().statusCode(204);

        // revoked family: the cookie no longer refreshes
        given().cookie("refresh_token", refresh)
            .post("/auth/refresh").then().statusCode(401);
    }
}
