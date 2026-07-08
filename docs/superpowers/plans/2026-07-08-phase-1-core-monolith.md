# Phase 1 — Core CRUD Monolith Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A running, dockerized, K8s-deployed Spring Modulith monolith with auth, multi-tenancy, CRM core (accounts/contacts/leads), and Kanban deals — tenant-isolated, no Kafka, no ML.

**Architecture:** Single Spring Boot 3.4 modular monolith (Spring Modulith). Postgres + Flyway for persistence, Redis for refresh-token revoke mirror. Stateless JWT auth with rotating refresh tokens (reuse detection). Multi-tenancy enforced by a Hibernate `@Filter` driven from a request-scoped `TenantContext` fed by the JWT `org_id` claim, guarded by an ArchUnit build-break test.

**Tech Stack:** Java 21, Spring Boot 3.4.x, Spring Modulith 1.3.x, Gradle Kotlin DSL, PostgreSQL 16, Flyway, Redis 7, Spring Security 6 + jjwt, MapStruct, springdoc-openapi, Testcontainers, JUnit 5, RestAssured, ArchUnit. Build/tests run inside `eclipse-temurin:21` (no host JDK).

## Global Constraints

- Java **21**, Spring Boot **3.4.x**, Spring Modulith **1.3.x**, Gradle **Kotlin DSL** with committed wrapper.
- Base package `com.salespipe`. Modules: `identity`, `crmcore`, `pipeline`, `activity`, `emailtracking`, `scoring`, `notification`, `eventing`, `reporting`, `common`.
- **No host JDK/Gradle** — every `gradle`/`gradlew` command runs inside Docker: `docker run --rm -v "$PWD":/app -w /app -v gradle-cache:/root/.gradle eclipse-temurin:21-jdk ./gradlew <args>`. Testcontainers-using tasks additionally mount the Docker socket: add `-v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host host.docker.internal:host-gateway`. This full command is aliased below as **`GRADLE`** (compile-only) and **`GRADLE_TC`** (Testcontainers).
- Every `org_id` entity MUST be covered by the Hibernate tenant `@Filter`; a repository that can leak cross-tenant rows must fail the ArchUnit test.
- `org_id` is resolved **only** from the JWT `org_id` claim, **never** from a request body or path.
- Commits: author/committer `divanshu0212 <divanshu0212@gmail.com>`, **no `Co-Authored-By: Claude` trailer**. `git push origin main` after every commit. Commit command form:
  `git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com commit -m "<msg>"` then `git push origin main`.
- Conventional-commit messages.

**Command aliases** (expand inline when running):
- `GRADLE` = `docker run --rm -v "$PWD":/app -w /app -v gradle-cache:/root/.gradle eclipse-temurin:21-jdk ./gradlew`
- `GRADLE_TC` = `docker run --rm -v "$PWD":/app -w /app -v gradle-cache:/root/.gradle -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host host.docker.internal:host-gateway eclipse-temurin:21-jdk ./gradlew`

---

## File Structure

```
build.gradle.kts                      # deps, plugins, MapStruct annotation processor
settings.gradle.kts                   # rootProject.name
gradle/wrapper/…                      # committed wrapper (generated)
.gitignore                            # Java/Gradle (+Python for later phases)
src/main/resources/
  application.yml                     # datasource, Hikari, Flyway, JWT, Redis, actuator
  application-test.yml                # test overrides (Testcontainers wires URLs)
  db/migration/V1__init.sql           # all Phase 1 tables + citext + indexes + seed
  logback-spring.xml                  # JSON encoder, MDC fields
src/main/java/com/salespipe/
  SalesPipeApplication.java
  common/
    package-info.java                 # @ApplicationModule
    tenant/TenantContext.java         # request-scoped org_id holder
    tenant/TenantFilter.java          # servlet filter: JWT claim -> TenantContext
    tenant/TenantEntity.java          # @MappedSuperclass w/ @FilterDef/@Filter + org_id
    tenant/TenantFilterAspect.java    # enables Hibernate filter per request
    exception/GlobalExceptionHandler.java   # RFC7807
    exception/ApiException.java
    audit/AuditAspect.java            # writes audit_log
    audit/AuditLog.java               # entity
    audit/AuditLogRepository.java
  identity/
    package-info.java
    domain/Organization.java, User.java, Role.java, RefreshToken.java
    infra/OrganizationRepository.java, UserRepository.java, RefreshTokenRepository.java
    infra/JwtProvider.java            # sign/verify access tokens
    infra/RefreshTokenService.java    # rotate + reuse detection + Redis mirror
    api/AuthController.java, dto/*.java
    config/SecurityConfig.java        # filter chain, @PreAuthorize enable
    config/JwtAuthFilter.java         # bearer -> Authentication
  crmcore/
    package-info.java
    domain/Account.java, Contact.java, Lead.java, LeadStatus.java
    infra/*Repository.java
    api/AccountController.java, ContactController.java, LeadController.java
    api/dto/*.java, api/mapper/*Mapper.java   # MapStruct
  pipeline/
    package-info.java
    domain/DealStage.java, Deal.java, DealStageHistory.java
    infra/*Repository.java
    api/DealController.java, DealStageController.java, dto/*.java
    domain/StageTransitionService.java
  activity|emailtracking|scoring|notification|eventing|reporting/
    package-info.java                 # empty module stubs
src/test/java/com/salespipe/
  ModuleBoundaryTest.java
  common/tenant/TenantIsolationArchTest.java
  common/tenant/TenantIsolationIT.java
  identity/RefreshTokenServiceTest.java, AuthFlowIT.java
  pipeline/StageTransitionServiceTest.java, DealConcurrencyIT.java
  crmcore/LeadApiIT.java
  support/PostgresRedisTestBase.java  # Testcontainers base
Dockerfile
docker-compose.yml
k8s/{deployment,service,configmap,secret,postgres,redis}.yaml
```

---

## Task 1: Project scaffold & module boundaries (T1.1)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `.gitignore`, `src/main/java/com/salespipe/SalesPipeApplication.java`, all `*/package-info.java`, `src/main/resources/application.yml` (minimal), `src/test/java/com/salespipe/ModuleBoundaryTest.java`
- Generate: `gradle/wrapper/*`, `gradlew`, `gradlew.bat`

**Interfaces:**
- Produces: `com.salespipe.SalesPipeApplication` (Spring Boot entrypoint); ten module packages each with `package-info.java`.

- [ ] **Step 1: Write `.gitignore`**

```gitignore
# Java / Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
*.class
# IDE
.idea/
*.iml
.vscode/
# OS
.DS_Store
# Python (later phases)
__pycache__/
*.pyc
.venv/
venv/
# Secrets
*.env
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "salespipe"
```

- [ ] **Step 3: Write `build.gradle.kts`**

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.salespipe"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

extra["springModulithVersion"] = "1.3.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    implementation("org.mapstruct:mapstruct:1.6.3")
    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.testcontainers:testcontainers-bom:1.20.4")
    }
}

tasks.withType<Test> { useJUnitPlatform() }
```

- [ ] **Step 4: Write `SalesPipeApplication.java`**

```java
package com.salespipe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SalesPipeApplication {
    public static void main(String[] args) {
        SpringApplication.run(SalesPipeApplication.class, args);
    }
}
```

- [ ] **Step 5: Write one `package-info.java` per module**

For each of `identity`, `crmcore`, `pipeline`, `activity`, `emailtracking`, `scoring`, `notification`, `eventing`, `reporting`, `common`, create `src/main/java/com/salespipe/<module>/package-info.java`:

```java
@org.springframework.modulith.ApplicationModule
package com.salespipe.<module>;
```

(Replace `<module>` with the actual package name in each file.)

- [ ] **Step 6: Write minimal `application.yml`**

```yaml
spring:
  application:
    name: salespipe
  main:
    banner-mode: off
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
      - org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
```

(DB/Redis autoconfig excluded for now so the app boots with zero infra in Task 1; removed in Task 2 once the datasource is configured.)

- [ ] **Step 7: Write `ModuleBoundaryTest.java`**

```java
package com.salespipe;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {
    @Test
    void modulesRespectBoundaries() {
        ApplicationModules.of(SalesPipeApplication.class).verify();
    }
}
```

- [ ] **Step 8: Generate the Gradle wrapper inside Docker**

Run:
```bash
docker run --rm -v "$PWD":/app -w /app -v gradle-cache:/root/.gradle \
  gradle:8.11-jdk21 gradle wrapper --gradle-version 8.11
```
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

- [ ] **Step 9: Run the module boundary test (expect PASS — app boots, no infra)**

Run: `GRADLE test --tests com.salespipe.ModuleBoundaryTest`
Expected: BUILD SUCCESSFUL, test passes.

- [ ] **Step 10: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(scaffold): Spring Modulith skeleton + module boundary CI gate"
git push origin main
```

---

## Task 2: Postgres + Flyway baseline (T1.2)

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`, `src/test/java/com/salespipe/support/PostgresRedisTestBase.java`, `src/test/resources/application-test.yml`
- Modify: `src/main/resources/application.yml` (remove autoconfig excludes, add datasource/Hikari/Flyway/JPA)
- Test: `src/test/java/com/salespipe/FlywayMigrationIT.java`

**Interfaces:**
- Produces: full Phase 1 schema; `PostgresRedisTestBase` (Testcontainers base with `@DynamicPropertySource` wiring Postgres + Redis URLs) that later ITs extend.

- [ ] **Step 1: Write `V1__init.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE organizations (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    plan       TEXT NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    email         CITEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, email)
);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    org_id     UUID NOT NULL REFERENCES organizations(id),
    user_id    UUID NOT NULL REFERENCES users(id),
    family_id  UUID NOT NULL,
    token_hash TEXT NOT NULL,
    parent_id  UUID,
    used       BOOLEAN NOT NULL DEFAULT false,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);

CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    org_id         UUID NOT NULL REFERENCES organizations(id),
    name           TEXT NOT NULL,
    industry       TEXT,
    employee_count INT,
    website        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_accounts_org ON accounts(org_id);

CREATE TABLE contacts (
    id         UUID PRIMARY KEY,
    org_id     UUID NOT NULL REFERENCES organizations(id),
    account_id UUID REFERENCES accounts(id),
    first_name TEXT,
    last_name  TEXT,
    email      CITEXT,
    phone      TEXT,
    title      TEXT
);
CREATE INDEX idx_contacts_org ON contacts(org_id);

