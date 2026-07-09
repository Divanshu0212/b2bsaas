# Phase 4 — Production Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make SalesPipe observable (metrics/traces/logs), fully resilient, DLQ-manageable, GDPR-compliant, and load-proven with published numbers.

**Architecture:** Single Spring Boot 3.4 modular monolith (`src/main/java/com/salespipe/*`) plus Python ML sidecar. Add an observability container stack (Prometheus, Grafana, Tempo, Loki) to `docker-compose.yml` + `k8s/`. Micrometer→Prometheus for metrics, OpenTelemetry→OTLP→Tempo for traces (propagated across the Kafka boundary via the existing `outbox_events.trace_id` column + message headers), logstash-encoder JSON logs→Loki. Resilience via resilience4j (partially present). DLQ/GDPR/rate-limit are ADMIN REST + jobs.

**Tech Stack:** Java 21, Spring Boot 3.4.1, Spring Modulith, Spring Kafka, resilience4j 2.4, bucket4j 8.14 + Redis, Micrometer, OpenTelemetry Java agent/starter, Grafana Tempo/Loki/Prometheus, SendGrid Java SDK, Gatling, Testcontainers, Flyway (Postgres).

## Global Constraints

- Commit identity: `divanshu0212 <divanshu0212@gmail.com>`. **No `Co-Authored-By: Claude` trailer.**
- One commit per task (T4.1…T4.9) on branch `phase-4-production-hardening`. Merge to `main` at end (that merge is done by the human/driver, not inside a task).
- Java toolchain 21; add deps only to root `build.gradle.kts`.
- Config via env-var-with-default pattern: `${ENV_VAR:default}` in `application.yml`.
- New Flyway migrations are append-only, next number after `V8` (`V9`, `V10`, …). Never edit an existing migration.
- Every outbound HTTP call MUST have a timeout — no unbounded waits.
- Secrets (SendGrid key) from env only; never committed. Absent key → no-op behavior, tests/demo still run.
- Tests use Testcontainers real infra for infra-boundary behavior; no mocking Postgres/Kafka/Redis in those.
- Follow existing package layout: `com.salespipe.<module>.{api,domain,infra,consumer,config}`.

---

## File structure (created/modified across the phase)

**Observability (T4.1–T4.3):**
- Create: `observability/prometheus.yml`, `observability/alerts.yml`, `observability/grafana/*.json`, `observability/tempo.yml`, `observability/loki.yml`, `observability/promtail.yml`
- Create: `src/main/java/com/salespipe/common/metrics/ConsumerLagMetrics.java`, `DlqDepthMetrics.java`, `RelayLagMetrics.java`
- Modify: `build.gradle.kts`, `docker-compose.yml`, `k8s/`, `application.yml`, `OutboxRecorder.java`, producer/consumer for header propagation, `logback-spring.xml`

**Resilience (T4.4):**
- Create: `src/main/java/com/salespipe/notification/infra/email/{EmailProvider,SendGridEmailProvider,NoopEmailProvider,EmailMessage,EmailProviderConfig}.java`
- Create: `src/main/java/com/salespipe/common/resilience/IdempotencyStore.java`
- Modify: notification consumers, `application.yml` (dbCalls bulkhead)

**DLQ / rate-limit / GDPR (T4.5–T4.7):**
- Create: `src/main/java/com/salespipe/eventing/admin/{DlqAdminController,DlqInspectionService,DlqMessage,DlqReplayService}.java`
- Create: `src/main/java/com/salespipe/common/ratelimit/ApiRateLimitFilter.java`, `ApiRateLimiter.java`
- Create: `src/main/java/com/salespipe/admin/{TenantDeletionController,TenantDeletionService}.java`, `src/main/java/com/salespipe/common/retention/RetentionJob.java`
- Create: `src/main/resources/db/migration/V9__*.sql` (as needed)

**Testing/load (T4.8–T4.9):**
- Create: `src/test/java/.../*IT.java` (bulkhead, idempotency, DLQ replay, tenant-delete, rate-limit fairness, chaos)
- Create: `loadtest/*.scala`, `loadtest/README.md`; modify root `README.md`

---

## Task 1 (T4.1): Metrics — Micrometer → Prometheus + custom meters

**Files:**
- Modify: `build.gradle.kts` (add `micrometer-registry-prometheus`)
- Create: `src/main/java/com/salespipe/common/metrics/DlqDepthMetrics.java`
- Create: `src/main/java/com/salespipe/common/metrics/ConsumerLagMetrics.java`
- Create: `src/main/java/com/salespipe/common/metrics/RelayLagMetrics.java`
- Create: `observability/prometheus.yml`, `observability/alerts.yml`, `observability/grafana/salespipe-dashboard.json`
- Modify: `docker-compose.yml`, `k8s/`, `application.yml`
- Test: `src/test/java/com/salespipe/common/metrics/PrometheusEndpointIT.java`, `DlqDepthMetricsTest.java`

