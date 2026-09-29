#!/bin/sh
# =============================================================================
#  frontend — точка входа nginx.
# =============================================================================
#  Задача: подставить в конфигурацию значения из окружения (12-factor, NFR-C2)
#  и запустить nginx с read-only корневой ФС.
#
#  Почему не штатный /docker-entrypoint.d/20-envsubst-on-templates.sh:
#  он пишет результат в /etc/nginx/conf.d, а корень смонтирован read-only.
#  Мы кладём готовый конфиг и все временные файлы nginx в /tmp (tmpfs).
# =============================================================================
set -eu

CONF_TEMPLATE="${NGINX_CONF_TEMPLATE:-/etc/nginx/nginx.conf.template}"
CONF_DIR="${NGINX_RUNTIME_DIR:-/tmp/nginx}"
CONF_OUT="${CONF_DIR}/nginx.conf"

if [ "${1:-nginx}" != "nginx" ]; then
  # Отладочный режим: `docker compose run frontend sh`.
  exec "$@"
fi

mkdir -p "${CONF_DIR}/client_temp" "${CONF_DIR}/proxy_temp" \
         "${CONF_DIR}/fastcgi_temp" "${CONF_DIR}/uwsgi_temp" "${CONF_DIR}/scgi_temp"

# Явный список переменных обязателен: без него envsubst съест nginx-переменные
# ($uri, $host, $remote_addr, ...) и конфиг развалится.
SUBST_VARS='${NGINX_PORT} ${NGINX_RESOLVER} ${HORIZON_GATEWAY_URL} ${HORIZON_API_TIMEOUT} ${HORIZON_SSE_TIMEOUT} ${HORIZON_CSP_CONNECT_SRC} ${HORIZON_HSTS_DIRECTIVE}'

export NGINX_PORT="${NGINX_PORT:-8080}"
# 127.0.0.11 — встроенный DNS Docker. В Kubernetes сюда подставляется ClusterIP kube-dns.
export NGINX_RESOLVER="${NGINX_RESOLVER:-127.0.0.11}"
export HORIZON_GATEWAY_URL="${HORIZON_GATEWAY_URL:-http://gateway:8080}"
export HORIZON_API_TIMEOUT="${HORIZON_API_TIMEOUT:-60s}"
export HORIZON_SSE_TIMEOUT="${HORIZON_SSE_TIMEOUT:-300s}"
export HORIZON_CSP_CONNECT_SRC="${HORIZON_CSP_CONNECT_SRC:-'self'}"
# HSTS имеет смысл только при TLS; локально по HTTP заголовок вреден
# (браузер запомнит домен и перестанет ходить по http). Пусто = директивы нет.
export HORIZON_HSTS_DIRECTIVE="${HORIZON_HSTS_DIRECTIVE:-}"

envsubst "${SUBST_VARS}" < "${CONF_TEMPLATE}" > "${CONF_OUT}"

# Проверка конфигурации до старта: лучше упасть здесь с внятной ошибкой,
# чем отдавать 502 после того, как оркестратор посчитает контейнер живым.
nginx -c "${CONF_OUT}" -t

exec nginx -c "${CONF_OUT}" -g 'daemon off;'
