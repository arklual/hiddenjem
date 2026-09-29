# syntax=docker/dockerfile:1.7
# =============================================================================
#  Horizon :: nlp-service — локальный вывод моделей. Порт 8086
# =============================================================================
#  Контекст сборки — `services/nlp-service/`.
#      docker build -f services/nlp-service/Dockerfile -t horizon/nlp-service services/nlp-service
#
#  Почему отдельный образ, а не пакет в analytics-service: torch тянет полтора гигабайта, а
#  аналитический движок обязан оставаться лёгким и детерминированным. Граница — HTTP (ADR-0017).
#
#  Веса моделей в образ НЕ кладутся: они большие и обновляются отдельно от кода. Каталог кэша
#  Hugging Face монтируется томом, и первый старт скачивает две модели перевода (~600 МБ).
#  Соответствие NFR-S9: non-root, drop ALL caps.
# =============================================================================

ARG PYTHON_IMAGE=python:3.12-slim

FROM ${PYTHON_IMAGE} AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1
WORKDIR /build
COPY pyproject.toml ./
COPY src ./src
# CPU-сборка torch: образ меньше на несколько гигабайт, а GPU на стенде нет и не предполагается
# (ТЗ §3.1: «серверные мощности для вычислений и обучения моделей не предоставляются»).
RUN python -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
 && /opt/venv/bin/pip install --no-cache-dir .

FROM ${PYTHON_IMAGE} AS runtime
ENV PATH="/opt/venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HF_HOME=/models \
    TRANSFORMERS_OFFLINE=0 \
    OMP_NUM_THREADS=4
RUN groupadd -g 10001 horizon && useradd -u 10001 -g horizon -m -s /usr/sbin/nologin horizon \
 && mkdir -p /models && chown horizon:horizon /models
COPY --from=build /opt/venv /opt/venv
USER 10001:10001
EXPOSE 8086
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8086/health/live',timeout=4).status==200 else 1)"
ENTRYPOINT ["python", "-m", "horizon_nlp"]
