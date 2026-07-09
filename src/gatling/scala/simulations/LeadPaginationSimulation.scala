package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * T4.9: read-heavy lead-list pagination. Exercises the indexed org-scoped list query under
 * concurrent load — the common dashboard read that must stay fast while writes happen.
 */
class LeadPaginationSimulation extends Simulation {

  private val baseUrl = System.getProperty("base", "http://localhost:8080")
  private val token = System.getProperty("token", "")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .authorizationHeader(s"Bearer $token")

  private val pages = Iterator.continually(Map("page" -> scala.util.Random.nextInt(10)))

  private val listLeads = scenario("Lead pagination")
    .feed(pages)
    .exec(
      http("GET leads page")
        .get("/leads?page=#{page}&size=20")
        .check(status.is(200))
    )

  setUp(
    listLeads.inject(
      rampUsersPerSec(1).to(100).during(30.seconds),
      constantUsersPerSec(100).during(30.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(1000), // p99 < 1s for a read
      global.successfulRequests.percent.gt(99)
    )
}
