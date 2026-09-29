#!/usr/bin/env bash
#
# Полная локальная проверка репозитория — то же, что делает CI, но одной командой.
#
# Не требует Docker: выполняются только те проверки, которым он не нужен.
# Тесты уровня integration/e2e запускаются отдельно (`make test-integration`, `make test-e2e`).
#
#   ./tools/verify.sh            # всё
#   ./tools/verify.sh java       # только JVM-часть
#   ./tools/verify.sh python     # только analytics-service
#   ./tools/verify.sh web        # только фронтенд
#   ./tools/verify.sh contracts  # контракты и golden-корпус
#   ./tools/verify.sh --strict   # пропуск проверки считается провалом (так зовут из CI)
#
# Итог всегда называет и пройденное, и пропущенное. Пропуск — не примечание: проверка, которой не
# нашлось инструмента, ничего не проверила, и умолчать об этом значит выдать за гарантию то, что
# ею не является.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=/dev/null
[[ -f tools/env.sh ]] && source tools/env.sh

TARGET="all"
STRICT="${HORIZON_VERIFY_STRICT:-0}"
for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=1 ;;
    *) TARGET="$arg" ;;
  esac
done

FAILURES=()
SKIPPED=()
PASSED=0
STARTED_AT=$SECONDS

c_red=$'\033[31m'; c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_bold=$'\033[1m'; c_off=$'\033[0m'

step() { printf '\n%s▸ %s%s\n' "$c_bold" "$1" "$c_off"; }
ok()   { printf '%s  ✓ %s%s\n' "$c_green" "$1" "$c_off"; PASSED=$((PASSED + 1)); }
bad()  { printf '%s  ✗ %s%s\n' "$c_red" "$1" "$c_off"; FAILURES+=("$1"); }
# Пропуск учитывается наравне с провалом. До этого он нигде не накапливался, и итог печатал
# «Все проверки пройдены» независимо от того, сколько их выполнилось: на машине без JDK, npm и
# PyYAML гейт сообщал об успехе, не проверив ничего, и возвращал ноль. Проверка, умеющая молча
# выключиться, хуже отсутствия проверки — она производит уверенность вместо сведений.
skip() { printf '%s  ‒ %s (пропущено)%s\n' "$c_yellow" "$1" "$c_off"; SKIPPED+=("$1"); }

run() { # run <название> <команда...>
  local name="$1"; shift
  if "$@" >/tmp/horizon-verify.log 2>&1; then
    ok "$name"
  else
    bad "$name"
    tail -30 /tmp/horizon-verify.log | sed 's/^/      /'
  fi
}

# То же самое, но помечает проверку как «проверку сборки» — ту, что ловит сломанный артефакт,
# остающийся синтаксически верным (миграция, контракт, сгенерированный клиент, ссылка в документе).
# Пометка нужна не гейту, а README: он перечисляет эти проверки, и раньше называл их количество
# руками — «девять», когда их было двенадцать. Список из README сверяется с этими вызовами
# проверкой `проверки сборки перечислены в README`, поэтому категория обязана быть машинно видимой.
run_structural() { run "$@"; }

