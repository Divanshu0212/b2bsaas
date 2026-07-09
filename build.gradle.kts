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

repositories {
    mavenCentral()
    // Confluent artifacts (kafka-json-schema-serializer, kafka-schema-registry-client)
    // are not published to Maven Central.
    maven {
        url = uri("https://packages.confluent.io/maven/")
    }
}

extra["springModulithVersion"] = "1.3.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // T3.4: WebClient for the resilient call to the Python scoring service. webflux is
    // pulled in for WebClient only; the app stays servlet-based (MVC) otherwise.
    implementation("org.springframework.boot:spring-boot-starter-webflux")
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
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.confluent:kafka-json-schema-serializer:7.7.1") {
        // Confluent ships its own slf4j/logback transitively; keep Spring Boot's.
        exclude(group = "org.slf4j")
    }
    // T2.4: consumer-side retry with exponential backoff before DLQ (overview §6.3).
    // 2.4.0 is the current release compatible with Spring Boot 3.4.x (needs
    // spring-boot-starter-actuator + spring-boot-starter-aop, both already deps above).
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.4.0")
    // T2.6: Redis-backed per-tenant rate limiting on the public email tracking/webhook
    // endpoints (overview §6.1: "Rate limiting: Bucket4j + Redis, per-tenant"). Uses the
    // Lettuce-based bucket4j proxy manager since spring-boot-starter-data-redis (already
    // a dep above) brings Lettuce onto the classpath. Artifact ids are the JDK17-targeted
    // "_jdk17-*" variants (Bucket4j 8.x publishes separate jars per JDK baseline; this
    // repo targets Java 21, so jdk17 is the right floor) -- there is no plain
    // "bucket4j-core"/"bucket4j-redis" artifact for this version line.
    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")
    implementation("com.bucket4j:bucket4j_jdk17-lettuce:8.14.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    // T3.3: contract test for the Python scoring service's POST /internal/score. WireMock
    // stubs the ML endpoint so ScoringClient's request/response mapping + resilience
    // fallback are verified without a live Python service.
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
    }
}

tasks.withType<Test> { useJUnitPlatform() }
