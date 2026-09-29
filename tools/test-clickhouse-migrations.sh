#!/usr/bin/env bash
# =============================================================================
#  Проверка прогонщика миграций ClickHouse без самого ClickHouse
# =============================================================================
#  Прогонщик решает четыре вещи, и каждая ломается молча: порядок применения,
#  пропуск уже применённого, отказ при правке применённой миграции, отказ при
#  неверном имени файла. Кластера в сборке нет, поэтому вместо клиента
#  подставляется заглушка: она записывает запросы и отвечает заранее заданным
#  списком применённых версий.
#
#  Заглушка — не упрощение задачи, а способ проверить именно логику прогонщика.
#  Проверять её вместе с настоящим ClickHouse значило бы проверять ClickHouse.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNNER="$ROOT/deploy/compose/clickhouse/apply-migrations.sh"
FAILURES=0

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Заглушка клиента: пишет каждый вызов в $LOG, а на SELECT отвечает содержимым
# $APPLIED (версии) или $CHECKSUM (контрольная сумма).
make_stub() {
    cat > "$1/clickhouse-client" <<'STUB'
#!/bin/sh
query=""
while [ $# -gt 0 ]; do
    case "$1" in
        --query) query="$2"; shift 2 ;;
        *) shift ;;
    esac
done
if [ -z "$query" ]; then
    # Вызов с --multiquery и файлом на входе: это применение миграции.
    cat > /dev/null
    echo "APPLY" >> "$LOG"
    exit 0
fi
echo "$query" | tr '\n' ' ' >> "$LOG"
echo "" >> "$LOG"
case "$query" in
    *"SELECT version FROM schema_migrations"*) cat "$APPLIED" ;;
    *"SELECT checksum FROM schema_migrations"*) cat "$CHECKSUM" ;;
esac
exit 0
STUB
    chmod +x "$1/clickhouse-client"
}

run_case() {
    local name="$1" applied="$2" checksum="$3" dir="$4"
    local work
    work="$(mktemp -d)"
    make_stub "$work"
    printf '%s' "$applied" > "$work/applied"
    printf '%s' "$checksum" > "$work/checksum"
    LOG="$work/log" APPLIED="$work/applied" CHECKSUM="$work/checksum" \
        CH_CLIENT="$work/clickhouse-client" CH_DIR="$dir" CH_PASSWORD="x" \
        sh "$RUNNER" > "$work/out" 2>&1 && echo 0 > "$work/code" || echo 1 > "$work/code"
    LAST_WORK="$work"
}

MIGRATIONS="$ROOT/deploy/compose/clickhouse/migrations"

# ── 1. На пустой базе применяются все миграции, по возрастанию номера ──────────
run_case "empty" "" "" "$MIGRATIONS"
applied_count="$(grep -c '^APPLY$' "$LAST_WORK/log" || true)"
expected_count="$(ls "$MIGRATIONS"/V*.sql | wc -l | tr -d ' ')"
if [ "$applied_count" = "$expected_count" ]; then
    pass "на пустой базе применяются все $expected_count миграций"
else
    fail "на пустой базе применено $applied_count из $expected_count"
fi

# Порядок проверяется на наборе, где лексическая сортировка расходится с числовой. На V1..V3
# они совпадают, и такой набор не отличил бы верную сортировку от `sort` без ключей — ровно та
# ошибка образца, из-за которой первая версия этой проверки ничего не проверяла.
ordering="$(mktemp -d)"
for v in 1 2 10; do
    cp "$MIGRATIONS/V1__term_mentions.sql" "$ordering/V${v}__probe.sql"
done
run_case "ordering" "" "" "$ordering"
order="$(grep -o "VALUES ([0-9]*" "$LAST_WORK/log" | grep -o '[0-9]*' | tr '\n' ' ')"
if [ "$order" = "1 2 10 " ]; then
    pass "порядок применения — по номеру версии, а не по алфавиту: $order"
else
    fail "порядок применения нарушен: «$order» вместо «1 2 10 »"
fi
rm -rf "$ordering"

# ── 2. Уже применённые миграции не выполняются повторно ───────────────────────
# Контрольная сумма совпадает с настоящей, значит расхождения нет.
real_checksum="$(md5sum "$MIGRATIONS/V1__term_mentions.sql" | cut -d' ' -f1)"
run_case "applied" "1
2
3
" "$real_checksum" "$MIGRATIONS"
if ! grep -q '^APPLY$' "$LAST_WORK/log"; then
    pass "применённые миграции повторно не выполняются"
else
    fail "применённая миграция выполнена повторно"
fi

# ── 3. Правка применённой миграции — отказ, а не тихое расхождение ────────────
run_case "tampered" "1
" "ffffffffffffffffffffffffffffffff" "$MIGRATIONS"
if [ "$(cat "$LAST_WORK/code")" = "1" ] && grep -q "изменён после применения" "$LAST_WORK/out"; then
    pass "правка применённой миграции останавливает развёртывание"
else
    fail "правка применённой миграции прошла незамеченной"
fi

# ── 4. Файл с неверным именем — отказ, а не молчаливый пропуск ────────────────
badnames="$(mktemp -d)"
cp "$MIGRATIONS/V1__term_mentions.sql" "$badnames/Vx__broken.sql"
run_case "badname" "" "" "$badnames"
if [ "$(cat "$LAST_WORK/code")" = "1" ]; then
    pass "файл с неверным именем останавливает развёртывание"
else
    fail "файл с неверным именем пропущен молча"
fi
rm -rf "$badnames"

# ── 5. Журнал применённого создаётся до первой миграции ──────────────────────
run_case "journal" "" "" "$MIGRATIONS"
if grep -q "CREATE TABLE IF NOT EXISTS schema_migrations" "$LAST_WORK/log"; then
    pass "журнал применённого создаётся до миграций"
else
    fail "журнал применённого не создаётся"
fi

if [ "$FAILURES" -gt 0 ]; then
    printf '\n\033[31mпрогонщик миграций ClickHouse: провалено %s\033[0m\n' "$FAILURES" >&2
    exit 1
fi
printf '\nпрогонщик миграций ClickHouse проверен\n'
