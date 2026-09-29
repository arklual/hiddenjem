# Hiddenjem — точка входа для разработчика.
#
#   make            список целей
#   make up         полный стек
#   make up-min     стек для машины с 2 CPU / 3 ГБ RAM
#   make verify     всё, что проверяет CI, без Docker
#
# Каждая цель работает из чистого клона на Linux и macOS.

SHELL          := $(shell command -v bash)
.SHELLFLAGS    := -eu -o pipefail -c
.DEFAULT_GOAL  := help
.ONESHELL:

ROOT           := $(shell pwd)
COMPOSE_DIR    := deploy/compose
COMPOSE        := docker compose -f $(COMPOSE_DIR)/docker-compose.yml --env-file .env
COMPOSE_MIN    := docker compose -f $(COMPOSE_DIR)/docker-compose.min.yml --env-file .env
MVN            := ./services/mvnw -f services/pom.xml
NPM            := npm --prefix frontend
E2E            := npm --prefix tests/e2e
PY             := services/analytics-service/.venv/bin/python
# The analytics package is installed into the service's venv, so its CLIs must run with the service
# as the working directory. Invoking them from the repository root fails with ModuleNotFoundError.
PY_SVC         := env -C services/analytics-service $(CURDIR)/services/analytics-service/.venv/bin/python
# Таблица приёмки пишется машиной, а не руками: набранная руками расходится с замером молча —
# ровно так методология полгода утверждала, что заложенные темы не восстанавливаются.
ACCEPT_REPORT  := build/reports/acceptance-current.md
STABILITY_REPORT := build/reports/stability-current.md
LOSSES_REPORT := build/reports/boundary-losses-current.md
API_URL        ?= http://localhost:8080
MODELS_PATH    ?= /opt/horizon-models
LLM_MODEL_NAME ?= horizon-qwen3.5-4b
LLM_MODEL_FILE ?= Qwen3.5-4B-Q4_K_M.gguf
LLM_MODEL_URL  ?= https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf
NLP_URL        ?= http://localhost:8086
CONTACT_EMAIL  ?= ops@horizon.example
WEB_URL        ?= http://localhost:3000
DATASET        ?= 100_слабых_технологических_сигналов_сентябрь_2026.xlsx

.PHONY: help
help: ## Показать список целей
	@echo ""
	@echo "  Hiddenjem — обнаружение зарождающихся технологических трендов"
	@echo ""
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ───────────────────────────── окружение ─────────────────────────────

.env: .env.example ## Создать .env из шаблона
	@if [ ! -f .env ]; then cp .env.example .env; echo "Создан .env из .env.example"; fi

.PHONY: up
up: .env ## Поднять полный стек (инфраструктура + сервисы + наблюдаемость)
	$(COMPOSE) up -d --build
	@$(MAKE) --no-print-directory wait-healthy
	@echo ""
	@echo "  Интерфейс   $(WEB_URL)"
	@echo "  API         $(API_URL)"
	@echo "  Grafana     http://localhost:3001"
	@echo ""

.PHONY: up-min
up-min: .env ## Поднять облегчённый стек (без ClickHouse и наблюдаемости)
	$(COMPOSE_MIN) up -d --build
	@$(MAKE) --no-print-directory wait-healthy COMPOSE="$(COMPOSE_MIN)"

.PHONY: down
down: ## Остановить стек (данные сохраняются)
	-$(COMPOSE) down --remove-orphans

