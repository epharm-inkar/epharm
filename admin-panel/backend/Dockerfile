# ── Backend (Kotlin + Spring Boot) — multi-stage prod-образ ──────────────────
# Сборка исполняемого jar внутри образа (не нужен локальный JDK), затем лёгкий JRE-runtime.
# Контекст сборки: admin-panel/backend (см. docker-compose.prod.yml).

FROM eclipse-temurin:22-jdk AS build
WORKDIR /app
# Сначала только gradle-метаданные и загрузка Gradle с зависимостями отдельным слоем: Docker берёт его из кэша,
# пока не менялись файлы сборки, и выпуск с новым кодом только компилирует (на сервере старый сборщик Docker,
# кэша --mount там нет). Номер выпуска сюда не передаётся: ARG перед RUN сбрасывал бы этот кэш каждый раз.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
# Затем исходники и сборка boot-jar (тесты гоняет CI, здесь -x test для скорости).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar verifyMerchandisingArtifact -x test

FROM eclipse-temurin:22-jre AS runtime
# curl нужен для HEALTHCHECK (в JRE-образе его нет). Слой до ARG выпуска, чтобы он не собирался заново.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
ARG RELEASE_ID=dev
ARG RELEASE_COMMIT=unknown
LABEL org.opencontainers.image.title="Epharm backend" \
      org.opencontainers.image.version="$RELEASE_ID" \
      org.opencontainers.image.revision="$RELEASE_COMMIT"
WORKDIR /app
ENV RELEASE_ID="$RELEASE_ID" \
    RELEASE_COMMIT="$RELEASE_COMMIT"
COPY --from=build /app/build/libs/app.jar app.jar
EXPOSE 8080
# Старт может занять время (Flyway-миграции) → start-period.
HEALTHCHECK --interval=15s --timeout=5s --retries=10 --start-period=90s \
    CMD curl -fsS http://localhost:8080/api/health || exit 1
# Bound heap within the 2 GiB production budget even when JVM ergonomics see host RAM.
ENTRYPOINT ["java", "-Xmx1280m", "-jar", "/app/app.jar"]
