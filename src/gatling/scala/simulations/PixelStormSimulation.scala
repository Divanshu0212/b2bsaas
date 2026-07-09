package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * T4.9: email-tracking pixel storm — a flood of unauthenticated open-tracking hits, the
 * public endpoint most exposed to bursty external traffic (mail clients, ESP proxies).
 * Verifies the pixel path (and its per-tenant rate limiter) holds up under a spike.
 *
 * A valid signed tracking URL comes from -DpixelPath=/emails/<id>/open?sig=<hmac>.
 */
class PixelStormSimulation extends Simulation {

  private val baseUrl = System.getProperty("base", "http://localhost:8080")
  private val pixelPath = System.getProperty("pixelPath", "/emails/unknown/open")

  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("image/gif")

  private val storm = scenario("Pixel storm").exec(
    http("GET tracking pixel")
      .get(pixelPath)
      // 200 (served pixel) or 404 (unknown/invalid id) both count as "handled without error"
      .check(status.in(200, 404))
  )

  setUp(
    storm.inject(
      rampUsersPerSec(10).to(300).during(30.seconds),
      constantUsersPerSec(300).during(30.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(1500),
      global.failedRequests.count.lt(1) // no 5xx: the pixel path must never error out
    )
}