**Interfaces:**
- Produces: `DlqDepthMetrics` bean binding gauge `salespipe_dlq_depth{topic=...}` sourced from a `DlqDepthSource` functional interface `long depth(String dlqTopic)`; `ConsumerLagMetrics` binding `salespipe_consumer_lag{group=...}`; `/actuator/prometheus` exposing these + `resilience4j_circuitbreaker_state` + `hikaricp_connections`.
- Consumes: existing `DlqPublisher` topic naming (`Topics.dlqFor`).

- [ ] **Step 1: Add Prometheus registry dep.** In `build.gradle.kts` after the actuator line add:
```kotlin
    implementation("io.micrometer:micrometer-registry-prometheus")
```

- [ ] **Step 2: Write failing test for `/actuator/prometheus`.** `PrometheusEndpointIT` (Spring Boot test, existing Testcontainers base if present, else `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `@AutoConfigureMockMvc`):
```java
@Test
void prometheusEndpointExposesJvmAndCustomMeters() throws Exception {
    mockMvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(containsString("hikaricp_connections")));
}
```

- [ ] **Step 3: Run, expect FAIL.** `./gradlew test --tests '*PrometheusEndpointIT'` → FAIL (endpoint 404, prometheus not in exposure list / dep missing).

- [ ] **Step 4: Expose prometheus + wire registry.** In `application.yml` the `management.endpoints.web.exposure.include` already lists `prometheus`; confirm and add `metrics`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,retries,metrics
```
(Dep from Step 1 supplies the registry; no code needed for JVM/Hikari meters — auto-bound.)

- [ ] **Step 5: Run, expect PASS.** `./gradlew test --tests '*PrometheusEndpointIT'` → PASS.

- [ ] **Step 6: Write failing unit test for DLQ depth gauge.** `DlqDepthMetricsTest`:
```java
@Test
void bindsGaugePerDlqTopic() {
    SimpleMeterRegistry reg = new SimpleMeterRegistry();
    DlqDepthMetrics.DlqDepthSource source = topic -> 7L;
    new DlqDepthMetrics(List.of("deals.events.DLQ"), source).bindTo(reg);
    assertThat(reg.get("salespipe.dlq.depth").tag("topic", "deals.events.DLQ").gauge().value())
        .isEqualTo(7.0);
}
```

- [ ] **Step 7: Run, expect FAIL** (class not defined). `./gradlew test --tests '*DlqDepthMetricsTest'`.

- [ ] **Step 8: Implement `DlqDepthMetrics`** (`MeterBinder`):
```java
package com.salespipe.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;

public class DlqDepthMetrics implements MeterBinder {

    @FunctionalInterface
    public interface DlqDepthSource { long depth(String dlqTopic); }

    private final List<String> dlqTopics;
    private final DlqDepthSource source;

    public DlqDepthMetrics(List<String> dlqTopics, DlqDepthSource source) {
        this.dlqTopics = dlqTopics;
        this.source = source;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String topic : dlqTopics) {
            Gauge.builder("salespipe.dlq.depth", () -> source.depth(topic))
                .tag("topic", topic)
                .description("Messages currently sitting in the DLQ topic")
                .register(registry);
        }
    }
}
```

- [ ] **Step 9: Run, expect PASS.** `./gradlew test --tests '*DlqDepthMetricsTest'`.

- [ ] **Step 10: Implement `DlqDepthSource` bean + `ConsumerLagMetrics` + `RelayLagMetrics`.** Add a `@Configuration` (e.g. `MetricsConfig`) that:
  - builds the DLQ topic list from `Topics` (all known event topics `dlqFor`-mapped),
  - supplies `DlqDepthSource` querying end offsets of each DLQ topic via an `AdminClient`/`KafkaConsumer` (`endOffsets` minus committed; for DLQ with no committed consumer, use raw end-offset count),
  - `ConsumerLagMetrics` computes per-group lag from `AdminClient.listConsumerGroupOffsets` vs `endOffsets`, gauge `salespipe.consumer.lag{group}`,
  - `RelayLagMetrics` gauges `salespipe.outbox.relay.lag` = count of `outbox_events WHERE published=false` (repository count query).
  Register all three `MeterBinder`s as beans.

- [ ] **Step 11: Create Prometheus scrape config** `observability/prometheus.yml`:
```yaml
global:
  scrape_interval: 10s
rule_files:
  - /etc/prometheus/alerts.yml
scrape_configs:
  - job_name: salespipe-app
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['app:8080']
```