.PHONY: reset
reset: ## Остановить стек и УДАЛИТЬ все данные
	@read -p "Удалить все тома и данные? [y/N] " ok; [ "$$ok" = "y" ] || exit 1
	$(COMPOSE) down -v --remove-orphans
	rm -rf services/*/target frontend/dist tests/e2e/results

.PHONY: logs
logs: ## Логи всех сервисов (make logs S=trends-service — одного)
	$(COMPOSE) logs -f --tail=200 $(S)

.PHONY: ps
ps: ## Состояние контейнеров
	$(COMPOSE) ps

.PHONY: wait-healthy
wait-healthy: ## Дождаться готовности сервисов
	@echo "Ожидание готовности сервисов…"
	@for i in $$(seq 1 120); do
		if curl -fsS $(API_URL)/actuator/health/readiness >/dev/null 2>&1; then
			echo "Готово."; exit 0
		fi
		sleep 2
	done
	echo "Сервисы не поднялись за 4 минуты. Смотрите: make logs"; exit 1

.PHONY: smoke
smoke: ## Быстрая проверка живости всех сервисов
	@set +e
	@for name in gateway:8080 trends-service:8082 ingestion-service:8083; do
		port=$${name##*:}; svc=$${name%%:*}
		code=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$$port/actuator/health || echo 000)
		printf '  %-20s %s\n' "$$svc" "$$code"
	done
	@code=$$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/health/ready || echo 000)
	@printf '  %-20s %s\n' "analytics-service" "$$code"

.PHONY: models
models: ## Скачать веса локальных моделей (ADR-0017); нужна сеть, ~8 ГБ
	# Веса не лежат в репозитории и не кладутся в образ: они большие и живут отдельным циклом от
	# кода. Каталог монтируется томом в `llm` и `nlp-service`.
	#
	# Модель перевода (~600 МБ) скачивается сама при первом старте nlp-service — она маленькая и
	# тянется из кода. Здесь только генеративная: её надо и скачать, и зарегистрировать в ollama.
	@mkdir -p $(MODELS_PATH)
	@if [ ! -f $(MODELS_PATH)/$(LLM_MODEL_FILE) ]; then \
		echo "  скачивается $(LLM_MODEL_FILE) (~2.7 ГБ)"; \
		curl -fsSL -o $(MODELS_PATH)/$(LLM_MODEL_FILE) $(LLM_MODEL_URL); \
	else echo "  $(LLM_MODEL_FILE) уже на месте"; fi
	@printf 'FROM /models/$(LLM_MODEL_FILE)\nPARAMETER temperature 0\nPARAMETER top_p 1\nPARAMETER seed 20260917\nPARAMETER num_ctx 8192\nPARAMETER num_thread 4\n' > $(MODELS_PATH)/Modelfile
	@docker exec horizon-llm ollama create $(LLM_MODEL_NAME) -f /models/Modelfile \
		|| echo "  контейнер horizon-llm не запущен: поднимите стек и повторите"
	@echo "  модель $(LLM_MODEL_NAME) готова"

.PHONY: validate-dataset
validate-dataset: ## Precision/Recall/F1 на датасете кейса (DATASET=путь к xlsx; ответы источников — из кэша)
	# Признаки считаются по живым открытым источникам, а не по полям таблицы: из датасета читаются
	# только название и область. Формулировки поиска и ответы источников сохраняются в
	# `fixtures/validation/`, поэтому повторный прогон сравним с предыдущим, а их удаление —
	# способ перемерить всё заново.
	@mkdir -p $(CURDIR)/build/reports
	$(PY_SVC) -m horizon_analytics.validation.run \
		--dataset $(abspath $(DATASET)) \
		--nlp-url $(NLP_URL) --email $(CONTACT_EMAIL) \
		--markdown $(CURDIR)/build/reports/dataset-validation-current.md

# ───────────────────────────── сборка ─────────────────────────────

.PHONY: build
build: build-java build-web ## Собрать всё

.PHONY: build-java
build-java: ## Собрать JVM-сервисы
	$(MVN) -B package -DskipTests

.PHONY: build-web
build-web: ## Собрать фронтенд
	$(NPM) ci
	$(NPM) run build

# ───────────────────────────── тесты ─────────────────────────────

.PHONY: test
test: test-unit test-python test-web ## Быстрые тесты (unit + slice + contract)

.PHONY: test-unit
test-unit: ## JVM: unit + slice + contract (без Docker)
	$(MVN) -B verify -DskipITs

.PHONY: test-integration
test-integration: ## JVM: интеграционные тесты на Testcontainers (нужен Docker)
	$(MVN) -B verify -Pintegration

.PHONY: venv
venv: ## Подготовить окружение analytics-service
	@if [ ! -x $(PY) ]; then python3 -m venv services/analytics-service/.venv; fi
	@# Guard on the package importing, not on the interpreter existing: a venv can be present while
	@# the editable install is missing, and then every CLI fails with ModuleNotFoundError while
	@# pytest still passes (it injects `src` via its own `pythonpath` setting).
	@$(PY) -c 'import horizon_analytics' 2>/dev/null || \
		$(PY) -m pip install -q --no-cache-dir -e 'services/analytics-service[dev]'

.PHONY: test-python
test-python: venv ## analytics-service: pytest
	$(PY) -m pytest services/analytics-service/tests -q -m "not integration"

.PHONY: test-web
test-web: ## Фронтенд: vitest
	$(NPM) run test -- --run

.PHONY: test-e2e
test-e2e: ## E2E против реального стека (нужен поднятый стек)
	$(E2E) ci
	$(E2E) run install:browsers
	HORIZON_API_URL=$(API_URL) HORIZON_WEB_URL=$(WEB_URL) $(E2E) test

.PHONY: mutation
mutation: ## Mutation testing (долго)
	$(MVN) -B test-compile org.pitest:pitest-maven:mutationCoverage -Pmutation

.PHONY: load
load: ## Нагрузочный тест k6 (нужен поднятый стек)
	k6 run tests/load/read-path.k6.js

.PHONY: verify
verify: ## Все проверки CI, не требующие Docker
	./tools/verify.sh

.PHONY: explain
explain: venv ## Почему темы нет в отчёте: трассировка терминов сквозь конвейер
	$(PY_SVC) -m horizon_analytics.tools.explain $(if $(TERM_),--term "$(TERM_)",--expected --domain $(or $(DOMAIN),ai))

.PHONY: losses
losses: venv ## Потери правила границы на списке настоящих имён: таблица для §30
	# Что её обновляет: правка самого правила границы или списка имён. Корпус — не обновляет:
	# замер идёт по фиксированному списку настоящих названий, а не по извлечённым кандидатам.
	# Записано потому, что вопрос «надо ли перегенерировать после правки извлечения» возникает
	# каждый раз, а ответ до сих пор добывался прогоном.
	# Числа потерь набирались руками и дважды разошлись с кодом: «20 из 58» осталось в README, в
	# сценарии демонстрации и в записке после того, как правило изменилось. Ошибка тихая — документ
	# выглядит одинаково убедительно и когда верен, и когда устарел.
	@mkdir -p $(CURDIR)/build/reports
	@$(PY_SVC) -m horizon_analytics.tools.boundary_losses --markdown > $(LOSSES_REPORT)
	@echo "  → $(LOSSES_REPORT)"

.PHONY: stability
stability: venv ## Устойчивость состава к весам индикаторов: таблица для §16 методологии
	# Обновляется тем же, чем приёмка: это тот же прогон конвейера по эталонному корпусу, только
	# с перебором весов.
	# Числа §16 набирались руками и дважды расходились с прогоном — «первая шестёрка не опускается
	# ниже восьмого места» оказалось неверным. Ошибка тихая: таблица выглядит одинаково убедительно
	# и когда верна, и когда устарела. Поэтому её печатает машина, а документ на неё ссылается.
	@mkdir -p $(CURDIR)/build/reports
	@$(PY_SVC) -m horizon_analytics.tools.stability --markdown > $(STABILITY_REPORT)
	@echo "  → $(STABILITY_REPORT)"

.PHONY: accept
accept: venv ## Приёмка качества выдачи на эталонном корпусе: все домены на обоих языках
	# Что её обновляет: любая правка конвейера — извлечение, фильтры имён, словарь направлений,
	# веса методологии — и правка самого корпуса. Прогон идёт по нему целиком, поэтому «не должно
	# было задеть» здесь не довод: проверять надо запуском.
	@fail=0
	@mkdir -p $(CURDIR)/build/reports
	@printf '<!-- Сгенерировано `make accept`. Руками не править: правка разойдётся с замером -->\n' > $(ACCEPT_REPORT)
	@printf 'Замер: %s, эталонный корпус `fixtures/corpus/documents.jsonl`.\n\n' "$$(date +%Y-%m-%d)" >> $(ACCEPT_REPORT)
	@printf '| Язык | Домен | Ожидаемых тем | Дошло до ТОП-15 |\n| --- | --- | --- | --- |\n' >> $(ACCEPT_REPORT)
	@for lang in en ru; do
		for domain in ai security quantum bio fintech energy; do
			printf '  %-3s %-10s ' "$$lang" "$$domain"
			# Вывод берётся независимо от кода возврата: домен, где приёмка не прошла, обязан
			# попасть в таблицу. Первая версия писала строку только при успехе, и таблица молча
			# теряла ровно те домены, ради которых её читают.
			out=$$($(PY_SVC) -m horizon_analytics.tools.explain --expected --domain $$domain --lang $$lang 2>&1 || true)
			line=$$(echo "$$out" | grep 'дошло до отчёта' || true)
			if [ -z "$$line" ]; then
				echo 'ошибка'
				fail=1
				printf '| %s | %s | — | **замер не выполнен** |\n' "$$lang" "$$domain" >> $(ACCEPT_REPORT)
				continue
			fi
			echo "$$line"
			got=$${line##*: }
			printf '| %s | %s | %s | **%s** |\n' "$$lang" "$$domain" "$${got#*/}" "$${got%%/*}" >> $(ACCEPT_REPORT)
			[ "$${got%%/*}" = "$${got#*/}" ] || fail=1
		done
	done
	@echo "  таблица приёмки: $(ACCEPT_REPORT)"
	# Ненулевой выход сегодня — ожидаемое состояние, а не поломка: одна посаженная тема из десяти,
	# `software bill of materials`, доходит до ранжирования и встаёт 19-й при top_n = 15. Это
	# вопрос весов, и трогать их ради одной темы — подгонка под тест (пункт 6 бэклога). Поэтому
	# цель не входит в `make verify`: гейт обязан быть зелёным, а известное расхождение — видимым.
	@exit $$fail

