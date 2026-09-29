# Hiddenjem

Сервис автоматизированного поиска зарождающихся научно-технологических трендов — слабых сигналов.

Аналитик задаёт направление в свободной форме. Система сама собирает свежие данные из открытых
источников на русском, английском и китайском языках, выделяет темы, отсекает мейнстрим, хайп и
шум и выдаёт ТОП-N слабых сигналов. У каждого сигнала есть описание, пример организации,
источники с датами и уровнем доверенности и объяснение, почему это слабый сигнал.

- **Демо-стенд:** http://2.27.20.14:18081
- **Техническая документация:** [docs/technical.md](docs/technical.md) — пайплайн, методология,
  признаки
- **Метрики:** [docs/metrics.md](docs/metrics.md) — Precision 0,892, Recall 0,740, F1 0,809 на
  датасете кейса

## Как это работает

| Шаг | Что происходит |
|---|---|
| 1. Запрос | Аналитик вводит направление, режим (быстрый или качественный), число тем и окно лет |
| 2. Сбор | Параллельно опрашиваются 25 источников, агент-исследователь сам читает страницы; корпус до 5 000 документов |
| 3. Темы | Из текстов выделяются технологические термины и группируются в темы; добавляются технологии, найденные агентом и в веб-корпусе |
| 4. Оценка | Балл 0–100: насколько тема уже известна миру (модель внешних признаков) и как развивается в корпусе (методология зарождения) |
| 5. Отбор | Мейнстрим, зрелое и неподтверждённое отсекается; верхушку списка проверяют три LLM-эксперта |
| 6. Отчёт | ТОП-N тем с источниками, разложением балла, объяснением и списком отсеянного с причинами |

## Архитектура

![Архитектура](docs/architecture.png)

Бэкенд — пять сервисов. Шаги анализа идут через Kafka по паттерну Saga, работа с языковой моделью
вынесена в отдельный сервис. Подробно — в [docs/technical.md](docs/technical.md), исходник схемы —
[docs/architecture.puml](docs/architecture.puml).

| Сервис | Каталог | Технологии |
|---|---|---|
| Веб-интерфейс | `frontend/` | React, TypeScript, nginx |
| API-шлюз | `services/gateway/` | Java 21, Spring Cloud Gateway |
| Оркестратор и отчёты | `services/trends-service/` | Java 21, Spring Boot |
| Сбор данных | `services/ingestion-service/` | Java 21, Spring Boot, Resilience4j |
| Анализ | `services/analytics-service/` | Python 3.12, FastAPI, numpy, scikit-learn |
| Языковые модели | `services/nlp-service/` | Python 3.12, FastAPI, LangGraph |
| Инфраструктура | `deploy/compose/` | Kafka (Redpanda), PostgreSQL, Redis, ClickHouse, OpenTelemetry, Prometheus, Grafana |

## Развёртывание

### Требования

- Linux или macOS, Docker 24+ с Docker Compose v2;
- 8 CPU, 16 ГБ ОЗУ (рекомендуется 24 ГБ), 20 ГБ свободного диска;
- доступ в интернет — к открытым источникам и API языковой модели;
- ключ OpenAI-совместимого API с моделью `gpt-5.6-luna` (или другой моделью из перечня ТЗ).

### Запуск