- [ ] **Step 12: Create alert rules** `observability/alerts.yml`: groups for high DLQ depth (`salespipe_dlq_depth > 0` for 1m), consumer lag spike (`salespipe_consumer_lag > 1000` for 2m), CB open (`resilience4j_circuitbreaker_state{state="open"} == 1`), error-rate SLO burn (`rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]) / rate(http_server_requests_seconds_count[5m]) > 0.05`).

- [ ] **Step 13: Create Grafana dashboard JSON** `observability/grafana/salespipe-dashboard.json` with panels: request latency (p50/p95/p99 from `http_server_requests_seconds`), consumer lag, DLQ depth, ML scoring latency, CB state, Hikari pool. Provision via `observability/grafana/provisioning/{datasources,dashboards}/*.yml`.

- [ ] **Step 14: Add Prometheus + Grafana to compose.** In `docker-compose.yml` add `prometheus` (image `prom/prometheus:v2.54.1`, mount `./observability/prometheus.yml` + `alerts.yml`, port 9090) and `grafana` (image `grafana/grafana:11.2.0`, mount provisioning + dashboards, port 3000, `GF_SECURITY_ADMIN_PASSWORD` env). Mirror into `k8s/` as Deployments+Services+ConfigMaps.

- [ ] **Step 15: Manual verify.** `docker compose up -d prometheus grafana app`, open Grafana `:3000`, confirm panels render live. Induce a DLQ message (send malformed event) → DLQ-depth panel rises + alert fires in Prometheus `/alerts`.

- [ ] **Step 16: Commit.**
```bash
git add build.gradle.kts application.yml src/main/java/com/salespipe/common/metrics observability docker-compose.yml k8s src/test/java/com/salespipe/common/metrics
git commit -m "feat(observability): Micrometer→Prometheus metrics + Grafana dashboards + alerts (T4.1)"
```

---

## Task 2 (T4.2): Distributed tracing — OpenTelemetry → Tempo across Kafka boundary

**Files:**
- Modify: `build.gradle.kts` (OTel Spring Boot starter)
- Modify: `src/main/java/com/salespipe/eventing/outbox/OutboxRecorder.java` (capture real trace_id)
- Modify: `src/main/java/com/salespipe/eventing/outbox/PollingRelay.java` + `producer/EventPublisher.java` (inject `traceparent` header from stored trace_id)
- Modify: `src/main/java/com/salespipe/eventing/consumer/IdempotentConsumer.java` (extract `traceparent` header → span context)
- Create: `observability/tempo.yml`
- Modify: `application.yml`, `docker-compose.yml`, `k8s/`
- Test: `src/test/java/com/salespipe/eventing/TraceHeaderPropagationIT.java`

**Interfaces:**
- Consumes: `OutboxEvent.getTraceId()` (existing column), `DealStageChanged` event flow.
- Produces: `traceparent` Kafka header on every relayed message; `OutboxRecorder` stores W3C traceparent string in `trace_id`.

- [ ] **Step 1: Add OTel starter dep.** `build.gradle.kts`:
```kotlin
    implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.10.0")
```
(Add OTel instrumentation BOM to `dependencyManagement.imports`: `mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.10.0")` and drop the version from the starter line.)

- [ ] **Step 2: Configure OTLP export in `application.yml`:**
```yaml
otel:
  service:
    name: salespipe
  traces:
    exporter: otlp
  metrics:
    exporter: none
  logs:
    exporter: none
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
      protocol: grpc
```

- [ ] **Step 3: Write failing integration test for header propagation.** `TraceHeaderPropagationIT` (Testcontainers Kafka): record an outbox event within an active span, run the relay, assert the produced Kafka message carries a `traceparent` header whose trace-id equals the active span's trace-id.
```java
@Test
void relayedMessageCarriesTraceparentMatchingOutboxTraceId() {
    // given a stored outbox event with trace_id = a valid W3C traceparent
    // when PollingRelay publishes it
    // then the ProducerRecord has header "traceparent" and its trace-id segment matches
    assertThat(header(record, "traceparent")).contains(expectedTraceId);
}
```

- [ ] **Step 4: Run, expect FAIL.** `./gradlew test --tests '*TraceHeaderPropagationIT'` (no header injected yet).

- [ ] **Step 5: Capture real trace_id at outbox write.** In `OutboxRecorder`, replace `MDC.get("trace_id")` with the current W3C traceparent from OTel `Span.current().getSpanContext()` (format `00-<traceId>-<spanId>-01`), falling back to `MDC.get("trace_id")` then `null`. Keep the method signatures unchanged.

- [ ] **Step 6: Inject `traceparent` header in relay/producer.** In `PollingRelay` (and `EventPublisher` if it builds `ProducerRecord`s), when `outboxEvent.getTraceId()` is non-null add `record.headers().add("traceparent", traceId.getBytes(UTF_8))`.

