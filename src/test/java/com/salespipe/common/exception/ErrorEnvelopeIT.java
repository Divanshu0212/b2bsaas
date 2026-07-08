package com.salespipe.common.exception;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;

class ErrorEnvelopeIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @BeforeEach void setPort() { RestAssured.port = port; }

    @Test
    void validationErrorReturnsProblemJson() {
        given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "", "email", "bad", "password", "x"))
            .post("/auth/register").then()
            .statusCode(400)
            .contentType("application/problem+json");
    }
}
