# Phase 1 — Core CRUD Monolith

**Goal:** A running, dockerized, K8s-deployed modular monolith with auth, multi-tenancy, and the CRM core (accounts/contacts/leads) + Kanban deals. No Kafka yet, no ML.

**Demo at end:** Log in as a seeded org's user, create leads, drag a deal across Kanban stages via the API, see stage history recorded. All data tenant-isolated. Runs `docker compose up` locally and on a single K8s Deployment.

**Prerequisites:** none (greenfield).

---

## Tasks

### T1.1 — Project scaffold & module boundaries
- Spring Boot 3.x, Java 21, Gradle (or Maven). Spring Modulith dependency.
- Create packages per §9 of overview: `identity`, `crmcore`, `pipeline`, `activity`, `emailtracking`, `scoring`, `notification`, `eventing`, `reporting`, `common`. Empty `package-info.java` declaring each module for now.
- Add `ApplicationModules.of(App.class).verify()` as a test → CI gate from day one.
- **Files:** `build.gradle`, `SalesPipeApplication.java`, `*/package-info.java`, `ModuleBoundaryTest.java`.
- **Accept:** app boots; module verification test passes.

### T1.2 — Postgres + Flyway baseline
- Flyway `V1__init.sql` creating: `organizations`, `users`, `refresh_tokens`, `accounts`, `contacts`, `leads`, `deal_stages`, `deals`, `deal_stage_history`, `audit_log`. (Schemas per overview §4; `activities`/`email_events`/scoring tables deferred to their phases.)
- Composite `(org_id, …)` indexes on hot paths. `version INT` columns on `leads`, `deals`.
- HikariCP config (overview §6.4).
- **Files:** `V1__init.sql`, `application.yml` (datasource, Hikari, Flyway).
- **Accept:** Flyway migrates clean on empty DB; Testcontainers Postgres brings schema up in a test.

### T1.3 — Tenant context + Hibernate filter
- Request-scoped `TenantContext` bean holding `org_id`, populated by a servlet filter from the authenticated JWT claim — **never** request body.
- Hibernate `@Filter` on every `org_id` entity, enabled per-request in an interceptor/aspect.
- ArchUnit test: any JPA repository method that could return cross-tenant rows without the filter fails the build.
- **Files:** `common/tenant/TenantContext.java`, `TenantFilter.java`, `TenantAwareRepositoryConfig.java`, `TenantIsolationArchTest.java`.
- **Accept:** integration test proves org A cannot read org B's leads even with a guessed id.

### T1.4 — Identity: auth + RBAC
- Registration/onboarding: create org + first ADMIN user.
- Login → access JWT (~15 min) + refresh token. Refresh rotation with **reuse detection** (overview §6.1): store hashed in `refresh_tokens` with `family_id`/`parent_id`/`used`; Redis mirror for revoke.
- `POST /auth/refresh`, `POST /auth/logout` (revoke family).
- BCrypt password hashing. RBAC roles ADMIN/MANAGER/SALES_REP via `@PreAuthorize`.
- **Files:** `identity/api/AuthController.java`, `identity/domain/*`, `identity/infra/JwtProvider.java`, `RefreshTokenService.java`, `SecurityConfig.java`.
- **Accept:** login→refresh→refresh works; replaying a rotated (used) refresh token revokes the family and 401s; role-gated endpoint denies SALES_REP.

### T1.5 — CRM core: accounts, contacts, leads
- CRUD for accounts, contacts, leads. DTOs via MapStruct. Bean Validation on inputs.
- Leads carry `owner_id`, `status`, `source`, `raw_notes`, `version`.
- Pagination + filtering on `GET /leads?status=&owner=&page=`.
- **Files:** `crmcore/api/*Controller.java`, `crmcore/domain/*`, `crmcore/infra/*Repository.java`, mappers.
- **Accept:** CRUD works, tenant-scoped, paginated list returns only caller's org.

