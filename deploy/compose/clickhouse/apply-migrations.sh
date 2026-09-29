#!/bin/sh
# =============================================================================
#  Применение миграций схемы ClickHouse
# =============================================================================
#  Зачем это существует. Раньше init выполнял все файлы схемы при каждом подъёме,
#  и каждый из них был обёрнут в `CREATE TABLE IF NOT EXISTS`. На пустом кластере
#  это работает; на существующем — правка списка колонок не применяется НИКОГДА:
#  `IF NOT EXISTS` тихо пропускает таблицу, и никто об этом не узнаёт. В CI дефект
#  невидим по построению — сквозной сценарий всегда поднимает пустой том.
#
#  Ровно тот класс «зелено везде, не работает на стенде», ради которого заведена
#  проверка миграций Postgres (docs/01-analysis/29-migration-safety-spec.md).
#
#  Модель — та же, что у Flyway: файлы `V<номер>__<описание>.sql`, журнал
#  применённого в самой базе, контрольная сумма против правки задним числом.
#
#  Чего здесь намеренно нет: блокировки. В ClickHouse её негде взять, а два
#  одновременных init — ситуация развёртывания, а не эксплуатации. Гонка привела
#  бы к повторному применению миграции; все они идемпотентны (`IF NOT EXISTS`,
#  `CREATE OR REPLACE`), поэтому цена гонки — лишняя строка в журнале.
# =============================================================================
set -eu

CH_HOST="${CH_HOST:-clickhouse}"
CH_PORT="${CH_PORT:-9000}"
CH_DB="${CH_DB:-horizon}"
CH_USER="${CH_USER:-horizon}"
CH_PASSWORD="${CH_PASSWORD:-}"
CH_DIR="${CH_DIR:-/sql/migrations}"
# Клиент вынесен в переменную, чтобы прогонщик можно было проверить без кластера:
# тест подставляет заглушку, записывающую запросы.
CH_CLIENT="${CH_CLIENT:-clickhouse-client}"

log() { echo "[clickhouse-migrate] $*"; }

query() {
    "$CH_CLIENT" --host "$CH_HOST" --port "$CH_PORT" \
        --user "$CH_USER" --password "$CH_PASSWORD" --query "$1"
}

query_db() {
    "$CH_CLIENT" --host "$CH_HOST" --port "$CH_PORT" \
        --user "$CH_USER" --password "$CH_PASSWORD" --database "$CH_DB" --query "$1"
}

apply_file() {
    "$CH_CLIENT" --host "$CH_HOST" --port "$CH_PORT" \
        --user "$CH_USER" --password "$CH_PASSWORD" --database "$CH_DB" --multiquery < "$1"
}

log "база $CH_DB"
query "CREATE DATABASE IF NOT EXISTS ${CH_DB}"

# Журнал применённого. ReplacingMergeTree: повторная вставка той же версии при гонке
# схлопывается, а чтение идёт с FINAL.
query_db "CREATE TABLE IF NOT EXISTS schema_migrations (
    version    UInt32,
    name       String,
    checksum   String,
    applied_at DateTime DEFAULT now()
) ENGINE = ReplacingMergeTree(applied_at) ORDER BY version"

applied_versions="$(query_db "SELECT version FROM schema_migrations FINAL ORDER BY version FORMAT TabSeparated")"

checksum_of() {
    # md5sum есть и в alpine, и в debian-образах ClickHouse; sha256sum — не везде.
    md5sum "$1" | cut -d' ' -f1
}

recorded_checksum() {
    query_db "SELECT checksum FROM schema_migrations FINAL WHERE version = $1 FORMAT TabSeparated"
}

# Порядок задаётся номером версии, а не именем файла: лексически V10 идёт раньше V2, и схема
# применилась бы задом наперёд. Номер извлекается из имени и ставится в начало строки, потому что
# сортировать сам путь по позиции символа нельзя — длина каталога у всех разная.
listing="$(
    for path in "$CH_DIR"/V*.sql; do
        [ -f "$path" ] || continue
        file="$(basename "$path")"
        version="$(echo "$file" | sed -n 's/^V\([0-9][0-9]*\)__.*$/\1/p')"
        if [ -z "$version" ]; then
            echo "BADNAME $file"
        else
            echo "$version $path"
        fi
    done | sort -n -k1,1
)"

# Список подаётся через дескриптор 3, а не через stdin: клиент ClickHouse внутри цикла
# читает stdin и съедает оставшиеся строки списка — вторая миграция уезжала в текст
# INSERT. Видно только при двух и более файлах, поэтому и не всплывало.
listing_file="${TMPDIR:-/tmp}/clickhouse-migrations.list"
printf '%s\n' "$listing" > "$listing_file"
while IFS=" " read -r version path <&3; do
    [ -n "$version" ] || continue
    if [ "$version" = "BADNAME" ]; then
        log "ОШИБКА: $path не соответствует «V<номер>__<описание>.sql»"
        exit 1
    fi
    file="$(basename "$path")"
    checksum="$(checksum_of "$path")"

    if echo "$applied_versions" | grep -qx "$version"; then
        recorded="$(recorded_checksum "$version")"
        if [ -n "$recorded" ] && [ "$recorded" != "$checksum" ]; then
            log "ОШИБКА: $file изменён после применения (было $recorded, стало $checksum)."
            log "Применённую миграцию править нельзя — добавьте следующую версию."
            exit 1
        fi
        log "уже применена: $file"
        continue
    fi

    log "применяю: $file"
    apply_file "$path"
    query_db "INSERT INTO schema_migrations (version, name, checksum) VALUES ($version, '$file', '$checksum')"
done 3<"$listing_file" || exit 1

log "готово"
