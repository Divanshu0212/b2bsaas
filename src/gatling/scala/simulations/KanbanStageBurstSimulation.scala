package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * T4.9: concurrent Kanban stage-change bursts — the write-heavy hot path (each PATCH
 * triggers an optimistic-locked update + an outbox event). Measures p95/p99 under a ramp
 * of concurrent users, the number the README publishes for "Kanban drag under load".
 *
 * Base URL + a pre-provisioned JWT and deal/stage ids come from system properties so the
 * simulation stays environment-agnostic (see loadtest/README.md for how to obtain them):
 *   -Dbase=http://localhost:8080 -Dtoken=... -DdealId=... -DtoStageId=...
 */
class KanbanStageBurstSimulation extends Simulation {

  private val baseUrl = System.getProperty("base", "http://localhost:8080")
  private val token = System.getProperty("token", "")
  private val dealId = System.getProperty("dealId", "")
  private val toStageId = System.getProperty("toStageId", "")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .authorizationHeader(s"Bearer $token")
    .contentTypeHeader("application/json")

  private val stagePatch = scenario("Kanban stage burst").exec(
    http("PATCH deal stage")
      .patch(s"/deals/$dealId/stage")
      .body(StringBody(s"""{"toStageId":"$toStageId"}"""))
      .check(status.in(200, 409)) // 409 = optimistic-lock conflict under contention, expected
  )

  setUp(
    stagePatch.inject(
      rampConcurrentUsers(1).to(50).during(30.seconds),
      constantConcurrentUsers(50).during(30.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(2000), // p99 < 2s
      global.successfulRequests.percent.gt(95)
    )
}