```bash
git clone https://github.com/arklual/hiddenjem.git
cd hiddenjem

# 1. Конфигурация: рабочие значения стенда уже в шаблоне
cp .env.example .env

# 2. Ключ языковой модели (обязательно)
#    HORIZON_NLP_OPENAI_API_KEY=<ключ>
#    HORIZON_NLP_OPENAI_BASE_URL и HORIZON_NLP_JUDGE_MODEL — если модель другая
nano .env

# 3. Каталоги данных на хосте: кэш признаков, модели перевода, веб-корпус.
#    Сервисы работают от пользователя 10001 и должны иметь право записи.
sudo mkdir -p /opt/horizon-signals/cache /opt/horizon-models/hf /opt/horizon-webcorpus
sudo chown -R 10001:10001 /opt/horizon-signals /opt/horizon-models/hf

#    Веб-корпус (архив 36 МБ, см. раздел «Веб-корпус»)
curl -fL -o /tmp/webcorpus.tar.gz \
  https://github.com/arklual/hiddenjem/releases/download/webcorpus-2026-09-29/hiddenjem-webcorpus-2026-09-29.tar.gz
sudo tar -xzf /tmp/webcorpus.tar.gz -C /opt/horizon-webcorpus
(cd /opt/horizon-webcorpus && sha256sum -c SHA256SUMS)   # macOS: shasum -a 256 -c SHA256SUMS

# 4. Сборка и запуск всех сервисов
docker compose -f deploy/compose/docker-compose.yml --env-file .env up -d --build

# 5. Дождаться готовности (2–5 минут после сборки)
docker compose -f deploy/compose/docker-compose.yml --env-file .env ps
curl -fsS http://localhost:8080/actuator/health/readiness
```

Шаги 4–5 одной командой: `make up` — соберёт образы, поднимет стек и дождётся готовности.

Первая сборка образов занимает 10–20 минут: Maven и npm скачивают зависимости.

После запуска:

| Что | Адрес |
|---|---|
| Веб-интерфейс | http://localhost:3000 |
| API (через шлюз) | http://localhost:8080 |
| Grafana | http://localhost:3001 |

Проверка, что всё работает:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/                            # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/api/v1/research-requests/quota  # 200
make smoke                                                                                   # здоровье сервисов
```

На macOS каталог `/opt` по умолчанию не доступен Docker Desktop. Либо добавьте его в
Settings → Resources → File sharing, либо укажите каталоги в домашней папке через
`HORIZON_SIGNALS_PATH`, `HORIZON_MODELS_PATH` и `HORIZON_WEBCORPUS_DIR` в `.env`.

Если порт занят, поменяйте `FRONTEND_PORT`, `GATEWAY_PORT` и другие `*_PORT` в `.env`. Если
интерфейс открывают не с `localhost`, добавьте его адрес в `CORS_ALLOWED_ORIGINS`.

Остановить: `docker compose -f deploy/compose/docker-compose.yml --env-file .env down`
(данные сохраняются). Удалить вместе с данными: `make reset`.

### Необязательные ключи

Без них система работает; с ними — полнее.

| Переменная | Что даёт |
|---|---|
| `HORIZON_OPENALEX_API_KEY` | Больше суточного бюджета OpenAlex |
| `HORIZON_GITHUB_TOKEN` | Поиск GitHub без блокировки по адресу |
| `HORIZON_SEMANTIC_SCHOLAR_API_KEY` | Отдельный пул Semantic Scholar вместо общего |
| `HORIZON_ALPHAXIV_API_KEY` + `HORIZON_SOURCE_ALPHAXIV_ENABLED=true` | Статьи alphaXiv |
| `HORIZON_LENS_API_TOKEN` (и `HORIZON_LENS_PATENT_API_TOKEN`, если ключ на патенты отдельный) | The Lens: научные работы и патенты всех ведомств |
| `HORIZON_CONNECTOR_CONTACT_EMAIL` | Ваш контакт в User-Agent: OpenAlex и Crossref пускают таких в «вежливый пул» |

После изменения `.env` перезапустите сервисы:
`docker compose -f deploy/compose/docker-compose.yml --env-file .env up -d`.

### Веб-корпус

Заранее собранный корпус: 2 248 сигналов по 39 направлениям и 12 969 страниц-доказательств, собранных
с соблюдением robots.txt. Сигналы размечены через LLM по критериям жюри, 1 322 из них одобрены. Корпус
лежит в релизе репозитория, а не в git: распакованный он весит около 110 МБ.

- **Скачать:** [hiddenjem-webcorpus-2026-09-29.tar.gz](https://github.com/arklual/hiddenjem/releases/download/webcorpus-2026-09-29/hiddenjem-webcorpus-2026-09-29.tar.gz)
- **Страница релиза:** https://github.com/arklual/hiddenjem/releases/tag/webcorpus-2026-09-29

| Файл | Содержимое |
|---|---|
| `signals.jsonl` | Сигналы: название, описание, компании, ссылки на страницы-доказательства, вердикт и причина разметки |
| `docs.jsonl` | Страницы-доказательства: адрес, заголовок, дата, язык, текст |
| `SHA256SUMS` | Контрольные суммы |

Корпус распаковывается в `/opt/horizon-webcorpus` (шаг 3 запуска) или в свой каталог, указанный в
`HORIZON_WEBCORPUS_DIR`. Сервис перечитывает файлы при изменении, перезапуск не нужен.

Без корпуса поставьте `HORIZON_SOURCE_WEBCORPUS_ENABLED=false`: система будет работать только по живым
источникам и агенту-исследователю. Иначе каждый отчёт будет помечен неполным.

## Использование

1. Откройте http://localhost:3000, нажмите «Новый анализ».
2. Введите направление, например «квантовые технологии» или «финтех», и выберите режим:
   быстрый (7–15 минут) или качественный.
3. Прогресс виден в реальном времени. Готовый отчёт содержит:
   - ТОП тем с баллом и уверенностью;
   - карточку каждой темы: определение, пример, все источники, блок «Почему это слабый сигнал»;
   - вкладку «Что не попало»: сколько кандидатов рассмотрено и почему отсеяны;
   - экспорт запиской (Markdown), в JSON и CSV.

Через API:

```bash
# запустить анализ
curl -s -X POST http://localhost:8080/api/v1/research-requests \
  -H 'Content-Type: application/json' \
  -d '{"query": "квантовые технологии", "parameters": {"mode": "fast", "topN": 15, "yearsWindow": 7}}'