- [ ] **Step 7: Extract header in consumer.** In `IdempotentConsumer.consume(...)`, read the `traceparent` header and use OTel `W3CTraceContextPropagator` to set the extracted context as parent for the handler span (wrap `handle(...)` in a span made child of the extracted context). No-op when header absent.

- [ ] **Step 8: Run, expect PASS.** `./gradlew test --tests '*TraceHeaderPropagationIT'` → PASS.

- [ ] **Step 9: Add Tempo to compose.** `observability/tempo.yml` (OTLP receiver on 4317, local storage). `docker-compose.yml`: `tempo` service (image `grafana/tempo:2.6.0`, ports 4317/3200), set app `OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317`. Add Tempo as a Grafana datasource (provisioning file from Task 1). Mirror into `k8s/`.

- [ ] **Step 10: Manual verify one connected trace.** Compose up. `PATCH /deals/{id}/stage`. In Grafana Explore→Tempo, find the trace; confirm spans: HTTP PATCH → DB write → outbox → relay produce → each consumer → DB write → notification, all under one trace-id.

- [ ] **Step 11: Commit.**
```bash
git add build.gradle.kts application.yml src/main/java/com/salespipe/eventing observability/tempo.yml docker-compose.yml k8s src/test/java/com/salespipe/eventing/TraceHeaderPropagationIT.java
git commit -m "feat(observability): OpenTelemetry tracing propagated across Kafka boundary → Tempo (T4.2)"
```

---

## Task 3 (T4.3): Structured logging with org_id/trace_id/user_id → Loki (STRETCH)

**Files:**
- Modify: `src/main/resources/logback-spring.xml` (JSON encoder + MDC fields, confirm stdout)
- Modify: MDC population point (a servlet filter, e.g. new `src/main/java/com/salespipe/common/logging/MdcContextFilter.java`)
- Create: `observability/loki.yml`, `observability/promtail.yml`
- Modify: `docker-compose.yml`, `k8s/`
- Test: `src/test/java/com/salespipe/common/logging/JsonLogFieldsTest.java`

**Interfaces:**
- Consumes: OTel span context (trace_id from Task 2), `TenantContext.getOrgId()`, authenticated principal.
- Produces: JSON log lines on stdout carrying `org_id`, `trace_id`, `user_id`.

- [ ] **Step 1: Write failing test that MDC carries org_id/trace_id/user_id.** `JsonLogFieldsTest`: drive a request through `MdcContextFilter` with a populated `TenantContext` + authentication + active span; capture MDC inside the chain; assert all three keys present. (Use a `MockFilterChain` capturing MDC.)

- [ ] **Step 2: Run, expect FAIL** (filter not defined).

- [ ] **Step 3: Implement `MdcContextFilter`** (`OncePerRequestFilter`): put `org_id` (from `TenantContext`), `trace_id` (from current span, W3C traceId), `user_id` (from `SecurityContext` principal) into MDC in `doFilterInternal`, clear in `finally`. Register with high precedence after security.

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Confirm JSON encoder in `logback-spring.xml`.** Ensure a `LogstashEncoder`/`LoggingEventCompositeJsonEncoder` on the stdout appender includes MDC. Add `<includeMdcKeyName>org_id</includeMdcKeyName>` etc. if using composite.

- [ ] **Step 6: Add Loki + Promtail to compose (STRETCH).** `observability/loki.yml`, `observability/promtail.yml` (scrape container stdout, parse JSON, label by `org_id`). `docker-compose.yml`: `loki` (image `grafana/loki:3.2.0`, port 3100) + `promtail` (image `grafana/promtail:3.2.0`, mount docker socket + config). Add Loki as Grafana datasource; configure trace↔log correlation (`derivedFields` on `trace_id`). Mirror into `k8s/`.

- [ ] **Step 7: Manual verify.** Compose up. Generate traffic. In Grafana Explore→Loki, query `{job="salespipe"}`, confirm JSON with `trace_id`; click a `trace_id` derived-field link → jumps to the Tempo trace.

- [ ] **Step 8: Commit.**
```bash
git add src/main/resources/logback-spring.xml src/main/java/com/salespipe/common/logging observability/loki.yml observability/promtail.yml docker-compose.yml k8s src/test/java/com/salespipe/common/logging
git commit -m "feat(observability): JSON logs w/ org_id/trace_id/user_id + Loki aggregation + log↔trace correlation (T4.3)"
```

---

## Task 4 (T4.4): Resilience completion — SendGrid email + bulkhead + idempotency + timeouts