### T1.6 — Pipeline: Kanban deals + stages
- Org-configurable `deal_stages` (seeded defaults: NEW→QUALIFICATION→PROPOSAL→WON/LOST).
- `deals` CRUD; `PATCH /deals/{id}/stage` moves stage with **optimistic lock** (`@Version`) → 409 on concurrent drag conflict.
- Stage change writes a `deal_stage_history` row + sets `entered_stage_at`.
- `GET /deals/pipeline` returns deals grouped by stage for board render.
- **Files:** `pipeline/api/DealController.java`, `pipeline/domain/*`, `StageTransitionService.java`.
- **Accept:** two concurrent stage PATCHes → one 200, one 409; history row written; pipeline endpoint groups correctly.

### T1.7 — Cross-cutting: audit, exceptions, validation
- Global `@ControllerAdvice` → consistent error envelope (RFC 7807 problem+json).
- Audit interceptor writes `audit_log` rows (actor, action, entity, JSONB diff) on mutating ops.
- Structured JSON logging with `org_id`/`user_id` in MDC (trace_id added Phase 4).
- **Files:** `common/exception/GlobalExceptionHandler.java`, `common/audit/AuditAspect.java`, logback JSON config.
- **Accept:** validation error returns problem+json; mutating a lead writes an audit row with a diff.

### T1.8 — API docs
- springdoc-openapi → Swagger UI at `/swagger-ui.html`.
- **Accept:** all Phase 1 endpoints appear with schemas.

### T1.9 — Containerization + local compose
- Multi-stage Dockerfile (build → JRE-slim/distroless runtime).
- `docker-compose.yml`: app + Postgres + Redis.
- **Accept:** `docker compose up` yields a working app; login flow succeeds against it.

### T1.10 — K8s single Deployment (CORE, minimal)
- Deployment + Service + ConfigMap + Secret for the app; Postgres/Redis as StatefulSet+PVC (local) or managed (documented).
- Actuator liveness/readiness/startup probes wired.
- **Files:** `k8s/` manifests (Helm chart proper comes in Phase 5).
- **Accept:** `kubectl apply` on a local cluster (kind/minikube) → pod Ready, app reachable through Service.

---

## Testing requirements (Phase 1)
- **Unit** (JUnit 5 + Mockito): stage-transition logic, refresh-token rotation/reuse logic, tenant-context resolution.
- **Integration** (Testcontainers Postgres + Redis): auth flow, tenant isolation, optimistic-lock 409, Flyway migration.
- **Contract** (RestAssured): Phase 1 endpoints match OpenAPI.
- **Arch** (Spring Modulith + ArchUnit): module boundaries + tenant-filter enforcement.

## New endpoints (Phase 1)
`POST /auth/register` · `POST /auth/login` · `POST /auth/refresh` · `POST /auth/logout` · CRUD `/accounts` `/contacts` `/leads` · `GET /leads` (filter/page) · CRUD `/deals` · `PATCH /deals/{id}/stage` · `GET /deals/pipeline` · CRUD `/deal-stages`.

## Tier labels
All Phase 1 = **CORE** except: Loki/ELK (not in scope yet), managed Postgres/Redis (STRETCH; StatefulSet is the local CORE path).

## Interview talking points
- Tenant isolation enforced by the framework + a build-breaking ArchUnit test, not by developer discipline.
- Refresh-token reuse detection (rotating families) — real session security.
- Optimistic locking prevents lost updates on concurrent Kanban drags.
- Module boundaries verified in CI — the "designed for extraction" story starts here.

## Risks / gotchas
- Hibernate `@Filter` must be enabled on **every** session; a missed enable = silent cross-tenant leak. The ArchUnit test is the safety net — write it early.
- `CITEXT` for emails needs the Postgres `citext` extension in `V1`.
- Don't let `deal_stages.position` collide; enforce `UNIQUE (org_id, position)` and reorder transactionally.
