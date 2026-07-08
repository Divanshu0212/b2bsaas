package com.salespipe;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

class OpenApiIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @BeforeEach void setPort() { RestAssured.port = port; }

    @Test
    void apiDocsExposePhase1Paths() {
        given().get("/v3/api-docs").then().statusCode(200)
            .body("paths.'/leads'", notNullValue())
            .body("paths.'/deals/{id}/stage'", notNullValue())
            .body("paths.'/auth/login'", notNullValue());
    }
}