**Files:**
- Modify: `build.gradle.kts` (SendGrid SDK)
- Create: `src/main/java/com/salespipe/notification/infra/email/EmailMessage.java`
- Create: `src/main/java/com/salespipe/notification/infra/email/EmailProvider.java`
- Create: `src/main/java/com/salespipe/notification/infra/email/SendGridEmailProvider.java`
- Create: `src/main/java/com/salespipe/notification/infra/email/NoopEmailProvider.java`
- Create: `src/main/java/com/salespipe/notification/infra/email/EmailProviderConfig.java`
- Create: `src/main/java/com/salespipe/common/resilience/IdempotencyStore.java`
- Modify: `HotLeadNotificationConsumer.java`, `DealStageChangedNotificationConsumer.java`
- Modify: `application.yml` (email CB/retry/timelimiter, `dbCalls` bulkhead)
- Test: `EmailIdempotencyIT.java`, `EmailProviderCircuitBreakerTest.java`, `BulkheadIsolationIT.java` (bulkhead proof shared with T4.8)

**Interfaces:**
- Produces: `EmailProvider.send(EmailMessage msg, String idempotencyKey)`; `EmailMessage(String toEmail, String subject, String body)`; `IdempotencyStore.firstSeen(String key, Duration ttl): boolean` (true if key not seen before → caller proceeds; false if duplicate → caller skips).
- Consumes: notification consumers call `emailProvider.send(...)` after `notifications.save(...)`; resilience4j instances `emailProvider` (CB/retry/timelimiter) + `dbCalls` (bulkhead).

- [ ] **Step 1: Add SendGrid dep.** `build.gradle.kts`:
```kotlin
    implementation("com.sendgrid:sendgrid-java:4.10.3")
```

- [ ] **Step 2: Write failing test for idempotency dedupe.** `EmailIdempotencyIT` (Testcontainers Redis): send same idempotency key twice through a recording `EmailProvider`; assert underlying send invoked once.
```java
@Test
void sameIdempotencyKeySendsOnce() {
    assertThat(store.firstSeen("k1", Duration.ofHours(1))).isTrue();
    assertThat(store.firstSeen("k1", Duration.ofHours(1))).isFalse();
}
```

- [ ] **Step 3: Run, expect FAIL** (`IdempotencyStore` undefined).

- [ ] **Step 4: Implement `IdempotencyStore`** (Redis `SETNX` + TTL via `StringRedisTemplate`):
```java
package com.salespipe.common.resilience;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyStore {
    private final StringRedisTemplate redis;
    public IdempotencyStore(StringRedisTemplate redis) { this.redis = redis; }

    /** true if key was not seen before (caller should proceed); false if duplicate. */
    public boolean firstSeen(String key, Duration ttl) {
        Boolean set = redis.opsForValue().setIfAbsent("idem:" + key, "1", ttl);
        return Boolean.TRUE.equals(set);
    }
}
```

- [ ] **Step 5: Run, expect PASS** on the store test.

- [ ] **Step 6: Define `EmailMessage` + `EmailProvider`:**
```java
public record EmailMessage(String toEmail, String subject, String body) {}

public interface EmailProvider {
    void send(EmailMessage message, String idempotencyKey);
}
```

- [ ] **Step 7: Write failing CB test for provider.** `EmailProviderCircuitBreakerTest`: wrap a failing `EmailProvider` in the `emailProvider` CB; after failures exceed the window, CB opens and short-circuits (throws `CallNotPermittedException`), send not attempted.

- [ ] **Step 8: Run, expect FAIL.**

- [ ] **Step 9: Implement `SendGridEmailProvider`** — calls SendGrid `Mail`/`SendGrid.api`, annotated `@CircuitBreaker(name="emailProvider")` `@Retry(name="emailProvider")` `@TimeLimiter`-equivalent (or wrap in the timelimiter); dedupe via `IdempotencyStore.firstSeen(idempotencyKey, ttl)` — skip send if false. From-address + key from config (`app.email.*`).

- [ ] **Step 10: Implement `NoopEmailProvider`** — logs the message, no network. Used when `app.email.enabled=false` / no API key.

- [ ] **Step 11: `EmailProviderConfig`** — `@ConditionalOnProperty(name="app.email.enabled", havingValue="true")` → `SendGridEmailProvider`; else `NoopEmailProvider`. Bind `app.email.{enabled,api-key,from}` with env defaults.

- [ ] **Step 12: Add resilience config to `application.yml`.** Under `resilience4j` add `circuitbreaker.instances.emailProvider`, `retry.instances.emailProvider`, `timelimiter.instances.emailProvider`, and `bulkhead.instances.dbCalls` (separate pool from `scoringService`). Concrete values: emailProvider CB sliding-window 10 / failure-rate 50 / wait-in-open 30s; retry max-attempts 3 exp-backoff; timelimiter 5s; dbCalls bulkhead max-concurrent 32.

- [ ] **Step 13: Run, expect PASS** on the CB test.