# ─────────────────────────────── контракты ───────────────────────────────
verify_contracts() {
  step "Контракты и golden-корпус"

  run_structural "JSON Schema разбираются и объявляют draft 2020-12" python3 - <<'PY'
import json, pathlib, sys
bad = []
for p in sorted(pathlib.Path("contracts/schemas").glob("*.json")):
    d = json.loads(p.read_text(encoding="utf-8"))
    if not str(d.get("$schema", "")).startswith("https://json-schema.org/draft/2020-12"):
        bad.append(p.name)
if bad:
    print("не объявляют draft 2020-12:", bad); sys.exit(1)
PY

  if python3 -c "import yaml" 2>/dev/null; then
    run_structural "OpenAPI и AsyncAPI разбираются" python3 - <<'PY'
import sys, yaml
api = yaml.safe_load(open("contracts/openapi/horizon-api.yaml", encoding="utf-8"))
events = yaml.safe_load(open("contracts/asyncapi/horizon-events.yaml", encoding="utf-8"))
assert api["openapi"].startswith("3.1"), api["openapi"]
assert events["asyncapi"].startswith("3."), events["asyncapi"]
assert api["paths"] and api["components"]["schemas"]
assert events["channels"] and events["components"]["messages"]
PY
  else
    skip "OpenAPI/AsyncAPI (нет PyYAML)"
  fi

  run_structural "golden-корпус соответствует манифесту" python3 - <<'PY'
import hashlib, json, pathlib, sys
manifest = json.loads(pathlib.Path("fixtures/corpus/manifest.json").read_text(encoding="utf-8"))
lines = pathlib.Path("fixtures/corpus/documents.jsonl").read_text(encoding="utf-8").splitlines()
if len(lines) != manifest["documentCount"]:
    print(f"документов {len(lines)}, в манифесте {manifest['documentCount']}"); sys.exit(1)
ids = sorted(json.loads(line)["documentId"] for line in lines)
digest = hashlib.sha256("".join(ids).encode("utf-8")).hexdigest()
if digest != manifest["contentHash"]:
    print(f"contentHash расходится:\n  файл:    {digest}\n  манифест:{manifest['contentHash']}")
    print("Корпус изменён без обновления версии — см. fixtures/corpus/README.md")
    sys.exit(1)
PY

  # Дефекты миграций не видит ни компилятор, ни тесты: файл синтаксически верен, тесты идут на
  # пустой схеме, а сервис не поднимается уже на стенде.
  # Сама проверка миграций разбирает SQL и потому может сломаться молча: на файлах без дефектов
  # сломанный разбор отвечает так же, как исправный.
  run_structural "разбор миграций не разъехался" python3 tools/test_check_migrations.py
  run_structural "миграции применимы" python3 tools/check-migrations.py
  # Схема ClickHouse раньше разворачивалась через `CREATE TABLE IF NOT EXISTS` и на существующем
  # кластере не менялась никогда. Прогонщик проверяется без кластера — заглушкой клиента.
  run_structural "прогонщик миграций ClickHouse" ./tools/test-clickhouse-migrations.sh
  # Проверка самого гейта: пропущенная проверка обязана попасть в итог. Рекурсии нет — тест
  # вызывает только цель `java`, а не `contracts`, из которой запускается сам.
  run_structural "гейт учитывает пропущенные проверки" ./tools/test-verify-summary.sh
  # Инструмент, которым проверяется несущесть тестов. На нём держатся утверждения «проверено
  # подменой» во всей истории изменений: если он сам врёт, врут и они.
  run_structural "проба ведёт себя так, как обещает" ./tools/test-probe.sh
  # Переменная окружения, выставленная деплоем и не читаемая никем, — тихий отказ: контейнер
  # стартует, health-check зелёный, настройка не действует. Так шлюз получал адрес upstream
  # под одним именем и читал другое, а `HORIZON_SOURCE_FIXTURE_ENABLED: "false"` в проде не выключал ничего.
  run_structural "переменные окружения деплоя кто-то читает" python3 tools/check-env-wiring.py
  # Тип колонки в миграции и тип поля сущности сверяет Hibernate при старте против настоящей базы
  # — по одному расхождению за запуск. Развёртывание из-за этого шло кругами: `numeric` против
  # `double`, потом `char` против `String`, и каждый круг стоил пересборки. Расхождение при этом
  # целиком лежит в репозитории, поэтому называется здесь и все сразу.
  run_structural "типы колонок совпадают с полями сущностей" python3 tools/check-schema-mapping.py
  # Топик, в который пишет outbox, обязан быть и в контракте, и в списке создаваемых. Одна из служб
  # публиковала шесть типов событий в канал, которого не знали ни AsyncAPI, ни redpanda-init.
  run_structural "топики согласованы с контрактом и деплоем" python3 tools/check-topics.py
  # Алерт на несуществующую метрику молчит всегда — и при аварии тоже: в PromQL нет данных, значит
  # нет и срабатывания. Одиннадцать доменных метрик были объявлены обязательством служб, а
  # испускались из них ноль.
  run_structural "метрики алертов кто-то испускает" python3 tools/check-metrics.py

  # Разбор YAML не проверяет ссылки: $ref на несуществующий компонент разбирается прекрасно и
  # ломается только при генерации клиента. Так одна опечатка прожила две итерации.
  run_structural "ссылки внутри OpenAPI разрешаются" python3 - <<'PY'
import sys, yaml
doc = yaml.safe_load(open("contracts/openapi/horizon-api.yaml", encoding="utf-8"))

def refs(node, path="#"):
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                yield value, path
            else:
                yield from refs(value, f"{path}/{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from refs(value, f"{path}/{index}")

def resolve(document, pointer):
    if not pointer.startswith("#/"):
        return True  # внешние файлы не наша забота
    node = document
    for part in pointer[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            return False
        node = node[part]
    return True

broken = [(ref, where) for ref, where in refs(doc) if not resolve(doc, ref)]
if broken:
    for ref, where in broken:
        print(f"  не разрешается: {ref}  (в {where})")
    sys.exit(1)
PY
}

# ─────────────────────────────── JVM ───────────────────────────────
verify_java() {
  step "JVM-сервисы"

  # Обёртка лежит в репозитории и закрепляет версию Maven — ради этого её и коммитят. Проверка
  # спрашивала только `command -v mvn` и на машине без глобального Maven молча пропускала всю
  # JVM-половину гейта: четыре службы, ArchUnit, срезы контроллеров. Пропуск при этом печатался
  # одной строкой среди девяти и читался как «здесь нечего проверять».
  local mvn_cmd=()
  if [[ -x services/mvnw ]]; then
    mvn_cmd=(services/mvnw)
  elif command -v mvn >/dev/null 2>&1; then
    mvn_cmd=(mvn)
  else
    skip "ни обёртки services/mvnw, ни Maven в PATH"; return
  fi

  # Обёртке нужен JDK. Без него она падает сообщением про JAVA_HOME, из которого не следует, что
  # делать; сказать это прямо дешевле, чем разбирать её вывод.
  # Проверяется наличие компилятора, а не непустота переменной: `tools/env.sh` задаёт JAVA_HOME
  # безусловно, и путь в ней может не существовать — как существовал MAVEN_HOME, указывавший на
  # неустановленный Maven. Непустая переменная о наличии JDK не говорит ничего.
  if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]] && ! command -v javac >/dev/null 2>&1; then
    skip "JDK не найден (задайте JAVA_HOME на существующий JDK)"; return
  fi

  run "сборка и быстрые тесты (unit + slice + contract)" \
      "${mvn_cmd[@]}" -B -f services/pom.xml verify -DskipITs
}

