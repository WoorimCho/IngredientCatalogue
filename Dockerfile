# syntax=docker/dockerfile:1

# ── Stage 1: build the boot jar ───────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Wrapper first so the Gradle download layer caches independently of sources.
COPY gradlew ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src

# --mount=type=cache keeps the Gradle dependency cache across builds (BuildKit).
RUN --mount=type=cache,target=/root/.gradle,id=ingredientcatelog-gradle ./gradlew --no-daemon clean bootJar -x test

# ── Stage 2: minimal runtime ─────────────────────────────────────────────────
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
