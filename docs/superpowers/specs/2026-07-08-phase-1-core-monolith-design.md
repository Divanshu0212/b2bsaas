# Phase 1 — Core CRUD Monolith — Design

**Status:** Approved 2026-07-08
**Source of truth:** [`docs/plan/phase-1-core-monolith.md`](../../plan/phase-1-core-monolith.md) and [`docs/plan/00-overview.md`](../../plan/00-overview.md). This design records the concrete technical choices made to execute Phase 1; where it is silent, the plan governs.

## Goal

A running, dockerized, K8s-deployed Spring Modulith monolith with auth, multi-tenancy, CRM core (accounts/contacts/leads), and Kanban deals. No Kafka, no ML. Demo: log in as a seeded org's user, create leads, move a deal across stages via the API with stage history recorded, all tenant-isolated. Runs via `docker compose up` locally and on a single K8s Deployment.

## Environment constraints

- **No local JDK or Gradle on the host.** Docker, `docker compose`, `kind`, and `kubectl` are available.
- Build and tests run **inside containers** (`eclipse-temurin:21`). Testcontainers uses the host Docker socket. The Gradle **wrapper** is committed so no host Gradle is required.

## Stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (virtual threads available) |
| Framework | Spring Boot 3.4.x |
| Modularity | Spring Modulith 1.3.x |
| Build | Gradle, **Kotlin DSL** (`build.gradle.kts`) + committed wrapper |
| DB | PostgreSQL 16 + Flyway; HikariCP pool |
| Cache / tokens | Redis 7 (refresh-token mirror) |
| Security | Spring Security 6, stateless JWT (jjwt), BCrypt |
| Mapping / validation | MapStruct, Bean Validation (Hibernate Validator) |
| API docs | springdoc-openapi (Swagger UI) |
| Logging | Logback + logstash JSON encoder, MDC (`org_id`, `user_id`) |
| Testing | JUnit 5, Mockito, Testcontainers (Postgres + Redis), RestAssured, ArchUnit |

## Module layout (Spring Modulith, overview §9)

Packages under `com.salespipe`: `identity`, `crmcore`, `pipeline`, `activity`, `emailtracking`, `scoring`, `notification`, `eventing`, `reporting`, `common`. Each declares a module via `package-info.java`. Phase 1 fills only `identity`, `crmcore`, `pipeline`, `common`; the rest ship as empty module stubs so the boundary test is wired from day one.

`ApplicationModules.of(SalesPipeApplication.class).verify()` runs as a test and is a CI gate. Cross-module calls only via public API interface or (later) domain events — never a foreign repository.

## Data — `V1__init.sql`

Enable `citext` extension. Tables (schemas per overview §4): `organizations`, `users`, `refresh_tokens`, `accounts`, `contacts`, `leads`, `deal_stages`, `deals`, `deal_stage_history`, `audit_log`. (`activities`/`email_events`/scoring tables are deferred to their phases.)

- Composite `(org_id, …)` indexes on hot query paths.
- `version INT` on `leads` and `deals` for `@Version` optimistic locking.
- `UNIQUE (org_id, position)` on `deal_stages`; reorder transactionally.
- `UNIQUE (org_id, email)` on `users`; email is `CITEXT`.
- `refresh_tokens`: `family_id`, `parent_id`, `used`, `token_hash` (SHA-256), `expires_at`, index on `token_hash`.

## Tenant isolation (T1.3)

- Request-scoped `TenantContext` bean holding `org_id`, populated by a servlet filter from the authenticated JWT `org_id` claim — **never** from the request body.
- Hibernate `@Filter` on every `org_id` entity, enabled per request via a JPA session interceptor/aspect.
- **ArchUnit test** fails the build if any repository method could return cross-tenant rows without the filter. Written early — it is the safety net.
- Acceptance: integration test proves org A cannot read org B's leads with a guessed id.

## Identity — auth + RBAC (T1.4)