- [ ] **Step 14: Wire providers into notification consumers.** In `HotLeadNotificationConsumer.handle` and `DealStageChangedNotificationConsumer`, after `notifications.save(...)`, resolve owner email (via a lookup) and call `emailProvider.send(new EmailMessage(email, subject, body), idempotencyKey)` where `idempotencyKey = consumerGroup()+":"+event.eventId()` (stable across redelivery). Guard `email != null`.

- [ ] **Step 15: Write bulkhead isolation IT.** `BulkheadIsolationIT` (shared w/ T4.8): induce ML slowness (WireMock delay > timelimiter) and confirm concurrent scoring calls saturate only the `scoringService` bulkhead while request threads / DB-bulkhead calls stay responsive (assert a concurrent non-ML endpoint keeps returning < X ms).

- [ ] **Step 16: Run all Task-4 tests, expect PASS.** `./gradlew test --tests '*Email*' --tests '*BulkheadIsolationIT'`.

- [ ] **Step 17: Commit.**
```bash
git add build.gradle.kts application.yml src/main/java/com/salespipe/notification/infra/email src/main/java/com/salespipe/common/resilience src/main/java/com/salespipe/notification/consumer src/test/java
git commit -m "feat(resilience): SendGrid email behind CB+retry+timeout, DB bulkhead, idempotency-key dedupe (T4.4)"
```

---

## Task 5 (T4.5): DLQ management — inspect + replay

**Files:**
- Create: `src/main/java/com/salespipe/eventing/admin/DlqMessage.java`
- Create: `src/main/java/com/salespipe/eventing/admin/DlqInspectionService.java`
- Create: `src/main/java/com/salespipe/eventing/admin/DlqReplayService.java`
- Create: `src/main/java/com/salespipe/eventing/admin/DlqAdminController.java`
- Test: `src/test/java/com/salespipe/eventing/admin/DlqReplayIT.java`

**Interfaces:**
- Consumes: `DlqPublisher.HEADER_ORIGINAL_TOPIC`/`HEADER_FAILURE_REASON`/`HEADER_ATTEMPTS`, `Topics.dlqFor`.
- Produces: `GET /admin/dlq` (list: `DlqMessage(topic, key, reason, attempts, offset, partition)`), `GET /admin/dlq/count`, `POST /admin/dlq/replay` (re-publish DLQ record to its original topic). ADMIN-only.

- [ ] **Step 1: Write failing replay IT.** `DlqReplayIT` (Testcontainers Kafka): publish a message to `<topic>.DLQ` with `x-original-topic` header; call replay service; assert a copy lands on the original topic with the same key/value.

- [ ] **Step 2: Run, expect FAIL** (service undefined).

- [ ] **Step 3: Implement `DlqMessage`** record + `DlqInspectionService` — consumes (peek) from all `*.DLQ` topics via a manual `KafkaConsumer` with `enable.auto.commit=false`, `seekToBeginning`, poll up to `limit`, map headers → `DlqMessage`. Provide `list(topic, limit)` and `count(topic)` (end-offset based).

- [ ] **Step 4: Implement `DlqReplayService.replay(dlqTopic, partition, offset)`** — seek to the exact record, read it, re-publish raw value + key to `HEADER_ORIGINAL_TOPIC`, commit the DLQ offset past it so it's not replayed twice.

- [ ] **Step 5: Run, expect PASS.**

- [ ] **Step 6: Implement `DlqAdminController`** — `@PreAuthorize("hasRole('ADMIN')")`, endpoints `GET /admin/dlq`, `GET /admin/dlq/count`, `POST /admin/dlq/replay`. Add MockMvc slice test asserting non-ADMIN → 403.

- [ ] **Step 7: Run controller test, expect PASS.**

- [ ] **Step 8: Commit.**
```bash
git add src/main/java/com/salespipe/eventing/admin src/test/java/com/salespipe/eventing/admin
git commit -m "feat(eventing): DLQ inspect + replay admin API (T4.5)"
```

---

## Task 6 (T4.6): Rate limiting — broaden to per-tenant API quotas

**Files:**
- Create: `src/main/java/com/salespipe/common/ratelimit/ApiRateLimiter.java`
- Create: `src/main/java/com/salespipe/common/ratelimit/ApiRateLimitFilter.java`
- Modify: `application.yml` (quota config), security filter chain registration
- Test: `src/test/java/com/salespipe/common/ratelimit/ApiRateLimitFairnessIT.java`

**Interfaces:**
- Consumes: existing bucket4j `ProxyManager` pattern (see `emailtracking/security/RateLimiter`), `TenantContext.getOrgId()`.
- Produces: `ApiRateLimiter.tryConsume(UUID orgId): boolean`; `ApiRateLimitFilter` returning HTTP 429 with `Retry-After` when a tenant's authenticated-API budget is exhausted.