.PHONY: reproduce
reproduce: ## Проверить воспроизводимость методологии на golden-корпусе
	$(PY) -m pytest services/analytics-service/tests/golden -q

.PHONY: backtest
backtest: venv ## Бэктест методологии на историческом срезе
	# Обновляется тем же, чем приёмка, и пишет таблицу для документа. JSON в trends-service больше
	# не отгружается: панель проверки жила на странице методологии, а страница выведена из продукта.
	@mkdir -p $(CURDIR)/build/reports
	$(PY_SVC) -m horizon_analytics.tools.backtest --snapshot-year 2021 --also-years 2019,2020,2022 \
		--markdown $(CURDIR)/build/reports/backtest-current.md

.PHONY: real-corpus
real-corpus: ## Выгрузить настоящие работы из OpenAlex для замеров (нужна сеть; DIR=каталог)
	# Не эталонный корпус и не его замена: у эталонного есть манифест и хэш, он отвечает на
	# «воспроизводимо ли», а эта выгрузка — на «работает ли на данных, которых никто не подбирал».
	# Повторяется приблизительно: OpenAlex живая база.
	python3 tools/fetch-real-corpus.py $(or $(DIR),real-corpus)

.PHONY: lexicon-targets
lexicon-targets: venv ## Проверить, что цели перекрёстного словаря существуют у источника (нужна сеть)
	$(PY_SVC) tools/check-lexicon-targets.py

# ───────────────────────────── качество кода ─────────────────────────────

.PHONY: lint
lint: ## Линтеры для всех языков
	$(MVN) -B spotless:check
	$(NPM) run lint
	$(PY) -m ruff check services/analytics-service/src services/analytics-service/tests
	$(PY) -m black --check services/analytics-service/src services/analytics-service/tests
	$(PY) -m mypy --strict services/analytics-service/src

.PHONY: fmt
fmt: ## Автоформатирование
	$(MVN) -B spotless:apply
	$(NPM) run format
	$(PY) -m black services/analytics-service/src services/analytics-service/tests
	$(PY) -m ruff check --fix services/analytics-service/src services/analytics-service/tests

.PHONY: contracts
contracts: ## Проверить контракты и golden-корпус
	./tools/verify.sh contracts

.PHONY: clean
clean: ## Удалить артефакты сборки
	-$(MVN) -B clean
	rm -rf frontend/dist tests/e2e/results tests/e2e/playwright-report
