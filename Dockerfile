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
