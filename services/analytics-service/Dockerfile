# syntax=docker/dockerfile:1.7
# =============================================================================
#  Horizon :: analytics-service — Python 3.12 / FastAPI
# =============================================================================
#  Один образ, две роли (ADR-0014):
#     docker run ... horizon/analytics-service api      → uvicorn на :8000
#     docker run ... horizon/analytics-service worker   → aiokafka-консьюмер
#  Роль передаётся аргументом CMD и разбирается в docker-entrypoint.sh.
#
#  Контекст сборки — `services/analytics-service/`.
#      docker build -f services/analytics-service/Dockerfile \
#                   -t horizon/analytics-service services/analytics-service
#
#  Соответствие NFR-S9: non-root, read-only rootfs, drop ALL caps.
# =============================================================================

ARG PYTHON_IMAGE=python:3.12-slim

# ─────────────────────────── Stage 1: сборка колёс ───────────────────────────
# Компиляторы и заголовки нужны только здесь (numpy/scikit-learn/psycopg могут
# собираться из исходников). В runtime-образ они не попадают.
FROM ${PYTHON_IMAGE} AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN apt-get update \
 && apt-get install --no-install-recommends -y \
      build-essential \
      libpq-dev \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Виртуальное окружение целиком копируется в runtime — это даёт минимальный
# runtime-образ без pip-метаданных сборочных пакетов.
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:${PATH}"

# Зависимости берутся из pyproject.toml — оттуда же, откуда их ставят тесты.
#
# Раньше здесь стоял отдельный requirements.txt, и два списка разошлись: `orjson` и `asyncpg`
# объявлены в pyproject и импортируются кодом, но в пинах их не было — контейнер падал на
# `ModuleNotFoundError: No module named 'orjson'` при первом же запуске. Расхождение шире одного
# пакета: тесты шли на fastapi 0.141, образ собирался на 0.115, то есть проверяли не то, что
# выкатывали. Один список закрывает оба отказа разом.
#
# Исходники копируются здесь же, потому что установка пакета требует их наличия: слой
# пересобирается на любую правку кода, и это осознанная плата за единственный источник истины.
COPY pyproject.toml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.cache/pip,sharing=locked \
    pip install --upgrade pip setuptools wheel \
 && pip install .

# ─────────────────────────── Stage 2: runtime ───────────────────────────
FROM ${PYTHON_IMAGE} AS runtime

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="horizon-analytics-service" \
      org.opencontainers.image.description="Horizon NLP/indicators/ranking service (FastAPI, roles: api|worker)" \
      org.opencontainers.image.source="https://github.com/horizon/horizon" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Proprietary"

# libpq5 — единственная разделяемая библиотека, нужная psycopg в runtime.
# curl нужен для HEALTHCHECK; python-slim его не содержит.
RUN apt-get update \
 && apt-get install --no-install-recommends -y \
      libpq5 \
      curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 10001 horizon \
 && useradd  --system --uid 10001 --gid horizon --no-create-home --shell /usr/sbin/nologin horizon \
 && mkdir -p /app /opt/horizon/cache \
 && chown -R horizon:horizon /app /opt/horizon

COPY --from=builder --chown=horizon:horizon /opt/venv /opt/venv

WORKDIR /app
COPY --chown=horizon:horizon docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0555 /usr/local/bin/docker-entrypoint.sh

# Исходники пакета. `horizon_analytics` — единый пакет для обеих ролей (ADR-0014).
# Копируется последним: код меняется чаще всего, слой должен быть верхним.
COPY --chown=horizon:horizon . /app/

USER 10001:10001

ENV PATH="/opt/venv/bin:${PATH}" \
    PYTHONPATH=/app/src \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONFAULTHANDLER=1 \
    PIP_NO_CACHE_DIR=1 \
    HOME=/tmp \
    MPLCONFIGDIR=/tmp \
    XDG_CACHE_HOME=/tmp \
    NUMBA_CACHE_DIR=/tmp \
    HORIZON_ANALYTICS_ROLE=api \
    HORIZON_ANALYTICS_PORT=8000 \
    OTEL_SERVICE_NAME=analytics-service

# Потоки BLAS: без ограничения numpy/scikit-learn запускает по потоку на ядро хоста,
# игнорируя cgroup-лимит, и контейнер выедает CPU соседей.
ENV OMP_NUM_THREADS=2 \
    OPENBLAS_NUM_THREADS=2 \
    MKL_NUM_THREADS=2

EXPOSE 8000

# Роль `worker` HTTP не слушает: там healthcheck заменяется на проверку
# живости консьюмера в самом entrypoint (см. HORIZON_ANALYTICS_ROLE).
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD /usr/local/bin/docker-entrypoint.sh healthcheck

# Exec-форма: PID 1 = entrypoint (sh), который делает `exec` на процесс роли,
# поэтому SIGTERM доходит до uvicorn/консьюмера (NFR-R7, graceful shutdown <= 30 c).
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["api"]