# статус (reportId появится после завершения)
curl -s http://localhost:8080/api/v1/research-requests/<requestId>

# отчёт
curl -s http://localhost:8080/api/v1/reports/<reportId>
```

Полное описание API — `contracts/openapi/horizon-api.yaml`.

## Разработка и тесты

| Команда | Что делает |
|---|---|
| `make test` | Быстрые тесты: JVM (unit, slice, contract), Python, фронтенд |
| `make test-integration` | Интеграционные тесты JVM на Testcontainers (нужен Docker) |
| `make validate-dataset DATASET=<xlsx>` | Precision/Recall/F1 на датасете кейса ([docs/metrics.md](docs/metrics.md)) |
| `python -m horizon_analytics.signals.train` | Обучение модели внешних признаков ([docs/technical.md](docs/technical.md), «Обучение модели») |
| `make lint` | Линтеры всех языков |
| `make help` | Все цели |

### Зависимости

| Сервис | Файл |
|---|---|
| Java-сервисы | `services/pom.xml`, `services/*/pom.xml` (Maven, JDK 21) |
| analytics-service | `services/analytics-service/pyproject.toml` |
| nlp-service | `services/nlp-service/pyproject.toml` |
| Веб-интерфейс | `frontend/package.json`, `frontend/package-lock.json` |

## Структура репозитория

```
contracts/     JSON Schema событий саги и OpenAPI
deploy/        docker compose (полный и облегчённый стек), Helm-чарт
docs/          техническая документация, метрики, схема архитектуры
fixtures/      данные для замеров и тестов: отрицательный контроль, кэш ответов источников
frontend/      веб-интерфейс
services/      gateway, trends-service, ingestion-service, analytics-service, nlp-service, platform (общий код Java)
tests/         сквозные и нагрузочные тесты
tools/         служебные проверки
```