- [ ] **Step 1: Write failing fairness IT.** `ApiRateLimitFairnessIT` (Testcontainers Redis): tenant A bursts past its quota → gets 429; tenant B's requests in the same window still return 200.
```java
@Test
void oneTenantBurstDoesNotThrottleAnother() {
    exhaust(orgA);
    assertThat(call(orgA)).isEqualTo(429);
    assertThat(call(orgB)).isEqualTo(200);
}
```

- [ ] **Step 2: Run, expect FAIL** (filter undefined).

- [ ] **Step 3: Implement `ApiRateLimiter`** — same LettuceBasedProxyManager pattern as `RateLimiter`, key `"api:" + orgId`, capacity/refill from `app.api-rate-limit.*`.

- [ ] **Step 4: Implement `ApiRateLimitFilter`** (`OncePerRequestFilter`) — after auth/tenant resolution, on authenticated API paths, `if (!apiRateLimiter.tryConsume(orgId)) { response.setStatus(429); response.setHeader("Retry-After","60"); return; }`. Skip the already-limited `/emails/**` public paths and actuator.

- [ ] **Step 5: Register filter** in the security chain after tenant resolution. Add `app.api-rate-limit.{capacity,refill-per-minute}` to `application.yml` with env defaults.

- [ ] **Step 6: Run, expect PASS.**

- [ ] **Step 7: Commit.**
```bash
git add src/main/java/com/salespipe/common/ratelimit application.yml src/test/java/com/salespipe/common/ratelimit
git commit -m "feat(ratelimit): per-tenant API quotas w/ fairness (T4.6)"
```

---

## Task 7 (T4.7): GDPR hard-delete + retention job

**Files:**
- Create: `src/main/java/com/salespipe/admin/TenantDeletionService.java`
- Create: `src/main/java/com/salespipe/admin/TenantDeletionController.java`
- Create: `src/main/java/com/salespipe/common/retention/RetentionJob.java`
- Modify: `application.yml` (retention windows)
- Test: `src/test/java/com/salespipe/admin/TenantHardDeleteIT.java`

**Interfaces:**
- Produces: `TenantDeletionService.hardDelete(UUID orgId)` — deletes all rows for `org_id` across every table + drops that tenant's archived partitions; `DELETE /admin/tenants/{orgId}` (ADMIN-only); `RetentionJob` `@Scheduled` dropping `activities`/`email_events` partitions past the window.
- Consumes: the full table list (enumerate every `org_id`-scoped table from migrations V1–V8).

- [ ] **Step 1: Write failing hard-delete IT.** `TenantHardDeleteIT` (Testcontainers Postgres): seed two orgs with rows in every table (deals, leads, activities, email_events, notifications, lead_features, lead_scores, outbox_events, audit_log, processed_events…); `hardDelete(orgA)`; assert **zero** rows remain for orgA in every table AND orgB's rows untouched.

- [ ] **Step 2: Run, expect FAIL** (service undefined).

- [ ] **Step 3: Implement `TenantDeletionService.hardDelete`** — `@Transactional`, delete in FK-safe order across all `org_id` tables (child→parent). Enumerate tables explicitly (a `List<String>` constant, ordered) so a new table forces a conscious edit. Then detach/drop any archived partitions holding only that tenant's data. Log a summary (table→rows deleted).

- [ ] **Step 4: Run, expect PASS.**

- [ ] **Step 5: Implement `TenantDeletionController`** — `DELETE /admin/tenants/{orgId}`, `@PreAuthorize("hasRole('ADMIN')")`. MockMvc test: non-ADMIN → 403.

- [ ] **Step 6: Implement `RetentionJob`** — `@Scheduled(cron=...)` drops `activities`/`email_events` partitions older than `app.retention.activities-days` / `app.retention.email-events-days`. Config in `application.yml` with env defaults. Guard behind `app.retention.enabled`.

- [ ] **Step 7: Write retention IT** (or unit against a partition-list helper): a partition past the window is dropped, one inside is kept.

- [ ] **Step 8: Run, expect PASS.**

- [ ] **Step 9: Commit.**
```bash
git add src/main/java/com/salespipe/admin src/main/java/com/salespipe/common/retention application.yml src/test/java/com/salespipe/admin
git commit -m "feat(admin): GDPR tenant hard-delete + partition retention job (T4.7)"
```

---

## Task 8 (T4.8): Testcontainers suite consolidation

**Files:**
- Create/Modify: shared `src/test/java/com/salespipe/support/IntegrationTestBase.java` (single reusable Postgres+Kafka+Redis+schema-registry container set, `@Testcontainers` + `@DynamicPropertySource`)
- Modify: existing ITs to extend the base (remove per-test container duplication)
- Modify: `build.gradle.kts` (JaCoCo coverage gate if not present)
- Test: the full `./gradlew test` suite