- `POST /auth/register`: create org + first ADMIN user.
- `POST /auth/login`: BCrypt verify → ~15-min access JWT + rotating refresh token.
- Refresh token stored **hashed** (SHA-256) with `family_id`/`parent_id`/`used`; Redis mirror for O(1) revoke. `POST /auth/refresh` rotates; presenting a `used=true` token = **reuse detection** → revoke the entire `family_id` and 401.
- `POST /auth/logout` revokes the family.
- RBAC `@PreAuthorize` over ADMIN / MANAGER / SALES_REP.
- Acceptance: login→refresh→refresh works; replaying a rotated token revokes family + 401s; a role-gated endpoint denies SALES_REP.

## CRM core (T1.5)

CRUD for accounts, contacts, leads. DTOs via MapStruct, Bean Validation on inputs. Leads carry `owner_id`, `status`, `source`, `raw_notes`, `version`. `GET /leads?status=&owner=&page=` paginated + filtered, tenant-scoped. Acceptance: CRUD tenant-scoped; list returns only caller's org.

## Pipeline — Kanban (T1.6)

- Org-configurable `deal_stages`, seeded defaults NEW → QUALIFICATION → PROPOSAL → WON / LOST.
- `deals` CRUD. `PATCH /deals/{id}/stage` moves stage under `@Version` optimistic lock → **409** on concurrent-drag conflict; writes a `deal_stage_history` row and sets `entered_stage_at`.
- `GET /deals/pipeline` returns deals grouped by stage.
- Acceptance: two concurrent stage PATCHes → one 200, one 409; history row written; pipeline groups correctly.

## Cross-cutting (T1.7, T1.8)

- Global `@ControllerAdvice` → **RFC 7807** problem+json error envelope.
- Audit aspect writes `audit_log` rows (actor, action, entity, JSONB diff) on mutating ops.
- Structured JSON logging, MDC carries `org_id` / `user_id` (`trace_id` added Phase 4).
- springdoc-openapi Swagger UI at `/swagger-ui.html`; all Phase 1 endpoints appear with schemas.

## Delivery (T1.9, T1.10)

- Multi-stage Dockerfile: `eclipse-temurin:21-jdk` build stage → slim JRE / distroless runtime.
- `docker-compose.yml`: app + Postgres + Redis.
- `k8s/`: Deployment + Service + ConfigMap + Secret for the app; Postgres/Redis as StatefulSet + PVC locally (managed alternative documented). Actuator `/health/liveness`, `/health/readiness`, `startupProbe` wired.
- Acceptance: `docker compose up` yields a working app (login succeeds); `kubectl apply` on kind → pod Ready, reachable through Service.

## Testing (Phase 1)

- **Unit:** stage-transition logic, refresh-token rotation/reuse, tenant-context resolution.
- **Integration** (Testcontainers Postgres + Redis): auth flow, tenant isolation, optimistic-lock 409, Flyway migration.
- **Contract** (RestAssured): endpoints match OpenAPI.
- **Arch** (Spring Modulith + ArchUnit): module boundaries + tenant-filter enforcement.

## Endpoints (Phase 1)

`POST /auth/register` · `POST /auth/login` · `POST /auth/refresh` · `POST /auth/logout` · CRUD `/accounts` `/contacts` `/leads` · `GET /leads` (filter/page) · CRUD `/deals` · `PATCH /deals/{id}/stage` · `GET /deals/pipeline` · CRUD `/deal-stages`.

## Build & commit conventions

- `.gitignore` for Java/Gradle (+ Python for later phases) before the first code commit.
- Commit **per logical group**: (1) scaffold + module boundaries, (2) DB + tenant isolation, (3) auth + RBAC, (4) CRM + pipeline, (5) cross-cutting + docs, (6) Docker + K8s. Conventional-commit messages.
- Author/committer `divanshu0212 <divanshu0212@gmail.com>`. **No `Co-Authored-By: Claude` trailer** (CLAUDE.md rule).

## Risks / gotchas

- A missed `@Filter` enable = silent cross-tenant leak — the ArchUnit test is the net; write it early.
- `CITEXT` needs the `citext` extension created in `V1`.
- `deal_stages.position` collisions — enforce `UNIQUE (org_id, position)`, reorder transactionally.
