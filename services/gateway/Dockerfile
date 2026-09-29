# syntax=docker/dockerfile:1.7
# =============================================================================
#  Horizon :: gateway — Spring Cloud Gateway (WebFlux), порт 8080
# =============================================================================
#  ВАЖНО: контекст сборки — каталог `services/`, а не `services/gateway/`,
#  потому что реактор Maven собирает модуль вместе с зависимостями (`-pl ... -am`).
#
#      docker build -f services/gateway/Dockerfile -t horizon/gateway services/
#
#  Игнор-файл контекста — `services/.dockerignore`.
#  Соответствие NFR-S9: non-root, read-only rootfs, drop ALL caps, jre-alpine.
# =============================================================================

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine
ARG MODULE=gateway

# ─────────────────────────── Stage 1: разогрев зависимостей ───────────────────────────
# Отдельный слой только с pom-файлами: пока не менялись зависимости, слой берётся из кэша.
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
# Сборка образа обязана быть быстрой и детерминированной.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -pl ${MODULE} -am package \
        -DskipTests -Dmaven.test.skip=true \
        -Dspotless.check.skip=true -Djacoco.skip=true \
 && cp ${MODULE}/target/*.jar /build/app.jar

# Раскладка fat-jar по слоям Spring Boot: зависимости меняются редко, код — часто,
# поэтому при обновлении сервиса по сети едет только последний слой.
WORKDIR /build/extracted
RUN java -Djarmode=layertools -jar /build/app.jar extract \
 && mkdir -p dependencies spring-boot-loader snapshot-dependencies application

# ─────────────────────────── Stage 3: runtime ───────────────────────────
FROM ${RUNTIME_IMAGE} AS runtime

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="horizon-gateway" \
      org.opencontainers.image.description="Horizon API gateway (Spring Cloud Gateway)" \
      org.opencontainers.image.source="https://github.com/horizon/horizon" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Proprietary"

# Непривилегированный пользователь с фиксированным UID: тот же UID указан в
# securityContext.runAsUser Helm-чарта, иначе read-only rootfs даёт неожиданные отказы.
RUN addgroup -S -g 10001 horizon \
 && adduser  -S -u 10001 -G horizon -H -s /sbin/nologin horizon \
 && mkdir -p /app /tmp/horizon \
 && chown -R horizon:horizon /app /tmp/horizon

WORKDIR /app

# Порядок COPY = порядок изменчивости слоёв, от редкого к частому.
COPY --from=build --chown=horizon:horizon /build/extracted/dependencies/          ./
COPY --from=build --chown=horizon:horizon /build/extracted/spring-boot-loader/    ./
COPY --from=build --chown=horizon:horizon /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=horizon:horizon /build/extracted/application/           ./

USER 10001:10001

# Container-aware heap: JVM видит cgroup-лимит, 75% отдаём куче.
# Абсолютный -Xmx здесь запрещён — лимит памяти задаётся в одном месте: compose/Helm.
ENV SERVER_PORT=8080 \
    OTEL_SERVICE_NAME=gateway \
    SPRING_MAIN_BANNER_MODE=off \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=40.0 -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Djava.io.tmpdir=/tmp -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.awt.headless=true"

EXPOSE 8080

# read-only rootfs: единственный записываемый путь — /tmp.
# Инструкция VOLUME намеренно НЕ используется: она плодит анонимные тома на каждый
# `docker run` и игнорируется Kubernetes. Точка монтирования задаётся снаружи:
# tmpfs в compose, emptyDir в Helm-чарте.

# busybox wget входит в alpine — отдельный curl ставить не нужно.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

# Exec-форма: PID 1 = java, значит SIGTERM доходит до приложения и graceful
# shutdown укладывается в 30 с (NFR-R7).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