# ─────────────────────────────── Python ───────────────────────────────
verify_python() {
  step "analytics-service"
  local svc="services/analytics-service"
  if [[ ! -d "$svc" ]]; then skip "сервис отсутствует"; return; fi

  local py="$svc/.venv/bin/python"
  if [[ ! -x "$py" ]]; then
    printf '  создаю виртуальное окружение…\n'
    python3 -m venv "$svc/.venv" >/dev/null 2>&1
    "$svc/.venv/bin/pip" install --quiet --no-cache-dir -e "$svc[dev]" >/tmp/horizon-verify.log 2>&1 \
      || { bad "установка зависимостей Python"; return; }
  fi

  local py_abs="$ROOT/$svc/.venv/bin/python"
  run "ruff"   env -C "$svc" "$py_abs" -m ruff check src tests
  run "black"  env -C "$svc" "$py_abs" -m black --check src tests
  run "mypy"   env -C "$svc" "$py_abs" -m mypy --strict src
  # Границы слоёв питоновского движка. Объявлялись в четырёх документах наравне с ArchUnit и не
  # проверялись ни разу: домен здесь считает индикаторы и балл, и знающий про FastAPI или драйвер
  # базы, он перестаёт быть местом, которое можно посчитать без стенда.
  run "import-linter" env -C "$svc" "$py_abs" -m importlinter.cli --config pyproject.toml
  # PYTHONDONTWRITEBYTECODE: прогон обязан отражать исходник, а не его прошлую компиляцию.
  # Инвалидация кеша идёт по времени изменения с точностью до секунды, и правка, уложившаяся в ту
  # же секунду, что и предыдущий прогон, оставляет старый байткод действительным. Тест тогда молча
  # проверяет прежний код — ровно то, ради чего гейта не существует. Поймано на живом примере:
  # восстановленный после подмены файл продолжал давать результат подменённого.
  run "pytest" env -C "$svc" PYTHONDONTWRITEBYTECODE=1 "$py_abs" -m pytest tests -q -m "not integration"
}

# ─────────────────────────────── nlp-service ───────────────────────────────
# Сервис локального вывода моделей гейт не проверял вообще: каталог тестов был пуст, а стадии не
# существовало. Именно в нём живёт правило, от которого зависит читаемость русской выдачи, — и
# ошибку в нём не увидел бы никто, кроме глаза на демонстрации.
#
# Окружение гейта нарочно **без** torch и transformers: полтора гигабайта на каждую проверку
# означали бы, что проверку перестают запускать. Модельные библиотеки импортируются внутри
# функций, поэтому чистая логика — маскирование аббревиатур, разбор ответов, схемы границы —
# проверяется без весов.
verify_nlp() {
  step "nlp-service"
  local svc="services/nlp-service"
  if [[ ! -d "$svc" ]]; then skip "сервис отсутствует"; return; fi

  # Каталог именно `.venv`: проверки репозитория обходят его по имени, и окружение под другим
  # именем попадало в выборку «исходники проекта» — гейт начинал искать выключенные проверки
  # в чужих пакетах и находил их в pytest.
  local py="$svc/.venv/bin/python"
  if [[ ! -x "$py" ]]; then
    printf '  создаю виртуальное окружение…\n'
    python3 -m venv "$svc/.venv" >/dev/null 2>&1
    "$svc/.venv/bin/pip" install --quiet --no-cache-dir \
        fastapi pydantic pydantic-settings httpx structlog prometheus-client \
        pytest ruff mypy >/tmp/horizon-verify-nlp.log 2>&1 \
      || { bad "установка зависимостей nlp-service"; return; }
  fi

  local py_abs="$ROOT/$svc/.venv/bin/python"
  run "ruff"   env -C "$svc" "$py_abs" -m ruff check src tests
  run "mypy"   env -C "$svc" "$py_abs" -m mypy --strict src
  run "pytest" env -C "$svc" PYTHONDONTWRITEBYTECODE=1 "$py_abs" -m pytest tests -q
}