**Interfaces:**
- Produces: `IntegrationTestBase` starting all four infra containers once (static, reused across ITs via singleton-container pattern); `@DynamicPropertySource` wiring `spring.datasource.*`, `spring.kafka.bootstrap-servers`, `spring.data.redis.*`, `SCHEMA_REGISTRY_URL`.

- [ ] **Step 1: Implement `IntegrationTestBase`** with singleton containers (static fields, started in a static block — Testcontainers reuses them across subclasses). Include Postgres 16, Kafka (confluent 7.7.1), Redis 7, cp-schema-registry 7.7.1.

- [ ] **Step 2: Migrate existing ITs** (metrics, tracing, email, DLQ, rate-limit, tenant-delete) to extend `IntegrationTestBase`; delete duplicated `@Container` declarations. No behavior change — same assertions.

- [ ] **Step 3: Add JaCoCo coverage gate** in `build.gradle.kts` (`jacoco` plugin + `jacocoTestCoverageVerification` with a floor, e.g. 0.70 line on `com.salespipe.*`, wired into `check`).

- [ ] **Step 4: Run full suite green.** `./gradlew clean test jacocoTestCoverageVerification` → PASS, coverage gate met.

- [ ] **Step 5: Commit.**
```bash
git add src/test/java/com/salespipe/support build.gradle.kts src/test/java
git commit -m "test(ci): consolidated Testcontainers integration suite + coverage gate (T4.8)"
```

---

## Task 9 (T4.9): Load testing (Gatling) + published numbers + STRETCH chaos

**Files:**
- Modify: `build.gradle.kts` (Gatling gradle plugin) OR standalone `loadtest/` sbt/maven — plan uses the Gatling Gradle plugin.
- Create: `src/gatling/scala/simulations/{KanbanStageBurst,LeadPagination,ConsumerThroughput,PixelStorm}Simulation.scala`
- Create: `loadtest/README.md`; Modify root `README.md` (results section)
- Create: `src/test/java/com/salespipe/chaos/ConsumerKillNoLossIT.java` (STRETCH)

**Interfaces:**
- Consumes: running app + infra (compose). Produces: Gatling HTML report; README p99 + consumer-lag figures; HikariCP pool size + k8s resource requests justified by results.

- [ ] **Step 1: Add Gatling plugin.** `build.gradle.kts` plugins block: `id("io.gatling.gradle") version "3.13.1.2"`.

- [ ] **Step 2: Write `KanbanStageBurstSimulation`** — concurrent `PATCH /deals/{id}/stage`, ramp to N concurrent users, assert 95th/99th percentile thresholds present in the report.

- [ ] **Step 3: Write remaining simulations** — `LeadPaginationSimulation` (`GET /leads?page=`), `ConsumerThroughputSimulation` (produce burst, measure drain), `PixelStormSimulation` (`GET /emails/pixel/...` flood).

- [ ] **Step 4: Run Gatling against compose stack.** `./gradlew gatlingRun` with app up. Capture the HTML report.

- [ ] **Step 5: Derive sizing.** From results, set HikariCP `maximum-pool-size` in `application.yml` and k8s `resources.requests/limits`; note the derivation in `loadtest/README.md`.

- [ ] **Step 6: Publish numbers in root README.** Add a "Load test results" section: environment (honest: laptop specs), p99 latency per scenario, consumer lag under load, and the pool/resource sizing justified by these numbers.

- [ ] **Step 7 (STRETCH): Chaos-lite IT.** `ConsumerKillNoLossIT` — mid-processing, stop a consumer container/thread; restart; assert every produced message is eventually processed exactly once (outbox + `processed_events` idempotency guarantees no loss/dupe).

- [ ] **Step 8: Run chaos IT, expect PASS.**

- [ ] **Step 9: Commit.**
```bash
git add build.gradle.kts src/gatling loadtest README.md application.yml k8s src/test/java/com/salespipe/chaos
git commit -m "test(load): Gatling scenarios + published p99/lag numbers + chaos-lite no-loss (T4.9)"
```

---

## Final (after all tasks)

Human/driver merges the branch:
```bash
git checkout main
git merge --no-ff phase-4-production-hardening
git push origin main
```

---

## Self-review notes

- **Spec coverage:** T4.1→Task1, T4.2→Task2, T4.3→Task3, T4.4→Task4, T4.5→Task5, T4.6→Task6, T4.7→Task7, T4.8→Task8, T4.9+chaos→Task9. All spec sections mapped.
- **Type consistency:** `EmailProvider.send(EmailMessage, String)`, `EmailMessage(toEmail,subject,body)`, `IdempotencyStore.firstSeen(String,Duration):boolean`, `DlqDepthMetrics.DlqDepthSource.depth(String):long`, `ApiRateLimiter.tryConsume(UUID):boolean` — used consistently across tasks.
- **No placeholders:** each code step shows concrete code or exact config keys; infra JSON/YAML files named with concrete image versions.
