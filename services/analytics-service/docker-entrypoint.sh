#!/bin/sh
# =============================================================================
#  analytics-service — точка входа. Один образ, две роли (ADR-0014).
# =============================================================================
#  Использование:
#     docker-entrypoint.sh api           запускает HTTP API (uvicorn) на :8000
#     docker-entrypoint.sh worker        запускает Kafka-консьюмер (aiokafka)
#     docker-entrypoint.sh healthcheck   служебная роль для HEALTHCHECK
#     docker-entrypoint.sh <прочее>      выполняется как есть (отладка: sh, python -c ...)
#
#  Контракт: процесс роли запускается через `exec`, поэтому он получает PID 1
#  и принимает SIGTERM напрямую — без этого graceful shutdown не работает.
# =============================================================================
set -eu

ROLE="${1:-${HORIZON_ANALYTICS_ROLE:-api}}"
PORT="${HORIZON_ANALYTICS_PORT:-8000}"
HOST="${HORIZON_ANALYTICS_HOST:-0.0.0.0}"
LOG_LEVEL_LOWER="$(echo "${HORIZON_LOG_LEVEL:-info}" | tr '[:upper:]' '[:lower:]')"

# Ресурсные атрибуты OTel: имя роли попадает в трейсы и метрики,
# иначе api и worker неразличимы на дашбордах (NFR-O1).
export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES:-service.name=analytics-service},service.role=${ROLE}"

log() { printf '{"level":"INFO","logger":"entrypoint","message":"%s"}\n' "$1"; }

case "${ROLE}" in

  api)
    log "starting analytics-service role=api on ${HOST}:${PORT}"
    # --workers 1: горизонтальное масштабирование делает оркестратор (NFR-P4),
    #   а не менеджер процессов внутри контейнера — иначе метрики и лимиты врут.
    # --timeout-graceful-shutdown 25: укладываемся в бюджет NFR-R7 (<= 30 c).
    # --proxy-headers: за шлюзом, реальный клиентский IP приходит в X-Forwarded-For.
    exec python -m uvicorn horizon_analytics.api.main:app \
      --host "${HOST}" \
      --port "${PORT}" \
      --workers 1 \
      --proxy-headers \
      --forwarded-allow-ips '*' \
      --timeout-graceful-shutdown 25 \
      --log-level "${LOG_LEVEL_LOWER}" \
      --no-access-log
    ;;

  worker)
    log "starting analytics-service role=worker concurrency=${HORIZON_ANALYTICS_WORKER_CONCURRENCY:-2}"
    exec python -m horizon_analytics.worker
    ;;

  healthcheck)
    # Роль api отвечает на /health; у worker HTTP-порта нет, поэтому там
    # проверяется файл живости, который консьюмер обновляет после каждого poll.
    if [ "${HORIZON_ANALYTICS_ROLE:-api}" = "worker" ]; then
      LIVENESS_FILE="${HORIZON_WORKER_LIVENESS_FILE:-/tmp/worker-alive}"
      [ -f "${LIVENESS_FILE}" ] || { echo "liveness file ${LIVENESS_FILE} is missing"; exit 1; }
      # Файл старше 2 минут = консьюмер завис (или потерял координатора группы).
      # Консьюмер обязан обновлять его не реже, чем раз в 30 с.
      if [ -n "$(find "${LIVENESS_FILE}" -mmin +2 2>/dev/null)" ]; then
        echo "liveness file ${LIVENESS_FILE} is stale (> 2 min)"
        exit 1
      fi
      exit 0
    fi
    exec curl --fail --silent --show-error --max-time 4 \
      "http://127.0.0.1:${PORT}/health" -o /dev/null
    ;;

  migrate)
    # Миграции Alembic (NFR-M7). Запускается отдельным one-shot контейнером/Job,
    # а не при старте сервиса: параллельный старт реплик даёт гонку блокировок.
    log "applying alembic migrations"
    exec python -m alembic upgrade head
    ;;

  *)
    # Отладочный режим: `docker compose run analytics-api sh`.
    exec "$@"
    ;;
esac