# ─────────────────────────────── фронтенд ───────────────────────────────
verify_web() {
  step "frontend"
  if [[ ! -f frontend/package.json ]]; then skip "фронтенд отсутствует"; return; fi
  if ! command -v npm >/dev/null 2>&1; then skip "npm не найден"; return; fi
  [[ -d frontend/node_modules ]] || run "npm ci" npm --prefix frontend ci

  run "eslint"    npm --prefix frontend run lint
  run "tsc"       npm --prefix frontend run typecheck
  run_structural "tsc охватывает файлы" ./tools/check-typecheck-is-real.sh
  # Клиент порождается из контракта и коммитится. Правка контракта без перегенерации оставляет
  # клиент описывающим прошлую версию API, и проверки соответствия схем типам это не ловят: обе
  # стороны устарели одинаково.
  run_structural "клиент соответствует контракту" ./tools/check-generated-client.sh
  run "vitest"    npm --prefix frontend run test -- --run
  run "vite build" npm --prefix frontend run build
}

# ─────────────────────────────── итог ───────────────────────────────
case "$TARGET" in
  all)       verify_contracts; verify_java; verify_python; verify_nlp; verify_web ;;
  contracts) verify_contracts ;;
  java)      verify_java ;;
  python)    verify_python; verify_nlp ;;
  nlp)       verify_nlp ;;
  web)       verify_web ;;
  *) echo "Неизвестная цель: $TARGET (all|contracts|java|python|nlp|web)"; exit 2 ;;
esac

printf '\n%s──────────────────────────────────────────%s\n' "$c_bold" "$c_off"

if [[ ${#FAILURES[@]} -gt 0 ]]; then
  printf '%s✗ Провалено проверок: %d%s\n' "$c_red" "${#FAILURES[@]}" "$c_off"
  printf '    • %s\n' "${FAILURES[@]}"
fi

# Пропущенное перечисляется всегда и после провалов — это не примечание, а часть ответа на вопрос
# «что проверено». Читающий итог должен узнать не только что сломалось, но и о чём гейт промолчал.
if [[ ${#SKIPPED[@]} -gt 0 ]]; then
  printf '%s‒ Не проверено: %d%s\n' "$c_yellow" "${#SKIPPED[@]}" "$c_off"
  printf '    • %s\n' "${SKIPPED[@]}"
  printf '%s  Гейт проверил не всё. В CI такое считается провалом: ./tools/verify.sh --strict%s\n' \
      "$c_yellow" "$c_off"
fi

if [[ ${#FAILURES[@]} -gt 0 ]]; then exit 1; fi

if [[ ${#SKIPPED[@]} -gt 0 ]]; then
  if [[ "$STRICT" == "1" ]]; then
    printf '%s✗ Строгий режим: пропуск проверки — провал%s\n' "$c_red" "$c_off"
    exit 1
  fi
  # Локально пропуск не провал: разработчик без npm вправе проверить JVM-часть. Но и «всё
  # пройдено» здесь сказать нельзя — формулировка называет ровно то, что произошло.
  printf '%s✓ Пройдено проверок: %d, пропущено: %d — за %d с%s\n' \
      "$c_yellow" "$PASSED" "${#SKIPPED[@]}" "$((SECONDS - STARTED_AT))" "$c_off"
  exit 0
fi

if [[ $PASSED -eq 0 ]]; then
  # Ни одна проверка не выполнилась и ни одна не пропустилась — значит гейт не сделал ничего, а
  # сказать об этом «пройдено» было бы прямой неправдой.
  printf '%s✗ Ни одна проверка не выполнилась%s\n' "$c_red" "$c_off"
  exit 1
fi

printf '%s✓ Все проверки пройдены (%d) за %d с%s\n' \
    "$c_green" "$PASSED" "$((SECONDS - STARTED_AT))" "$c_off"
exit 0
