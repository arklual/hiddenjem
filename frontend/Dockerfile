# syntax=docker/dockerfile:1.7
# =============================================================================
#  Horizon :: frontend — React 18 + TypeScript + Vite, отдаётся nginx
# =============================================================================
#  Контекст сборки — `frontend/`.
#      docker build -f frontend/Dockerfile -t horizon/frontend frontend
#
#  Порты: контейнер слушает 8080, наружу публикуется 3000.
#  ─────────────────────────────────────────────────────────────────────────
#  ПОЧЕМУ НЕ 80: непривилегированный процесс не может занять порт < 1024 без
#  CAP_NET_BIND_SERVICE, а NFR-S9 требует drop ALL capabilities. Порт внутри
#  контейнера — деталь реализации; наружу developer-facing порт остался 3000.
#  ─────────────────────────────────────────────────────────────────────────
#  Соответствие NFR-S9: non-root (uid 10001), read-only rootfs, drop ALL caps.
# =============================================================================

ARG NODE_IMAGE=node:22-alpine
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.27-alpine

# ─────────────────────────── Stage 1: сборка бандла ───────────────────────────
FROM ${NODE_IMAGE} AS build

WORKDIR /app

ENV CI=true \
    NODE_ENV=production \
    npm_config_audit=false \
    npm_config_fund=false

# Отдельный слой зависимостей: package-lock.json меняется куда реже исходников.
COPY package.json package-lock.json ./
# `npm ci` (а не `npm install`) — ставит РОВНО то, что в lock-файле, и падает
# при рассинхронизации. Это условие воспроизводимости сборки.
RUN --mount=type=cache,target=/root/.npm,sharing=locked \
    npm ci --include=dev

COPY . .

# Версия и хеш коммита попадают в бандл: без них нельзя сопоставить
# ошибку в браузере с конкретной сборкой.
ARG VERSION=dev
ARG REVISION=unknown
ENV VITE_APP_VERSION=${VERSION} \
    VITE_APP_REVISION=${REVISION}

RUN npm run build

# ─────────────────────────── Stage 2: runtime (nginx) ───────────────────────────
FROM ${NGINX_IMAGE} AS runtime

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="horizon-frontend" \
      org.opencontainers.image.description="Horizon web UI (React 18 + Vite), served by nginx" \
      org.opencontainers.image.source="https://github.com/horizon/horizon" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Proprietary"

USER root

# Тот же uid, что и у остальных сервисов Horizon: единая политика в Helm
# (runAsUser: 10001) не должна знать про исключения.
RUN addgroup -S -g 10001 horizon \
 && adduser  -S -u 10001 -G horizon -H -s /sbin/nologin horizon \
 && rm -rf /usr/share/nginx/html/* /etc/nginx/conf.d/default.conf \
 && mkdir -p /usr/share/nginx/html \
 && chown -R horizon:horizon /usr/share/nginx/html

# Конфигурация — шаблон: адрес шлюза и CSP задаются переменными окружения
# (12-factor, NFR-C2). Подстановку делает entrypoint при старте.
COPY --chown=root:root nginx.conf            /etc/nginx/nginx.conf.template
COPY --chown=root:root security-headers.conf /etc/nginx/security-headers.conf
COPY --chown=root:root docker-entrypoint.sh  /usr/local/bin/docker-entrypoint.sh
RUN chmod 0444 /etc/nginx/nginx.conf.template /etc/nginx/security-headers.conf \
 && chmod 0555 /usr/local/bin/docker-entrypoint.sh

# Source maps are built as `hidden` and are not part of the served bundle: shipping them would
# publish the original TypeScript, comments included, to any anonymous visitor.
COPY --from=build --chown=horizon:horizon /app/dist/ /usr/share/nginx/html/
RUN find /usr/share/nginx/html -name '*.map' -delete

USER 10001:10001

ENV NGINX_PORT=8080 \
    NGINX_RESOLVER=127.0.0.11 \
    HORIZON_GATEWAY_URL=http://gateway:8080 \
    HORIZON_API_TIMEOUT=60s \
    HORIZON_SSE_TIMEOUT=300s \
    HORIZON_CSP_CONNECT_SRC="'self'" \
    HORIZON_HSTS_DIRECTIVE=""

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["nginx"]
