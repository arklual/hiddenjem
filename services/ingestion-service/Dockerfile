# syntax=docker/dockerfile:1.7
# =============================================================================
#  Horizon :: ingestion-service — коннекторы источников, outbox, дедуп. Порт 8083
# =============================================================================
#  ВАЖНО: контекст сборки — каталог `services/`, а не `services/ingestion-service/`,
#  потому что реактор Maven собирает модуль вместе с зависимостями (`-pl ... -am`).
#
#      docker build -f services/ingestion-service/Dockerfile -t horizon/ingestion-service services/
#
#  Игнор-файл контекста — `services/.dockerignore`.
#  Соответствие NFR-S9: non-root, read-only rootfs, drop ALL caps, jre-alpine.
# =============================================================================

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine
ARG MODULE=ingestion-service

# ─────────────────────────── Stage 1: разогрев зависимостей ───────────────────────────
FROM ${MAVEN_IMAGE} AS deps
ARG MODULE
WORKDIR /build
COPY pom.xml ./
COPY platform/platform-common/pom.xml platform/platform-common/
COPY platform/platform-spring/pom.xml platform/platform-spring/
COPY ingestion-service/pom.xml         ingestion-service/
COPY trends-service/pom.xml            trends-service/
COPY gateway/pom.xml                   gateway/
# `|| true`: это разогрев кэша, а не сборка. Полноценная проверка зависимостей
# происходит на следующем этапе — там ошибка обязана падать.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -pl ${MODULE} -am dependency:go-offline -DskipTests || true

# ─────────────────────────── Stage 2: сборка модуля ───────────────────────────
FROM deps AS build
ARG MODULE
WORKDIR /build
COPY . .
# Тесты в образе не гоняем: за них отвечает CI (job `java-build`).
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -pl ${MODULE} -am package \
        -DskipTests -Dmaven.test.skip=true \
        -Dspotless.check.skip=true -Djacoco.skip=true \
 && cp ${MODULE}/target/*.jar /build/app.jar

WORKDIR /build/extracted
RUN java -Djarmode=layertools -jar /build/app.jar extract \
 && mkdir -p dependencies spring-boot-loader snapshot-dependencies application

# ─────────────────────────── Stage 3: runtime ───────────────────────────
FROM ${RUNTIME_IMAGE} AS runtime

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="horizon-ingestion-service" \
      org.opencontainers.image.description="Horizon source connectors, normalization, outbox" \
      org.opencontainers.image.source="https://github.com/horizon/horizon" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Proprietary"

RUN addgroup -S -g 10001 horizon \
 && adduser  -S -u 10001 -G horizon -H -s /sbin/nologin horizon \
 && mkdir -p /app /tmp/horizon /var/lib/horizon/raw \
 && chown -R horizon:horizon /app /tmp/horizon /var/lib/horizon

WORKDIR /app

COPY --from=build --chown=horizon:horizon /build/extracted/dependencies/          ./
COPY --from=build --chown=horizon:horizon /build/extracted/spring-boot-loader/    ./
COPY --from=build --chown=horizon:horizon /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=horizon:horizon /build/extracted/application/           ./

USER 10001:10001

# Container-aware heap: JVM видит cgroup-лимит, 75% отдаём куче.
# Абсолютный -Xmx здесь запрещён — лимит памяти задаётся в одном месте: compose/Helm.
ENV SERVER_PORT=8083 \
    OTEL_SERVICE_NAME=ingestion-service \
    SPRING_MAIN_BANNER_MODE=off \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=40.0 -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djava.io.tmpdir=/tmp -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.awt.headless=true"

EXPOSE 8083

# Записываемые пути: /tmp и /var/lib/horizon/raw (сырые ответы источников, NFR-D3, 90 дней).
# Оба монтируются снаружи (tmpfs/named volume в compose, emptyDir/PVC в Helm),
# поэтому rootfs остаётся read-only. VOLUME не объявляем — плодит анонимные тома.

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8083/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