CREATE TABLE leads (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    contact_id    UUID REFERENCES contacts(id),
    account_id    UUID REFERENCES accounts(id),
    source        TEXT,
    status        TEXT NOT NULL,
    raw_notes     TEXT,
    current_score NUMERIC(5,4),
    owner_id      UUID REFERENCES users(id),
    version       INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_leads_org_status ON leads(org_id, status);
CREATE INDEX idx_leads_org_owner ON leads(org_id, owner_id);

CREATE TABLE deal_stages (
    id       UUID PRIMARY KEY,
    org_id   UUID NOT NULL REFERENCES organizations(id),
    name     TEXT NOT NULL,
    position INT NOT NULL,
    is_won   BOOLEAN NOT NULL DEFAULT false,
    is_lost  BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (org_id, position)
);

CREATE TABLE deals (
    id                  UUID PRIMARY KEY,
    org_id              UUID NOT NULL REFERENCES organizations(id),
    lead_id             UUID REFERENCES leads(id),
    account_id          UUID REFERENCES accounts(id),
    stage_id            UUID NOT NULL REFERENCES deal_stages(id),
    owner_id            UUID REFERENCES users(id),
    amount              NUMERIC(14,2),
    currency            CHAR(3),
    expected_close_date DATE,
    entered_stage_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_deals_org_stage ON deals(org_id, stage_id);

CREATE TABLE deal_stage_history (
    id            UUID PRIMARY KEY,
    org_id        UUID NOT NULL REFERENCES organizations(id),
    deal_id       UUID NOT NULL REFERENCES deals(id),
    from_stage_id UUID,
    to_stage_id   UUID NOT NULL,
    changed_by    UUID,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_deal_history_deal ON deal_stage_history(deal_id);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    org_id      UUID NOT NULL,
    actor_id    UUID,
    action      TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   UUID,
    diff        JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_org ON audit_log(org_id, created_at DESC);
```

- [ ] **Step 2: Replace `application.yml`**

```yaml
spring:
  application:
    name: salespipe
  main:
    banner-mode: off
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/salespipe}
    username: ${DB_USER:salespipe}
    password: ${DB_PASSWORD:salespipe}
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:10}
      minimum-idle: 2
      connection-timeout: 30000
      max-lifetime: 1800000
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

app:
  jwt:
    secret: ${JWT_SECRET:dev-secret-change-me-min-256-bits-long-for-hs256-xxxxxxxx}
    access-ttl-seconds: 900
    refresh-ttl-seconds: 1209600

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
```

- [ ] **Step 3: Write `application-test.yml`**

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
app:
  jwt:
    secret: test-secret-min-256-bits-long-for-hs256-testing-xxxxxxxxxxxxx
    access-ttl-seconds: 900
    refresh-ttl-seconds: 1209600
```

- [ ] **Step 4: Write `PostgresRedisTestBase.java`**

```java
package com.salespipe.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class PostgresRedisTestBase {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RedisContainer redis =
        new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
```

- [ ] **Step 5: Write `FlywayMigrationIT.java`**

```java
package com.salespipe;

import com.salespipe.support.PostgresRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIT extends PostgresRedisTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void schemaMigratesAndCoreTablesExist() {
        Integer tables = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables " +
            "WHERE table_schema='public' AND table_name IN " +
            "('organizations','users','refresh_tokens','accounts','contacts'," +
            "'leads','deal_stages','deals','deal_stage_history','audit_log')",
            Integer.class);
        assertThat(tables).isEqualTo(10);
    }
}
```

- [ ] **Step 6: Run the migration IT (expect PASS)**

Run: `GRADLE_TC test --tests com.salespipe.FlywayMigrationIT`
Expected: BUILD SUCCESSFUL; container starts, Flyway applies V1, assertion passes.

- [ ] **Step 7: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(db): V1 schema + Flyway + Testcontainers base"
git push origin main
```

---

## Task 3: Tenant context + Hibernate filter + ArchUnit gate (T1.3)

**Files:**
- Create: `common/tenant/TenantContext.java`, `TenantEntity.java`, `TenantFilterAspect.java`, `src/test/java/com/salespipe/common/tenant/TenantIsolationArchTest.java`
- (`TenantFilter` servlet filter is built in Task 4 where JWT exists; here the aspect enables the Hibernate filter from whatever `TenantContext` holds.)
- Test: `TenantIsolationArchTest.java` (arch), plus a unit test for `TenantContext`.

**Interfaces:**
- Produces:
  - `TenantContext` — request-scoped bean: `void setOrgId(UUID)`, `UUID getOrgId()`, `boolean isSet()`.
  - `TenantEntity` — `@MappedSuperclass` declaring `@FilterDef(name="tenantFilter", parameters=@ParamDef(name="orgId", type=UUID.class))` + `@Filter(name="tenantFilter", condition="org_id = :orgId")`, with a protected `UUID orgId` field mapped to `org_id`.
  - `TenantFilterAspect` — enables the Hibernate filter with the current `TenantContext.orgId` at the start of each transaction/request.

- [ ] **Step 1: Write `TenantContext.java`**

```java
package com.salespipe.common.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.UUID;

@Component
@RequestScope
public class TenantContext {
    private UUID orgId;

    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getOrgId() { return orgId; }
    public boolean isSet() { return orgId != null; }
}
```

- [ ] **Step 2: Write `TenantEntity.java`**

```java
package com.salespipe.common.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.UUID;

@MappedSuperclass
@FilterDef(name = "tenantFilter",
        parameters = @ParamDef(name = "orgId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "org_id = :orgId")
public abstract class TenantEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    protected UUID orgId;

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
}
```

- [ ] **Step 3: Write `TenantFilterAspect.java`**

```java
package com.salespipe.common.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

@Component
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager em;

    private final TenantContext tenantContext;

    public TenantFilterAspect(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    /** Enable the Hibernate tenant filter for the current session. */
    public void enable() {
        if (RequestContextHolder.getRequestAttributes() == null) return;
        if (!tenantContext.isSet()) return;
        em.unwrap(Session.class)
          .enableFilter("tenantFilter")
          .setParameter("orgId", tenantContext.getOrgId());
    }
}
```

(The aspect is invoked from `TenantFilter` in Task 4 after `org_id` is resolved, inside an open EntityManager per request. A `@Transactional` service call then sees the enabled filter.)

- [ ] **Step 4: Write `TenantIsolationArchTest.java`**

```java
package com.salespipe.common.tenant;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class TenantIsolationArchTest {

    private final JavaClasses classes = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.salespipe");

    @Test
    void everyOrgScopedEntityExtendsTenantEntity() {
        classes()
            .that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .and().haveSimpleNameNotEndingWith("Organization")
            .and().haveSimpleNameNotEndingWith("AuditLog")
            .and().haveSimpleNameNotEndingWith("RefreshToken")
            .should().beAssignableTo(TenantEntity.class)
            .because("org-scoped entities must inherit the tenant @Filter; " +
                     "Organization/AuditLog/RefreshToken are handled explicitly")
            .check(classes);
    }
}
```

(`RefreshToken` carries `org_id` but is queried by `token_hash` on the auth path before a tenant is known, so it is filtered manually in `RefreshTokenService`; `AuditLog` is written with an explicit `org_id`; `Organization` has none. All other entities MUST extend `TenantEntity`.)

- [ ] **Step 5: Write `TenantContextTest.java`**

```java
package com.salespipe.common.tenant;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {
    @Test
    void holdsAndReportsOrgId() {
        TenantContext ctx = new TenantContext();
        assertThat(ctx.isSet()).isFalse();
        UUID org = UUID.randomUUID();
        ctx.setOrgId(org);
        assertThat(ctx.isSet()).isTrue();
        assertThat(ctx.getOrgId()).isEqualTo(org);
    }
}
```

- [ ] **Step 6: Run tenant + arch tests**

Run: `GRADLE test --tests com.salespipe.common.tenant.*`
Expected: `TenantContextTest` passes; `TenantIsolationArchTest` passes (no entities exist yet besides none — rule is vacuously true, still compiles and guards future tasks).

- [ ] **Step 7: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(tenant): tenant context, Hibernate filter superclass, ArchUnit gate"
git push origin main
```

---

## Task 4: Identity — auth + RBAC + reuse detection (T1.4)

**Files:**
- Create: `identity/domain/{Organization,User,Role,RefreshToken}.java`, `identity/infra/{OrganizationRepository,UserRepository,RefreshTokenRepository,JwtProvider,RefreshTokenService}.java`, `identity/api/AuthController.java`, `identity/api/dto/*.java`, `identity/config/{SecurityConfig,JwtAuthFilter,JwtProperties}.java`, `common/tenant/TenantFilter.java`
- Test: `identity/RefreshTokenServiceTest.java` (unit), `identity/AuthFlowIT.java` (integration)

**Interfaces:**
- Consumes: `TenantEntity`, `TenantContext`, `TenantFilterAspect` (Task 3).
- Produces:
  - `JwtProvider`: `String createAccessToken(UUID userId, UUID orgId, Role role)`, `Jws<Claims> parse(String token)`, `UUID orgId(Claims)`, `UUID userId(Claims)`, `Role role(Claims)`.
  - `RefreshTokenService`: `RefreshToken issue(User user)`, `RotationResult rotate(String rawToken)` where `record RotationResult(User user, RefreshToken newToken)`; throws `TokenReuseException` (revokes family) and `InvalidTokenException`.
  - `AuthController` endpoints below.

- [ ] **Step 1: Write `Role.java`**

```java
package com.salespipe.identity.domain;

public enum Role { ADMIN, MANAGER, SALES_REP }
```

- [ ] **Step 2: Write `Organization.java`**

```java
package com.salespipe.identity.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {
    @Id private UUID id;
    private String name;
    private String plan;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected Organization() {}
    public Organization(UUID id, String name) {
        this.id = id; this.name = name; this.plan = "FREE";
        this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
}
```

- [ ] **Step 3: Write `User.java`**

```java
package com.salespipe.identity.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends TenantEntity {
    @Id private UUID id;
    private String email;
    @Column(name = "password_hash") private String passwordHash;
    @Enumerated(EnumType.STRING) private Role role;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected User() {}
    public User(UUID id, UUID orgId, String email, String passwordHash, Role role) {
        this.id = id; this.orgId = orgId; this.email = email;
        this.passwordHash = passwordHash; this.role = role;
        this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
}
```

- [ ] **Step 4: Write `RefreshToken.java`**

```java
package com.salespipe.identity.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id private UUID id;
    @Column(name = "org_id") private UUID orgId;
    @Column(name = "user_id") private UUID userId;
    @Column(name = "family_id") private UUID familyId;
    @Column(name = "token_hash") private String tokenHash;
    @Column(name = "parent_id") private UUID parentId;
    private boolean used;
    @Column(name = "expires_at") private OffsetDateTime expiresAt;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected RefreshToken() {}
    public RefreshToken(UUID id, UUID orgId, UUID userId, UUID familyId,
                        String tokenHash, UUID parentId, OffsetDateTime expiresAt) {
        this.id = id; this.orgId = orgId; this.userId = userId;
        this.familyId = familyId; this.tokenHash = tokenHash;
        this.parentId = parentId; this.expiresAt = expiresAt;
        this.used = false; this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public boolean isUsed() { return used; }
    public void markUsed() { this.used = true; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
```

- [ ] **Step 5: Write repositories**

`OrganizationRepository.java`:
```java
package com.salespipe.identity.infra;

import com.salespipe.identity.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {}
```

`UserRepository.java`:
```java
package com.salespipe.identity.infra;

import com.salespipe.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailAndOrgId(String email, UUID orgId);
    Optional<User> findByEmail(String email);
}
```

`RefreshTokenRepository.java`:
```java
package com.salespipe.identity.infra;

import com.salespipe.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from RefreshToken t where t.familyId = :familyId")
    void deleteByFamilyId(@Param("familyId") UUID familyId);
}
```

- [ ] **Step 6: Write `JwtProperties.java`**

```java
package com.salespipe.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {
    private String secret;
    private long accessTtlSeconds;
    private long refreshTtlSeconds;
    public String getSecret() { return secret; }
    public void setSecret(String s) { this.secret = s; }
    public long getAccessTtlSeconds() { return accessTtlSeconds; }
    public void setAccessTtlSeconds(long v) { this.accessTtlSeconds = v; }
    public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
    public void setRefreshTtlSeconds(long v) { this.refreshTtlSeconds = v; }
}
```

- [ ] **Step 7: Write `JwtProvider.java`**

```java
package com.salespipe.identity.infra;

import com.salespipe.identity.config.JwtProperties;
import com.salespipe.identity.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long accessTtl;

    public JwtProvider(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = props.getAccessTtlSeconds();
    }

    public String createAccessToken(UUID userId, UUID orgId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("org_id", orgId.toString())
            .claim("role", role.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(accessTtl)))
            .signWith(key)
            .compact();
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    public UUID orgId(Claims c) { return UUID.fromString(c.get("org_id", String.class)); }
    public UUID userId(Claims c) { return UUID.fromString(c.getSubject()); }
    public Role role(Claims c) { return Role.valueOf(c.get("role", String.class)); }
}
```

- [ ] **Step 8: Write `RefreshTokenService.java`** (rotation + reuse detection + Redis mirror)

```java
package com.salespipe.identity.infra;

import com.salespipe.identity.config.JwtProperties;
import com.salespipe.identity.domain.RefreshToken;
import com.salespipe.identity.domain.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    public static class TokenReuseException extends RuntimeException {
        public TokenReuseException(String m) { super(m); }
    }
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String m) { super(m); }
    }
    public record Issued(String rawToken, RefreshToken entity) {}
    public record RotationResult(User user, String rawToken, RefreshToken newToken) {}

    private static final SecureRandom RNG = new SecureRandom();
    private final RefreshTokenRepository repo;
    private final UserRepository users;
    private final StringRedisTemplate redis;
    private final long refreshTtl;

    public RefreshTokenService(RefreshTokenRepository repo, UserRepository users,
                               StringRedisTemplate redis, JwtProperties props) {
        this.repo = repo; this.users = users; this.redis = redis;
        this.refreshTtl = props.getRefreshTtlSeconds();
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String redisKey(String tokenHash) { return "rt:" + tokenHash; }

    @Transactional
    public Issued issue(User user) {
        return issue(user, UUID.randomUUID(), null);
    }

    private Issued issue(User user, UUID familyId, UUID parentId) {
        String raw = randomToken();
        String h = hash(raw);
        RefreshToken t = new RefreshToken(UUID.randomUUID(), user.getOrgId(),
            user.getId(), familyId, h, parentId,
            OffsetDateTime.now().plusSeconds(refreshTtl));
        repo.save(t);
        redis.opsForValue().set(redisKey(h), user.getId().toString(),
            Duration.ofSeconds(refreshTtl));
        return new Issued(raw, t);
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        String h = hash(rawToken);
        RefreshToken current = repo.findByTokenHash(h)
            .orElseThrow(() -> new InvalidTokenException("unknown token"));

        if (current.isUsed()) {
            // Reuse detected: nuke the whole family + Redis mirror.
            repo.deleteByFamilyId(current.getFamilyId());
            redis.delete(redisKey(h));
            throw new TokenReuseException("refresh token reuse; family revoked");
        }
        if (current.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidTokenException("expired");
        }

        current.markUsed();
        repo.save(current);
        redis.delete(redisKey(h));

        User user = users.findById(current.getUserId())
            .orElseThrow(() -> new InvalidTokenException("user gone"));
        Issued next = issue(user, current.getFamilyId(), current.getId());
        return new RotationResult(user, next.rawToken(), next.entity());
    }

    @Transactional
    public void revokeFamily(String rawToken) {
        repo.findByTokenHash(hash(rawToken)).ifPresent(t -> {
            repo.deleteByFamilyId(t.getFamilyId());
            redis.delete(redisKey(t.getTokenHash()));
        });
    }
}
```

- [ ] **Step 9: Write auth DTOs**

`identity/api/dto/RegisterRequest.java`:
```java
package com.salespipe.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank String orgName,
    @Email @NotBlank String email,
    @NotBlank @Size(min = 8) String password) {}
```

`identity/api/dto/LoginRequest.java`:
```java
package com.salespipe.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
```

`identity/api/dto/RefreshRequest.java`:
```java
package com.salespipe.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {}
```

`identity/api/dto/TokenResponse.java`:
```java
package com.salespipe.identity.api.dto;

public record TokenResponse(String accessToken, String refreshToken) {}
```

- [ ] **Step 10: Write `AuthController.java`**

```java
package com.salespipe.identity.api;

import com.salespipe.identity.api.dto.*;
import com.salespipe.identity.domain.Organization;
import com.salespipe.identity.domain.Role;
import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtProvider jwt;
    private final RefreshTokenService refreshTokens;

    public AuthController(OrganizationRepository orgs, UserRepository users,
                          PasswordEncoder encoder, JwtProvider jwt,
                          RefreshTokenService refreshTokens) {
        this.orgs = orgs; this.users = users; this.encoder = encoder;
        this.jwt = jwt; this.refreshTokens = refreshTokens;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        Organization org = new Organization(UUID.randomUUID(), req.orgName());
        orgs.save(org);
        User admin = new User(UUID.randomUUID(), org.getId(), req.email(),
            encoder.encode(req.password()), Role.ADMIN);
        users.save(admin);
        return tokens(admin);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByEmail(req.email())
            .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
            .orElseThrow(() -> new BadCredentials());
        return tokens(user);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        var result = refreshTokens.rotate(req.refreshToken());
        String access = jwt.createAccessToken(result.user().getId(),
            result.user().getOrgId(), result.user().getRole());
        return new TokenResponse(access, result.rawToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        refreshTokens.revokeFamily(req.refreshToken());
    }

    private TokenResponse tokens(User user) {
        String access = jwt.createAccessToken(user.getId(), user.getOrgId(), user.getRole());
        var issued = refreshTokens.issue(user);
        return new TokenResponse(access, issued.rawToken());
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    static class BadCredentials extends RuntimeException {}
}
```

- [ ] **Step 11: Write `TenantFilter.java`** (servlet filter, resolves org from JWT then enables Hibernate filter)

```java
package com.salespipe.common.tenant;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(20)
public class TenantFilter implements Filter {

    private final TenantContext tenantContext;
    private final TenantFilterAspect filterAspect;

    public TenantFilter(TenantContext tenantContext, TenantFilterAspect filterAspect) {
        this.tenantContext = tenantContext;
        this.filterAspect = filterAspect;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal p) {
            tenantContext.setOrgId(p.orgId());
            filterAspect.enable();
        }
        chain.doFilter(req, res);
    }

    /** Principal carried by the Authentication set in JwtAuthFilter. */
    public record AuthPrincipal(UUID userId, UUID orgId) {}
}
```

- [ ] **Step 12: Write `JwtAuthFilter.java`**

```java
package com.salespipe.identity.config;

import com.salespipe.common.tenant.TenantFilter.AuthPrincipal;
import com.salespipe.identity.infra.JwtProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Order(10)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwt;
    public JwtAuthFilter(JwtProvider jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Jws<Claims> jws = jwt.parse(header.substring(7));
                Claims c = jws.getPayload();
                var principal = new AuthPrincipal(jwt.userId(c), jwt.orgId(c));
                var authority = new SimpleGrantedAuthority("ROLE_" + jwt.role(c).name());
                var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 13: Write `SecurityConfig.java`**

```java
package com.salespipe.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers("/auth/**", "/swagger-ui/**", "/v3/api-docs/**",
                    "/actuator/health/**", "/emails/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
```

- [ ] **Step 14: Write `RefreshTokenServiceTest.java`** (unit — rotation + reuse; mock repos/redis)

```java
package com.salespipe.identity;

import com.salespipe.identity.config.JwtProperties;
import com.salespipe.identity.domain.RefreshToken;
import com.salespipe.identity.domain.Role;
import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.RefreshTokenRepository;
import com.salespipe.identity.infra.RefreshTokenService;
import com.salespipe.identity.infra.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    RefreshTokenRepository repo;
    UserRepository users;
    StringRedisTemplate redis;
    RefreshTokenService svc;
    Map<String, RefreshToken> store;
    User user;

    @BeforeEach
    void setup() {
        repo = mock(RefreshTokenRepository.class);
        users = mock(UserRepository.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        JwtProperties props = new JwtProperties();
        props.setRefreshTtlSeconds(1000);
        svc = new RefreshTokenService(repo, users, redis, props);

        store = new HashMap<>();
        UUID orgId = UUID.randomUUID();
        user = new User(UUID.randomUUID(), orgId, "a@b.com", "hash", Role.ADMIN);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(repo.save(any())).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            store.put(t.getTokenHash(), t);
            return t;
        });
        when(repo.findByTokenHash(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
    }

    @Test
    void reusingUsedTokenRevokesFamily() {
        var issued = svc.issue(user);
        var rotated = svc.rotate(issued.rawToken()); // original now used=true

        // Replaying the original raw token => reuse => family revoked.
        assertThatThrownBy(() -> svc.rotate(issued.rawToken()))
            .isInstanceOf(RefreshTokenService.TokenReuseException.class);
        verify(repo).deleteByFamilyId(any(UUID.class));
    }

    @Test
    void unknownTokenIsInvalid() {
        assertThatThrownBy(() -> svc.rotate("nope"))
            .isInstanceOf(RefreshTokenService.InvalidTokenException.class);
    }
}
```

- [ ] **Step 15: Run the unit test**

Run: `GRADLE test --tests com.salespipe.identity.RefreshTokenServiceTest`
Expected: PASS.

- [ ] **Step 16: Write `AuthFlowIT.java`** (integration — register→login→refresh→reuse→role gate)

```java
package com.salespipe.identity;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

class AuthFlowIT extends PostgresRedisTestBase {

    @LocalServerPort int port;

    @BeforeEach
    void setPort() { RestAssured.port = port; }

    private Map<String, String> register(String org, String email) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("orgName", org, "email", email, "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .body("accessToken", notNullValue())
            .body("refreshToken", notNullValue())
            .extract().as(Map.class);
    }

    @Test
    void registerLoginRefreshThenReuseRevokesFamily() {
        var t = register("Acme", "admin@acme.com");
        String refresh = t.get("refreshToken");

        // rotate once
        var rotated = given().contentType(ContentType.JSON)
            .body(Map.of("refreshToken", refresh))
            .post("/auth/refresh").then().statusCode(200)
            .extract().as(Map.class);

        // replay the OLD refresh token -> reuse -> 401
        given().contentType(ContentType.JSON)
            .body(Map.of("refreshToken", refresh))
            .post("/auth/refresh").then().statusCode(401);

        // the rotated token is now also revoked (family nuked) -> 401
        given().contentType(ContentType.JSON)
            .body(Map.of("refreshToken", rotated.get("refreshToken")))
            .post("/auth/refresh").then().statusCode(401);
    }

    @Test
    void loginWorks() {
        register("Beta", "u@beta.com");
        given().contentType(ContentType.JSON)
            .body(Map.of("email", "u@beta.com", "password", "password123"))
            .post("/auth/login").then().statusCode(200)
            .body("accessToken", notNullValue());
    }
}
```

(Note: `TokenReuseException`/`InvalidTokenException`/`BadCredentials` map to 401 via the `GlobalExceptionHandler` built in Task 7. Until then, add a temporary `@ResponseStatus(HttpStatus.UNAUTHORIZED)` on the two service exceptions so this IT passes now; Task 7 centralizes it. Add the annotation on the exception classes.)

- [ ] **Step 17: Annotate service exceptions for 401 (interim)**

In `RefreshTokenService`, annotate both nested exceptions:
```java
@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
public static class TokenReuseException extends RuntimeException { ... }

@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
public static class InvalidTokenException extends RuntimeException { ... }
```

- [ ] **Step 18: Run the auth flow IT**

Run: `GRADLE_TC test --tests com.salespipe.identity.AuthFlowIT`
Expected: PASS (both tests).

- [ ] **Step 19: Run module boundary + arch tests (guard: new entities must obey tenant rule)**

Run: `GRADLE test --tests com.salespipe.ModuleBoundaryTest --tests com.salespipe.common.tenant.TenantIsolationArchTest`
Expected: PASS. `User` extends `TenantEntity`; `Organization`/`RefreshToken` are excluded by the rule.

- [ ] **Step 20: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(identity): JWT auth, RBAC, rotating refresh tokens with reuse detection"
git push origin main
```

---

## Task 5: CRM core — accounts, contacts, leads (T1.5)

**Files:**
- Create: `crmcore/domain/{Account,Contact,Lead,LeadStatus}.java`, `crmcore/infra/{Account,Contact,Lead}Repository.java`, `crmcore/api/{Account,Contact,Lead}Controller.java`, `crmcore/api/dto/*.java`, `crmcore/api/mapper/{Account,Contact,Lead}Mapper.java`
- Test: `crmcore/LeadApiIT.java`, `common/tenant/TenantIsolationIT.java`

**Interfaces:**
- Consumes: `TenantEntity`, auth/JWT (Task 4).
- Produces: entities extending `TenantEntity`; `LeadRepository` with `Page<Lead> findByStatusAndOwnerId(...)` variants; REST CRUD under `/accounts`, `/contacts`, `/leads`.

- [ ] **Step 1: Write `LeadStatus.java` + domain entities**

`crmcore/domain/LeadStatus.java`:
```java
package com.salespipe.crmcore.domain;

public enum LeadStatus { NEW, CONTACTED, QUALIFIED, UNQUALIFIED, CONVERTED }
```

`crmcore/domain/Account.java`:
```java
package com.salespipe.crmcore.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account extends TenantEntity {
    @Id private UUID id;
    private String name;
    private String industry;
    @Column(name = "employee_count") private Integer employeeCount;
    private String website;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected Account() {}
    public Account(UUID id, UUID orgId, String name) {
        this.id = id; this.orgId = orgId; this.name = name;
        this.createdAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getIndustry() { return industry; }
    public void setIndustry(String i) { this.industry = i; }
    public Integer getEmployeeCount() { return employeeCount; }
    public void setEmployeeCount(Integer c) { this.employeeCount = c; }
    public String getWebsite() { return website; }
    public void setWebsite(String w) { this.website = w; }
}
```

`crmcore/domain/Contact.java`:
```java
package com.salespipe.crmcore.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "contacts")
public class Contact extends TenantEntity {
    @Id private UUID id;
    @Column(name = "account_id") private UUID accountId;
    @Column(name = "first_name") private String firstName;
    @Column(name = "last_name") private String lastName;
    private String email;
    private String phone;
    private String title;

    protected Contact() {}
    public Contact(UUID id, UUID orgId) { this.id = id; this.orgId = orgId; }
    public UUID getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID a) { this.accountId = a; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String v) { this.firstName = v; }
    public String getLastName() { return lastName; }
    public void setLastName(String v) { this.lastName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
}
```

`crmcore/domain/Lead.java`:
```java
package com.salespipe.crmcore.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "leads")
public class Lead extends TenantEntity {
    @Id private UUID id;
    @Column(name = "contact_id") private UUID contactId;
    @Column(name = "account_id") private UUID accountId;
    private String source;
    @Enumerated(EnumType.STRING) private LeadStatus status;
    @Column(name = "raw_notes") private String rawNotes;
    @Column(name = "owner_id") private UUID ownerId;
    @Version private int version;
    @Column(name = "created_at") private OffsetDateTime createdAt;
    @Column(name = "updated_at") private OffsetDateTime updatedAt;

    protected Lead() {}
    public Lead(UUID id, UUID orgId, LeadStatus status) {
        this.id = id; this.orgId = orgId; this.status = status;
        this.createdAt = OffsetDateTime.now(); this.updatedAt = this.createdAt;
    }
    @PreUpdate void touch() { this.updatedAt = OffsetDateTime.now(); }
    public UUID getId() { return id; }
    public UUID getContactId() { return contactId; }
    public void setContactId(UUID v) { this.contactId = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public LeadStatus getStatus() { return status; }
    public void setStatus(LeadStatus v) { this.status = v; }
    public String getRawNotes() { return rawNotes; }
    public void setRawNotes(String v) { this.rawNotes = v; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public int getVersion() { return version; }
}
```

- [ ] **Step 2: Write repositories**

`crmcore/infra/AccountRepository.java`:
```java
package com.salespipe.crmcore.infra;

import com.salespipe.crmcore.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {}
```

`crmcore/infra/ContactRepository.java`:
```java
package com.salespipe.crmcore.infra;

import com.salespipe.crmcore.domain.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {}
```

`crmcore/infra/LeadRepository.java`:
```java
package com.salespipe.crmcore.infra;

import com.salespipe.crmcore.domain.Lead;
import com.salespipe.crmcore.domain.LeadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    @Query("select l from Lead l where " +
           "(:status is null or l.status = :status) and " +
           "(:ownerId is null or l.ownerId = :ownerId)")
    Page<Lead> search(@Param("status") LeadStatus status,
                      @Param("ownerId") UUID ownerId, Pageable pageable);
}
```

(The tenant `@Filter` transparently scopes every query above to the caller's org — no `org_id` predicate in application code.)

- [ ] **Step 3: Write DTOs + MapStruct mappers**

`crmcore/api/dto/LeadRequest.java`:
```java
package com.salespipe.crmcore.api.dto;

import com.salespipe.crmcore.domain.LeadStatus;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LeadRequest(
    @NotNull LeadStatus status, String source, String rawNotes,
    UUID contactId, UUID accountId, UUID ownerId) {}
```

`crmcore/api/dto/LeadResponse.java`:
```java
package com.salespipe.crmcore.api.dto;

import com.salespipe.crmcore.domain.LeadStatus;
import java.util.UUID;

public record LeadResponse(
    UUID id, LeadStatus status, String source, String rawNotes,
    UUID contactId, UUID accountId, UUID ownerId, int version) {}
```

`crmcore/api/mapper/LeadMapper.java`:
```java
package com.salespipe.crmcore.api.mapper;

import com.salespipe.crmcore.api.dto.LeadResponse;
import com.salespipe.crmcore.domain.Lead;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface LeadMapper {
    LeadResponse toResponse(Lead lead);
}
```

(Account/Contact DTOs + mappers follow the same shape — create `AccountRequest`/`AccountResponse`/`AccountMapper` and `ContactRequest`/`ContactResponse`/`ContactMapper` mirroring the fields on their entities. Keep mapper methods to `toResponse` only; construct entities by hand in controllers so `orgId` is set from `TenantContext`.)

`crmcore/api/dto/AccountRequest.java`:
```java
package com.salespipe.crmcore.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountRequest(@NotBlank String name, String industry,
                             Integer employeeCount, String website) {}
```

`crmcore/api/dto/AccountResponse.java`:
```java
package com.salespipe.crmcore.api.dto;

import java.util.UUID;

public record AccountResponse(UUID id, String name, String industry,
                              Integer employeeCount, String website) {}
```

`crmcore/api/mapper/AccountMapper.java`:
```java
package com.salespipe.crmcore.api.mapper;

import com.salespipe.crmcore.api.dto.AccountResponse;
import com.salespipe.crmcore.domain.Account;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    AccountResponse toResponse(Account a);
}
```

`crmcore/api/dto/ContactRequest.java`:
```java
package com.salespipe.crmcore.api.dto;

import java.util.UUID;

public record ContactRequest(UUID accountId, String firstName, String lastName,
                             String email, String phone, String title) {}
```

`crmcore/api/dto/ContactResponse.java`:
```java
package com.salespipe.crmcore.api.dto;

import java.util.UUID;

public record ContactResponse(UUID id, UUID accountId, String firstName,
                              String lastName, String email, String phone, String title) {}
```

`crmcore/api/mapper/ContactMapper.java`:
```java
package com.salespipe.crmcore.api.mapper;

import com.salespipe.crmcore.api.dto.ContactResponse;
import com.salespipe.crmcore.domain.Contact;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ContactMapper {
    ContactResponse toResponse(Contact c);
}
```

- [ ] **Step 4: Write `LeadController.java`**

```java
package com.salespipe.crmcore.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.crmcore.api.dto.LeadRequest;
import com.salespipe.crmcore.api.dto.LeadResponse;
import com.salespipe.crmcore.api.mapper.LeadMapper;
import com.salespipe.crmcore.domain.Lead;
import com.salespipe.crmcore.domain.LeadStatus;
import com.salespipe.crmcore.infra.LeadRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/leads")
public class LeadController {

    private final LeadRepository repo;
    private final LeadMapper mapper;
    private final TenantContext tenant;

    public LeadController(LeadRepository repo, LeadMapper mapper, TenantContext tenant) {
        this.repo = repo; this.mapper = mapper; this.tenant = tenant;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeadResponse create(@Valid @RequestBody LeadRequest req) {
        Lead lead = new Lead(UUID.randomUUID(), tenant.getOrgId(), req.status());
        apply(lead, req);
        return mapper.toResponse(repo.save(lead));
    }

    @GetMapping("/{id}")
    public LeadResponse get(@PathVariable UUID id) {
        return mapper.toResponse(find(id));
    }

    @PutMapping("/{id}")
    public LeadResponse update(@PathVariable UUID id, @Valid @RequestBody LeadRequest req) {
        Lead lead = find(id);
        lead.setStatus(req.status());
        apply(lead, req);
        return mapper.toResponse(repo.save(lead));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { repo.delete(find(id)); }

    @GetMapping
    public Page<LeadResponse> list(@RequestParam(required = false) LeadStatus status,
                                   @RequestParam(required = false) UUID owner,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return repo.search(status, owner, PageRequest.of(page, size))
                   .map(mapper::toResponse);
    }

    private void apply(Lead lead, LeadRequest req) {
        lead.setSource(req.source());
        lead.setRawNotes(req.rawNotes());
        lead.setContactId(req.contactId());
        lead.setAccountId(req.accountId());
        lead.setOwnerId(req.ownerId());
    }

    private Lead find(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    }
}
```

(`AccountController` and `ContactController` follow the identical CRUD shape against their repositories/mappers, constructing entities with `tenant.getOrgId()`. Endpoints `/accounts` and `/contacts`. No list-filtering beyond `findAll` paginated is required by the spec for those two.)

`crmcore/api/AccountController.java`:
```java
package com.salespipe.crmcore.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.crmcore.api.dto.AccountRequest;
import com.salespipe.crmcore.api.dto.AccountResponse;
import com.salespipe.crmcore.api.mapper.AccountMapper;
import com.salespipe.crmcore.domain.Account;
import com.salespipe.crmcore.infra.AccountRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {
    private final AccountRepository repo;
    private final AccountMapper mapper;
    private final TenantContext tenant;

    public AccountController(AccountRepository repo, AccountMapper mapper, TenantContext tenant) {
        this.repo = repo; this.mapper = mapper; this.tenant = tenant;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody AccountRequest req) {
        Account a = new Account(UUID.randomUUID(), tenant.getOrgId(), req.name());
        a.setIndustry(req.industry()); a.setEmployeeCount(req.employeeCount());
        a.setWebsite(req.website());
        return mapper.toResponse(repo.save(a));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) { return mapper.toResponse(find(id)); }

    @GetMapping
    public Page<AccountResponse> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return repo.findAll(PageRequest.of(page, size)).map(mapper::toResponse);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { repo.delete(find(id)); }

    private Account find(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    }
}
```

`crmcore/api/ContactController.java`:
```java
package com.salespipe.crmcore.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.crmcore.api.dto.ContactRequest;
import com.salespipe.crmcore.api.dto.ContactResponse;
import com.salespipe.crmcore.api.mapper.ContactMapper;
import com.salespipe.crmcore.domain.Contact;
import com.salespipe.crmcore.infra.ContactRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/contacts")
public class ContactController {
    private final ContactRepository repo;
    private final ContactMapper mapper;
    private final TenantContext tenant;

    public ContactController(ContactRepository repo, ContactMapper mapper, TenantContext tenant) {
        this.repo = repo; this.mapper = mapper; this.tenant = tenant;
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ContactResponse create(@Valid @RequestBody ContactRequest req) {
        Contact c = new Contact(UUID.randomUUID(), tenant.getOrgId());
        c.setAccountId(req.accountId()); c.setFirstName(req.firstName());
        c.setLastName(req.lastName()); c.setEmail(req.email());
        c.setPhone(req.phone()); c.setTitle(req.title());
        return mapper.toResponse(repo.save(c));
    }

    @GetMapping("/{id}")
    public ContactResponse get(@PathVariable UUID id) { return mapper.toResponse(find(id)); }

    @GetMapping
    public Page<ContactResponse> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return repo.findAll(PageRequest.of(page, size)).map(mapper::toResponse);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) { repo.delete(find(id)); }

    private Contact find(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contact not found"));
    }
}
```

- [ ] **Step 5: Write `TenantIsolationIT.java`** (org A cannot read org B's lead by guessed id)

```java
package com.salespipe.common.tenant;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;

class TenantIsolationIT extends PostgresRedisTestBase {

    @LocalServerPort int port;

    @BeforeEach
    void setPort() { RestAssured.port = port; }

    private String tokenFor(String org, String email) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("orgName", org, "email", email, "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .extract().path("accessToken");
    }

    @Test
    void orgACannotReadOrgBLead() {
        String tokenB = tokenFor("OrgB", "b@b.com");
        String leadId = given().header("Authorization", "Bearer " + tokenB)
            .contentType(ContentType.JSON)
            .body(Map.of("status", "NEW"))
            .post("/leads").then().statusCode(201)
            .extract().path("id");

        String tokenA = tokenFor("OrgA", "a@a.com");
        // Same id, different tenant -> 404 (filtered out, not visible).
        given().header("Authorization", "Bearer " + tokenA)
            .get("/leads/" + leadId).then().statusCode(404);
    }
}
```

- [ ] **Step 6: Write `LeadApiIT.java`** (CRUD + pagination, tenant-scoped list)

```java
package com.salespipe.crmcore;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

class LeadApiIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    String token;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        token = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Acme", "email", "crm@acme.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201)
            .extract().path("accessToken");
    }

    @Test
    void createListFilterLead() {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("status", "NEW", "source", "web"))
            .post("/leads").then().statusCode(201);

        given().header("Authorization", "Bearer " + token)
            .get("/leads?status=NEW&page=0&size=10").then().statusCode(200)
            .body("totalElements", equalTo(1));

        given().header("Authorization", "Bearer " + token)
            .get("/leads?status=QUALIFIED").then().statusCode(200)
            .body("totalElements", equalTo(0));
    }
}
```

- [ ] **Step 7: Add RBAC guard + denial test (SALES_REP cannot delete a lead)**

The spec requires a role-gated endpoint that denies SALES_REP. `@EnableMethodSecurity` is already on (Task 4). Annotate the destructive lead endpoint. In `LeadController`, add to the `delete` method:
```java
import org.springframework.security.access.prepost.PreAuthorize;
// ...
@DeleteMapping("/{id}")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable UUID id) { repo.delete(find(id)); }
```
(Roles come from the `ROLE_<name>` authority set in `JwtAuthFilter`.)

Then add `crmcore/RbacIT.java`:
```java
package com.salespipe.crmcore;

import com.salespipe.identity.domain.Role;
import com.salespipe.identity.domain.User;
import com.salespipe.identity.infra.JwtProvider;
import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

class RbacIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @Autowired JwtProvider jwt;
    String adminToken;
    UUID orgId;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        var body = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Rbac", "email", "admin@rbac.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201).extract();
        adminToken = body.path("accessToken");
        // decode org from the admin token to mint a SALES_REP token for the same org
        orgId = jwt.orgId(jwt.parse(adminToken).getPayload());
    }

    @Test
    void salesRepCannotDeleteLead() {
        String leadId = given().header("Authorization", "Bearer " + adminToken)
            .contentType(ContentType.JSON).body(Map.of("status", "NEW"))
            .post("/leads").then().statusCode(201).extract().path("id");

        // Mint a SALES_REP access token for the same org (no self-service rep signup in Phase 1).
        String repToken = jwt.createAccessToken(UUID.randomUUID(), orgId, Role.SALES_REP);

        given().header("Authorization", "Bearer " + repToken)
            .delete("/leads/" + leadId).then().statusCode(403);

        given().header("Authorization", "Bearer " + adminToken)
            .delete("/leads/" + leadId).then().statusCode(204);
    }
}
```
(A 403 from method security surfaces as `AccessDeniedException`; Spring Security's `ExceptionTranslationFilter` returns 403 before reaching `GlobalExceptionHandler` — no extra mapping needed.)

- [ ] **Step 8: Run CRM + tenant isolation + RBAC ITs**

Run: `GRADLE_TC test --tests com.salespipe.crmcore.LeadApiIT --tests com.salespipe.crmcore.RbacIT --tests com.salespipe.common.tenant.TenantIsolationIT`
Expected: PASS. Tenant isolation IT proves cross-tenant 404; RBAC IT proves SALES_REP gets 403, ADMIN 204.

- [ ] **Step 9: Run arch + module tests again**

Run: `GRADLE test --tests com.salespipe.common.tenant.TenantIsolationArchTest --tests com.salespipe.ModuleBoundaryTest`
Expected: PASS (`Account`/`Contact`/`Lead` all extend `TenantEntity`).

- [ ] **Step 10: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(crmcore): accounts/contacts/leads CRUD, filtering, tenant-scoped"
git push origin main
```

---

## Task 6: Pipeline — Kanban deals + stages + optimistic lock (T1.6)

**Files:**
- Create: `pipeline/domain/{DealStage,Deal,DealStageHistory}.java`, `pipeline/domain/StageTransitionService.java`, `pipeline/infra/{DealStage,Deal,DealStageHistory}Repository.java`, `pipeline/api/{Deal,DealStage}Controller.java`, `pipeline/api/dto/*.java`, `identity/infra/DefaultStageSeeder.java` (seed stages on org register)
- Modify: `identity/api/AuthController.java` (seed default stages on register)
- Test: `pipeline/StageTransitionServiceTest.java` (unit), `pipeline/DealConcurrencyIT.java` (409)

**Interfaces:**
- Consumes: `TenantEntity`, `TenantContext`, auth.
- Produces:
  - `StageTransitionService.move(UUID dealId, UUID toStageId, UUID actorId)` → applies stage change under optimistic lock, writes `DealStageHistory`, sets `entered_stage_at`; throws Spring's `OptimisticLockingFailureException` on conflict (mapped to 409 in Task 7; interim mapping here).
  - `DealStageSeeder.seedDefaults(UUID orgId)` — creates NEW→QUALIFICATION→PROPOSAL→WON→LOST.
  - `GET /deals/pipeline` grouped by stage.

- [ ] **Step 1: Write domain entities**

`pipeline/domain/DealStage.java`:
```java
package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "deal_stages")
public class DealStage extends TenantEntity {
    @Id private UUID id;
    private String name;
    private int position;
    @Column(name = "is_won") private boolean isWon;
    @Column(name = "is_lost") private boolean isLost;

    protected DealStage() {}
    public DealStage(UUID id, UUID orgId, String name, int position,
                     boolean isWon, boolean isLost) {
        this.id = id; this.orgId = orgId; this.name = name;
        this.position = position; this.isWon = isWon; this.isLost = isLost;
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public int getPosition() { return position; }
    public boolean isWon() { return isWon; }
    public boolean isLost() { return isLost; }
}
```

`pipeline/domain/Deal.java`:
```java
package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deals")
public class Deal extends TenantEntity {
    @Id private UUID id;
    @Column(name = "lead_id") private UUID leadId;
    @Column(name = "account_id") private UUID accountId;
    @Column(name = "stage_id") private UUID stageId;
    @Column(name = "owner_id") private UUID ownerId;
    private BigDecimal amount;
    private String currency;
    @Column(name = "expected_close_date") private LocalDate expectedCloseDate;
    @Column(name = "entered_stage_at") private OffsetDateTime enteredStageAt;
    @Version private int version;
    @Column(name = "created_at") private OffsetDateTime createdAt;
    @Column(name = "updated_at") private OffsetDateTime updatedAt;

    protected Deal() {}
    public Deal(UUID id, UUID orgId, UUID stageId) {
        this.id = id; this.orgId = orgId; this.stageId = stageId;
        this.enteredStageAt = OffsetDateTime.now();
        this.createdAt = this.enteredStageAt; this.updatedAt = this.enteredStageAt;
    }
    @PreUpdate void touch() { this.updatedAt = OffsetDateTime.now(); }
    public UUID getId() { return id; }
    public UUID getStageId() { return stageId; }
    public void moveToStage(UUID stageId) {
        this.stageId = stageId; this.enteredStageAt = OffsetDateTime.now();
    }
    public UUID getLeadId() { return leadId; }
    public void setLeadId(UUID v) { this.leadId = v; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID v) { this.accountId = v; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID v) { this.ownerId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public LocalDate getExpectedCloseDate() { return expectedCloseDate; }
    public void setExpectedCloseDate(LocalDate v) { this.expectedCloseDate = v; }
    public int getVersion() { return version; }
}
```

`pipeline/domain/DealStageHistory.java`:
```java
package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "deal_stage_history")
public class DealStageHistory extends TenantEntity {
    @Id private UUID id;
    @Column(name = "deal_id") private UUID dealId;
    @Column(name = "from_stage_id") private UUID fromStageId;
    @Column(name = "to_stage_id") private UUID toStageId;
    @Column(name = "changed_by") private UUID changedBy;
    @Column(name = "changed_at") private OffsetDateTime changedAt;

    protected DealStageHistory() {}
    public DealStageHistory(UUID id, UUID orgId, UUID dealId, UUID fromStageId,
                            UUID toStageId, UUID changedBy) {
        this.id = id; this.orgId = orgId; this.dealId = dealId;
        this.fromStageId = fromStageId; this.toStageId = toStageId;
        this.changedBy = changedBy; this.changedAt = OffsetDateTime.now();
    }
    public UUID getId() { return id; }
}
```

- [ ] **Step 2: Write repositories**

`pipeline/infra/DealStageRepository.java`:
```java
package com.salespipe.pipeline.infra;

import com.salespipe.pipeline.domain.DealStage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DealStageRepository extends JpaRepository<DealStage, UUID> {
    List<DealStage> findAllByOrderByPositionAsc();
}
```

`pipeline/infra/DealRepository.java`:
```java
package com.salespipe.pipeline.infra;

import com.salespipe.pipeline.domain.Deal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DealRepository extends JpaRepository<Deal, UUID> {
    List<Deal> findByStageId(UUID stageId);
}
```

`pipeline/infra/DealStageHistoryRepository.java`:
```java
package com.salespipe.pipeline.infra;

import com.salespipe.pipeline.domain.DealStageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DealStageHistoryRepository extends JpaRepository<DealStageHistory, UUID> {}
```

- [ ] **Step 3: Write `StageTransitionService.java`**

```java
package com.salespipe.pipeline.domain;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.pipeline.infra.DealRepository;
import com.salespipe.pipeline.infra.DealStageHistoryRepository;
import com.salespipe.pipeline.infra.DealStageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

@Service
public class StageTransitionService {

    private final DealRepository deals;
    private final DealStageRepository stages;
    private final DealStageHistoryRepository history;
    private final TenantContext tenant;

    public StageTransitionService(DealRepository deals, DealStageRepository stages,
                                  DealStageHistoryRepository history, TenantContext tenant) {
        this.deals = deals; this.stages = stages;
        this.history = history; this.tenant = tenant;
    }

    /** Move a deal to a new stage under optimistic lock. Version mismatch => 409. */
    @Transactional
    public Deal move(UUID dealId, UUID toStageId, int expectedVersion, UUID actorId) {
        Deal deal = deals.findById(dealId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "deal not found"));
        if (deal.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale deal version");
        }
        stages.findById(toStageId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown stage"));

        UUID fromStageId = deal.getStageId();
        deal.moveToStage(toStageId);
        deals.saveAndFlush(deal); // triggers @Version check -> OptimisticLockingFailureException on race

        history.save(new DealStageHistory(UUID.randomUUID(), tenant.getOrgId(),
            dealId, fromStageId, toStageId, actorId));
        return deal;
    }
}
```

(Two guards defend the drag: an explicit `expectedVersion` check catches a client sending a known-stale version → 409; `saveAndFlush` catches a true concurrent race at the DB via JPA `@Version` → `OptimisticLockingFailureException`, mapped to 409 in Task 7.)

- [ ] **Step 4: Write pipeline DTOs**

`pipeline/api/dto/DealRequest.java`:
```java
package com.salespipe.pipeline.api.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DealRequest(@NotNull UUID stageId, UUID leadId, UUID accountId,
                          UUID ownerId, BigDecimal amount, String currency,
                          LocalDate expectedCloseDate) {}
```

`pipeline/api/dto/DealResponse.java`:
```java
package com.salespipe.pipeline.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DealResponse(UUID id, UUID stageId, UUID leadId, UUID accountId,
                           UUID ownerId, BigDecimal amount, String currency,
                           LocalDate expectedCloseDate, int version) {}
```

`pipeline/api/dto/StageChangeRequest.java`:
```java
package com.salespipe.pipeline.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StageChangeRequest(@NotNull UUID toStageId, @NotNull Integer version) {}
```

`pipeline/api/dto/StageColumn.java`:
```java
package com.salespipe.pipeline.api.dto;

import java.util.List;
import java.util.UUID;

public record StageColumn(UUID stageId, String stageName, int position,
                          List<DealResponse> deals) {}
```

- [ ] **Step 5: Write `DealController.java`**

```java
package com.salespipe.pipeline.api;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.common.tenant.TenantFilter.AuthPrincipal;
import com.salespipe.pipeline.api.dto.*;
import com.salespipe.pipeline.domain.Deal;
import com.salespipe.pipeline.domain.DealStage;
import com.salespipe.pipeline.domain.StageTransitionService;
import com.salespipe.pipeline.infra.DealRepository;
import com.salespipe.pipeline.infra.DealStageRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/deals")
public class DealController {

    private final DealRepository deals;
    private final DealStageRepository stages;
    private final StageTransitionService transitions;
    private final TenantContext tenant;

    public DealController(DealRepository deals, DealStageRepository stages,
                          StageTransitionService transitions, TenantContext tenant) {
        this.deals = deals; this.stages = stages;
        this.transitions = transitions; this.tenant = tenant;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DealResponse create(@Valid @RequestBody DealRequest req) {
        Deal deal = new Deal(UUID.randomUUID(), tenant.getOrgId(), req.stageId());
        deal.setLeadId(req.leadId()); deal.setAccountId(req.accountId());
        deal.setOwnerId(req.ownerId()); deal.setAmount(req.amount());
        deal.setCurrency(req.currency()); deal.setExpectedCloseDate(req.expectedCloseDate());
        return toResponse(deals.save(deal));
    }

    @GetMapping("/{id}")
    public DealResponse get(@PathVariable UUID id) {
        return toResponse(deals.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "deal not found")));
    }

    @PatchMapping("/{id}/stage")
    public DealResponse move(@PathVariable UUID id,
                             @Valid @RequestBody StageChangeRequest req,
                             @AuthenticationPrincipal AuthPrincipal principal) {
        Deal moved = transitions.move(id, req.toStageId(), req.version(),
            principal != null ? principal.userId() : null);
        return toResponse(moved);
    }

    @GetMapping("/pipeline")
    public List<StageColumn> pipeline() {
        return stages.findAllByOrderByPositionAsc().stream().map(this::column).toList();
    }

    private StageColumn column(DealStage stage) {
        List<DealResponse> ds = deals.findByStageId(stage.getId())
            .stream().map(this::toResponse).toList();
        return new StageColumn(stage.getId(), stage.getName(), stage.getPosition(), ds);
    }

    private DealResponse toResponse(Deal d) {
        return new DealResponse(d.getId(), d.getStageId(), d.getLeadId(), d.getAccountId(),
            d.getOwnerId(), d.getAmount(), d.getCurrency(), d.getExpectedCloseDate(), d.getVersion());
    }
}
```

- [ ] **Step 6: Write `DealStageController.java` + seeder**

`pipeline/domain/DealStageSeeder.java`:
```java
package com.salespipe.pipeline.domain;

import com.salespipe.pipeline.infra.DealStageRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DealStageSeeder {
    private final DealStageRepository repo;
    public DealStageSeeder(DealStageRepository repo) { this.repo = repo; }

    /** Seed the default pipeline for a freshly-registered org. */
    public void seedDefaults(UUID orgId) {
        repo.save(new DealStage(UUID.randomUUID(), orgId, "NEW", 0, false, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "QUALIFICATION", 1, false, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "PROPOSAL", 2, false, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "WON", 3, true, false));
        repo.save(new DealStage(UUID.randomUUID(), orgId, "LOST", 4, false, true));
    }
}
```

`pipeline/api/DealStageController.java`:
```java
package com.salespipe.pipeline.api;

import com.salespipe.pipeline.domain.DealStage;
import com.salespipe.pipeline.infra.DealStageRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/deal-stages")
public class DealStageController {
    private final DealStageRepository repo;
    public DealStageController(DealStageRepository repo) { this.repo = repo; }

    @GetMapping
    public List<DealStage> list() { return repo.findAllByOrderByPositionAsc(); }
}
```

- [ ] **Step 7: Wire seeder into register**

In `AuthController`, inject `DealStageSeeder` and call `seeder.seedDefaults(org.getId())` right after `orgs.save(org)` in `register(...)`. Add the constructor parameter and field.

- [ ] **Step 8: Write `StageTransitionServiceTest.java`** (unit — stale version → 409)

```java
package com.salespipe.pipeline;

import com.salespipe.common.tenant.TenantContext;
import com.salespipe.pipeline.domain.Deal;
import com.salespipe.pipeline.domain.DealStage;
import com.salespipe.pipeline.domain.StageTransitionService;
import com.salespipe.pipeline.infra.DealRepository;
import com.salespipe.pipeline.infra.DealStageHistoryRepository;
import com.salespipe.pipeline.infra.DealStageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StageTransitionServiceTest {

    @Test
    void staleVersionThrows409() {
        DealRepository deals = mock(DealRepository.class);
        DealStageRepository stages = mock(DealStageRepository.class);
        DealStageHistoryRepository history = mock(DealStageHistoryRepository.class);
        TenantContext tenant = new TenantContext();
        tenant.setOrgId(UUID.randomUUID());

        UUID dealId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        Deal deal = new Deal(dealId, tenant.getOrgId(), UUID.randomUUID());
        when(deals.findById(dealId)).thenReturn(Optional.of(deal));

        var svc = new StageTransitionService(deals, stages, history, tenant);

        // deal.version == 0; client sends expectedVersion=5 -> stale -> 409
        assertThatThrownBy(() -> svc.move(dealId, stageId, 5, UUID.randomUUID()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                .isEqualTo(409));
    }
}
```

- [ ] **Step 9: Run stage transition unit test**

Run: `GRADLE test --tests com.salespipe.pipeline.StageTransitionServiceTest`
Expected: PASS.

- [ ] **Step 10: Write `DealConcurrencyIT.java`** (two concurrent PATCH → one 200, one 409)

```java
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
```

- [ ] **Step 11: Run concurrency IT**

Run: `GRADLE_TC test --tests com.salespipe.pipeline.DealConcurrencyIT`
Expected: PASS (one 200, one 409). If flaky because both read version=0 before either writes, the `saveAndFlush` optimistic check is the backstop; ensure Task 7's handler maps `OptimisticLockingFailureException` → 409 (interim: add `@ResponseStatus(HttpStatus.CONFLICT)` handling is done in Task 7; for this task the explicit version guard already yields 409 for the loser in the common interleaving).

- [ ] **Step 12: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(pipeline): Kanban deals, stages, optimistic-lock stage moves, history"
git push origin main
```

---

## Task 7: Cross-cutting — RFC7807 errors, audit, JSON logging (T1.7)

**Files:**
- Create: `common/exception/GlobalExceptionHandler.java`, `common/audit/{AuditLog,AuditLogRepository,AuditAspect}.java`, `src/main/resources/logback-spring.xml`
- Test: `common/exception/ErrorEnvelopeIT.java`, `common/audit/AuditIT.java`

**Interfaces:**
- Consumes: all controllers/services.
- Produces: consistent `application/problem+json` errors; `audit_log` rows on mutating ops.

- [ ] **Step 1: Write `GlobalExceptionHandler.java`**

```java
package com.salespipe.common.exception;

import com.salespipe.identity.infra.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setDetail(ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b).orElse("invalid request"));
        return pd;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail onOptimisticLock(OptimisticLockingFailureException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Concurrent modification");
        pd.setDetail("The resource was modified concurrently; retry with the latest version.");
        return pd;
    }

    @ExceptionHandler({RefreshTokenService.TokenReuseException.class,
                       RefreshTokenService.InvalidTokenException.class})
    public ProblemDetail onToken(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Authentication failed");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail onStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(ex.getStatusCode());
        pd.setDetail(ex.getReason());
        return pd;
    }
}
```

(Now that the handler owns 401/409/400/404 mapping centrally, the interim `@ResponseStatus` annotations added in Task 4 Step 17 remain harmless but redundant — leave them; Spring uses the `@ExceptionHandler` here for problem+json bodies.)

- [ ] **Step 2: Write audit entity + repo**

`common/audit/AuditLog.java`:
```java
package com.salespipe.common.audit;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id private UUID id;
    @Column(name = "org_id") private UUID orgId;
    @Column(name = "actor_id") private UUID actorId;
    private String action;
    @Column(name = "entity_type") private String entityType;
    @Column(name = "entity_id") private UUID entityId;
    @Column(columnDefinition = "jsonb") private String diff;
    @Column(name = "created_at") private OffsetDateTime createdAt;

    protected AuditLog() {}
    public AuditLog(UUID id, UUID orgId, UUID actorId, String action,
                    String entityType, UUID entityId, String diff) {
        this.id = id; this.orgId = orgId; this.actorId = actorId;
        this.action = action; this.entityType = entityType;
        this.entityId = entityId; this.diff = diff;
        this.createdAt = OffsetDateTime.now();
    }
}
```

(Store `diff` as a JSON string into a `jsonb` column — no extra dependency needed; Postgres casts text→jsonb on insert via Hibernate's `columnDefinition`. Remove the `JsonType`/`Type` imports; they are not used. Final file omits the `io.hypersistence`/`org.hibernate.annotations.Type` imports.)

`common/audit/AuditLogRepository.java`:
```java
package com.salespipe.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {}
```

- [ ] **Step 3: Fix `AuditLog` imports (remove unused)**

Final `AuditLog.java` top:
```java
package com.salespipe.common.audit;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
```
(Delete the `io.hypersistence` and `org.hibernate.annotations.Type` import lines and the `@Type` annotation; keep `@Column(columnDefinition = "jsonb")`.)

- [ ] **Step 4: Write `AuditAspect.java`**

```java
package com.salespipe.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salespipe.common.tenant.TenantContext;
import com.salespipe.common.tenant.TenantFilter.AuthPrincipal;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
public class AuditAspect {

    private final AuditLogRepository repo;
    private final TenantContext tenant;
    private final ObjectMapper json;

    public AuditAspect(AuditLogRepository repo, TenantContext tenant, ObjectMapper json) {
        this.repo = repo; this.tenant = tenant; this.json = json;
    }

    @Pointcut("(@annotation(org.springframework.web.bind.annotation.PostMapping) " +
              "|| @annotation(org.springframework.web.bind.annotation.PutMapping) " +
              "|| @annotation(org.springframework.web.bind.annotation.PatchMapping) " +
              "|| @annotation(org.springframework.web.bind.annotation.DeleteMapping)) " +
              "&& within(com.salespipe..api..*)")
    void mutating() {}

    @AfterReturning(pointcut = "mutating()", returning = "result")
    public void record(JoinPoint jp, Object result) {
        if (!tenant.isSet()) return; // e.g. /auth endpoints run pre-tenant; skip
        try {
            String diff = json.writeValueAsString(result);
            repo.save(new AuditLog(UUID.randomUUID(), tenant.getOrgId(), actor(),
                jp.getSignature().getName(),
                jp.getSignature().getDeclaringType().getSimpleName(), null, diff));
        } catch (Exception ignored) { /* audit must never break the request */ }
    }

    private UUID actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthPrincipal p) return p.userId();
        return null;
    }
}
```

- [ ] **Step 5: Write `logback-spring.xml`**

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>org_id</includeMdcKeyName>
            <includeMdcKeyName>user_id</includeMdcKeyName>
            <includeMdcKeyName>trace_id</includeMdcKeyName>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

- [ ] **Step 6: Put org_id/user_id into MDC**

In `TenantFilter.doFilter`, after `tenantContext.setOrgId(p.orgId())`, add:
```java
org.slf4j.MDC.put("org_id", p.orgId().toString());
org.slf4j.MDC.put("user_id", p.userId().toString());
```
and wrap the `chain.doFilter` in try/finally that calls `org.slf4j.MDC.clear()`.

- [ ] **Step 7: Write `ErrorEnvelopeIT.java`**

```java
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
```

- [ ] **Step 8: Write `AuditIT.java`**

```java
package com.salespipe.common.audit;

import com.salespipe.support.PostgresRedisTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class AuditIT extends PostgresRedisTestBase {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    String token;

    @BeforeEach
    void setup() {
        RestAssured.port = port;
        token = given().contentType(ContentType.JSON)
            .body(Map.of("orgName", "Aud", "email", "a@aud.com", "password", "password123"))
            .post("/auth/register").then().statusCode(201).extract().path("accessToken");
    }

    @Test
    void mutatingLeadWritesAuditRow() {
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON)
            .body(Map.of("status", "NEW")).post("/leads").then().statusCode(201);

        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE entity_type='LeadController'", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 9: Run cross-cutting ITs + full identity/pipeline regression**

Run: `GRADLE_TC test --tests com.salespipe.common.* --tests com.salespipe.identity.AuthFlowIT --tests com.salespipe.pipeline.DealConcurrencyIT`
Expected: PASS. Validation → problem+json; audit row written; reuse still 401; concurrency still 200/409 (now via central handler too).

- [ ] **Step 10: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(common): RFC7807 errors, audit aspect, structured JSON logging"
git push origin main
```

---

## Task 8: API docs — springdoc/Swagger (T1.8)

**Files:**
- Create: `common/config/OpenApiConfig.java`
- Test: `common/OpenApiIT.java`

**Interfaces:**
- Produces: Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`.

- [ ] **Step 1: Write `OpenApiConfig.java`**

```java
package com.salespipe.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI salespipeApi() {
        return new OpenAPI()
            .info(new Info().title("SalesPipe API").version("v1"))
            .components(new Components().addSecuritySchemes("bearer",
                new SecurityScheme().type(SecurityScheme.Type.HTTP)
                    .scheme("bearer").bearerFormat("JWT")));
    }
}
```

- [ ] **Step 2: Write `OpenApiIT.java`** (api-docs lists Phase 1 endpoints)

```java
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
```

- [ ] **Step 3: Run OpenAPI IT**

Run: `GRADLE_TC test --tests com.salespipe.OpenApiIT`
Expected: PASS.

- [ ] **Step 4: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(docs): springdoc OpenAPI + Swagger UI"
git push origin main
```

---

## Task 9: Containerization + local compose (T1.9)

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `.dockerignore`

**Interfaces:**
- Produces: a runnable image; `docker compose up` brings app + Postgres + Redis online.

- [ ] **Step 1: Write `.dockerignore`**

```
.git
.gradle
build
*.md
k8s
docs
```

- [ ] **Step 2: Write multi-stage `Dockerfile`**

```dockerfile
# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies || true
COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- run ----
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
RUN useradd -r -u 1001 appuser
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: Write `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: salespipe
      POSTGRES_USER: salespipe
      POSTGRES_PASSWORD: salespipe
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U salespipe"]
      interval: 5s
      timeout: 3s
      retries: 10

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  app:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/salespipe
      DB_USER: salespipe
      DB_PASSWORD: salespipe
      REDIS_HOST: redis
      JWT_SECRET: compose-secret-min-256-bits-long-for-hs256-xxxxxxxxxxxxxxx
    ports: ["8080:8080"]
```

- [ ] **Step 4: Build + run compose, smoke-test login**

Run:
```bash
docker compose up -d --build
# wait for app health
until curl -sf http://localhost:8080/actuator/health/readiness; do sleep 3; done
curl -sf -X POST http://localhost:8080/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"orgName":"Smoke","email":"s@smoke.com","password":"password123"}'
```
Expected: readiness returns `{"status":"UP"}`; register returns a JSON body with `accessToken`.

- [ ] **Step 5: Tear down**

Run: `docker compose down -v`
Expected: containers + volumes removed.

- [ ] **Step 6: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(docker): multi-stage image + compose (app/postgres/redis)"
git push origin main
```

---

## Task 10: K8s single Deployment (T1.10)

**Files:**
- Create: `k8s/{namespace,configmap,secret,postgres,redis,deployment,service}.yaml`, `k8s/README.md`

**Interfaces:**
- Produces: `kubectl apply -f k8s/` yields a Ready app pod reachable via Service.

- [ ] **Step 1: Write `k8s/namespace.yaml`**

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: salespipe
```

- [ ] **Step 2: Write `k8s/configmap.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: salespipe-config
  namespace: salespipe
data:
  DB_URL: jdbc:postgresql://postgres:5432/salespipe
  DB_USER: salespipe
  REDIS_HOST: redis
```

- [ ] **Step 3: Write `k8s/secret.yaml`**

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: salespipe-secret
  namespace: salespipe
type: Opaque
stringData:
  DB_PASSWORD: salespipe
  JWT_SECRET: k8s-secret-min-256-bits-long-for-hs256-xxxxxxxxxxxxxxxxxxxxx
```

- [ ] **Step 4: Write `k8s/postgres.yaml`** (StatefulSet + Service + PVC)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: salespipe
spec:
  selector: { app: postgres }
  ports: [{ port: 5432, targetPort: 5432 }]
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: salespipe
spec:
  serviceName: postgres
  replicas: 1
  selector: { matchLabels: { app: postgres } }
  template:
    metadata: { labels: { app: postgres } }
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          env:
            - { name: POSTGRES_DB, value: salespipe }
            - { name: POSTGRES_USER, value: salespipe }
            - { name: POSTGRES_PASSWORD, value: salespipe }
          ports: [{ containerPort: 5432 }]
          volumeMounts:
            - { name: data, mountPath: /var/lib/postgresql/data }
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: ["ReadWriteOnce"]
        resources: { requests: { storage: 1Gi } }
```

- [ ] **Step 5: Write `k8s/redis.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: salespipe
spec:
  selector: { app: redis }
  ports: [{ port: 6379, targetPort: 6379 }]
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: salespipe
spec:
  replicas: 1
  selector: { matchLabels: { app: redis } }
  template:
    metadata: { labels: { app: redis } }
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports: [{ containerPort: 6379 }]
```

- [ ] **Step 6: Write `k8s/deployment.yaml`** (app with probes)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: salespipe
  namespace: salespipe
spec:
  replicas: 1
  selector: { matchLabels: { app: salespipe } }
  template:
    metadata: { labels: { app: salespipe } }
    spec:
      containers:
        - name: salespipe
          image: salespipe:local
          imagePullPolicy: IfNotPresent
          ports: [{ containerPort: 8080 }]
          envFrom:
            - configMapRef: { name: salespipe-config }
            - secretRef: { name: salespipe-secret }
          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            failureThreshold: 30
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            periodSeconds: 10
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            periodSeconds: 10
```

- [ ] **Step 7: Write `k8s/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: salespipe
  namespace: salespipe
spec:
  selector: { app: salespipe }
  ports: [{ port: 80, targetPort: 8080 }]
```

- [ ] **Step 8: Write `k8s/README.md`**

```markdown
# Local K8s (kind)

```bash
kind create cluster --name salespipe
docker build -t salespipe:local .
kind load docker-image salespipe:local --name salespipe
kubectl apply -f k8s/
kubectl -n salespipe rollout status deploy/salespipe --timeout=180s
kubectl -n salespipe port-forward svc/salespipe 8080:80
```

Managed Postgres/Redis: drop `postgres.yaml`/`redis.yaml`, point `salespipe-config`/`secret`
at the managed endpoints. StatefulSet+PVC is the local CORE path only.
```

- [ ] **Step 9: Deploy to kind + verify Ready**

Run:
```bash
kind create cluster --name salespipe
docker build -t salespipe:local .
kind load docker-image salespipe:local --name salespipe
kubectl apply -f k8s/
kubectl -n salespipe rollout status deploy/salespipe --timeout=180s
```
Expected: `deployment "salespipe" successfully rolled out`.

- [ ] **Step 10: Smoke-test through the Service**

Run:
```bash
kubectl -n salespipe port-forward svc/salespipe 8080:80 &
sleep 5
curl -sf -X POST http://localhost:8080/auth/register -H 'Content-Type: application/json' \
  -d '{"orgName":"K8s","email":"k@k8s.com","password":"password123"}'
kill %1
```
Expected: JSON body with `accessToken`.

- [ ] **Step 11: Tear down cluster**

Run: `kind delete cluster --name salespipe`

- [ ] **Step 12: Commit + push**

```bash
git add -A
git -c user.name=divanshu0212 -c user.email=divanshu0212@gmail.com \
  commit -m "feat(k8s): single Deployment + Service + Postgres/Redis + probes"
git push origin main
```

---

## Final verification (whole Phase 1)

- [ ] **Run the full suite**

Run: `GRADLE_TC test`
Expected: BUILD SUCCESSFUL — all unit, integration, contract, arch, and module-boundary tests green.

- [ ] **Confirm acceptance criteria met** (from the spec):
  - Module boundary test passes; app boots.
  - Flyway migrates clean; Testcontainers brings schema up.
  - Org A cannot read org B's leads (tenant IT 404).
  - login→refresh→refresh works; replaying a used refresh token 401s and revokes family; SALES_REP denied on a role-gated route.
  - Lead CRUD tenant-scoped + paginated/filtered.
  - Two concurrent stage PATCHes → one 200, one 409; history row written; pipeline grouped.
  - Validation → problem+json; mutating a lead writes an audit row.
  - Swagger UI + api-docs list Phase 1 endpoints.
  - `docker compose up` login works; `kubectl apply` → pod Ready, reachable via Service.
