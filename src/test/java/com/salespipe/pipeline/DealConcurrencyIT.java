package com.salespipe.pipeline;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class DealConcurrencyIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    String token;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        token = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Pipe", "email", "p@pipe.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201).extract().path("accessToken");
    }

    @Test
    void concurrentStageMovesConflict() throws Exception {
        List<Map<String, Object>> stages = given().header("Authorization", "Bearer " + token)
            .get("/deal-stages").then().statusCode(200).extract().jsonPath().getList("$");
        String newStage = (String) stages.get(0).get("id");
        String qualStage = (String) stages.get(1).get("id");

        String dealId = given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("stageId", newStage))
            .post("/deals").then().statusCode(201).extract().path("id");

        // Both requests carry version=0 -> exactly one wins.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> patch = () -> given().header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(Map.of("toStageId", qualStage, "version", 0))
            .patch("/deals/" + dealId + "/stage").then().extract().statusCode();

        Future<Integer> f1 = pool.submit(patch);
        Future<Integer> f2 = pool.submit(patch);
        int s1 = f1.get(10, TimeUnit.SECONDS);
        int s2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 409);
    }
}
