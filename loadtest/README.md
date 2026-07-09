# Load testing (Gatling) — T4.9

Gatling simulations live in [`src/gatling/scala/simulations`](../src/gatling/scala/simulations)
and run via the Gatling Gradle plugin against a **running** SalesPipe instance.

## Scenarios

| Simulation | Path exercised | What it stresses |
|---|---|---|
| `KanbanStageBurstSimulation` | `PATCH /deals/{id}/stage` | Write hot path: optimistic-locked update + outbox event under concurrency |
| `LeadPaginationSimulation` | `GET /leads?page=` | Indexed org-scoped read under load |
| `PixelStormSimulation` | `GET /emails/{id}/open` | Unauthenticated tracking-pixel spike + per-tenant limiter |

Kafka **consumer throughput** and no-loss-under-crash are covered by the
`ConsumerKillNoLossIT` chaos-lite integration test (kills a consumer mid-drain and
asserts exactly-once recovery), not by an HTTP simulation.

## Running

1. Bring the stack up: `docker compose up -d` (app on :8080).
2. Get a JWT and seed ids:
   ```bash
   TOKEN=$(curl -s localhost:8080/auth/register \
     -H 'content-type: application/json' \
     -d '{"orgName":"Load","email":"load@x.com","password":"password123"}' \
     | jq -r .accessToken)
   # create a deal + note its id and a target stage id via /deals, /deal-stages ...
   ```
3. Run a simulation, passing env-specific values as system properties:
   ```bash
   ./gradlew gatlingRun --simulation=simulations.KanbanStageBurstSimulation \
     -Dbase=http://localhost:8080 -Dtoken=$TOKEN -DdealId=$DEAL -DtoStageId=$STAGE
   ```
   The HTML report is written under `build/reports/gatling/`.

   > The `io.gatling.gradle` plugin compiles these Scala simulations via its bundled Zinc
   > compiler on first `gatlingRun`, which fetches the Scala compiler bridge — run once with
   > network access so it caches. If `gatlingRun` reports `ClassNotFoundException` for a
   > simulation, the Scala sources haven't been compiled yet; a clean online `./gradlew
   > gatlingRun` resolves it.

## Deriving pool / resource sizing

`spring.datasource.hikari.maximum-pool-size` (`DB_POOL_MAX`) and the K8s
`resources.requests/limits` should be set from the observed saturation point: raise
concurrency until p99 latency degrades or Hikari `pending` climbs (visible on the Grafana
DB-pool panel from T4.1), then size the pool just above the level that kept p99 within SLO.
Record the derivation in the root README results section alongside the numbers.

> **Environment honesty:** Gatling on a laptop yields laptop numbers. Always state the host
> (CPU/RAM), whether the DB/Kafka ran in the same Docker engine, and the concurrency used,
> so the published p99/lag figures are interpretable rather than absolute.
