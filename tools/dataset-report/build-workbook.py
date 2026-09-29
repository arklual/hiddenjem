"""Карточка каждой технологии эталона: динамика по годам из двух источников и всё, что о ней известно.

Правила, которых придерживается таблица:

* **Пустая клетка ≠ ноль.** Пусто — источник промолчал или его не спрашивали; ноль — источник
  ответил, и работ нет. Смешать их значит выдать недоступность источника за свойство технологии.
* **Два независимых ряда публикаций.** OpenAlex (основной) и Semantic Scholar (проверка): у
  первого суточный бюджет, и в день замера он кончается, у второго — только темп.
* **Имя аналитика и имя литературы — разные вещи.** У 66 технологий из ста по формулировке
  датасета следов почти нет, а под именем, которым их зовут статьи, следы есть. В таблице есть оба.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

YEARS = list(range(2016, 2027))
UNSAFE = re.compile(r"[^a-z0-9]+")
HEAD_FILL = PatternFill("solid", fgColor="EEEEEE")
BOLD = Font(bold=True)


def cache_path(root: Path, source: str, term: str) -> Path:
    digest = hashlib.sha1(term.strip().lower().encode("utf-8")).hexdigest()[:12]
    slug = UNSAFE.sub("-", term.strip().lower()).strip("-")[:48] or "term"
    return root / source / f"{slug}-{digest}.json"


def read(root: Path, source: str, term: str) -> dict | None:
    path = cache_path(root, source, term)
    if not path.is_file():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return None
    return payload.get("data") if payload.get("ok") else None


def series(data: dict | None, key: str) -> dict[int, int]:
    if not data:
        return {}
    raw = data.get(key) or {}
    return {int(y): int(v or 0) for y, v in raw.items() if str(y).isdigit()}


def stage_of(works: dict[int, int]) -> tuple[str, str]:
    """Стадия по форме ряда публикаций и фраза, объясняющая решение."""
    if not works:
        return "нет данных", "источник о технологии молчит"
    total = sum(works.values())
    if total == 0:
        return "нет следа", "источник ответил, работ по этой формулировке нет"
    early = sum(v for y, v in works.items() if y <= 2021)
    recent = sum(v for y, v in works.items() if y >= 2025)
    middle = sum(v for y, v in works.items() if 2022 <= y <= 2024)
    share = recent / total
    if total <= 5:
        return "единичные работы", f"всего {total} работ за одиннадцать лет"
    if early == 0 and recent > 0:
        return "зарождающаяся", f"до 2022 года работ нет, за последние два года — {recent}"
    if share >= 0.5 and recent > middle:
        return "быстро растущая", f"{share:.0%} работ пришлось на последние два года"
    if share >= 0.25:
        return "растущая", f"{share:.0%} работ за последние два года"
    if early > recent:
        return "зрелая или спадающая", f"до 2022 года {early} работ, после 2024 — {recent}"
    return "ровная", "ряд без выраженного роста"


def style(sheet, widths: dict[int, int], freeze: str, columns: int) -> None:
    for cell in sheet[1]:
        cell.font = BOLD
        cell.fill = HEAD_FILL
        cell.alignment = Alignment(wrap_text=True, vertical="top")
    sheet.freeze_panes = freeze
    for index in range(1, columns + 1):
        sheet.column_dimensions[get_column_letter(index)].width = widths.get(index, 12)
    sheet.auto_filter.ref = f"A1:{get_column_letter(columns)}{sheet.max_row}"


def main() -> None:
    scratch = Path(sys.argv[1])
    cache = scratch / "signals_cache"
    items = json.loads((scratch / "dataset_items.json").read_text(encoding="utf-8"))
    variants = json.loads((scratch / "variants_100.json").read_text(encoding="utf-8"))
    # Статистика по имени, которым технологию называют статьи: лучший вариант и его ряд по годам.
    literature: dict[int, dict] = {}
    literature_path = scratch / "variants_s2.jsonl"
    if literature_path.is_file():
        for line in literature_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                literature[int(row["number"])] = row

    s2: dict[str, dict[str, int]] = {}
    s2_path = scratch / "s2_100.jsonl"
    if s2_path.is_file():
        for line in s2_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                s2[row["term"]] = {k: int(v) for k, v in (row.get("by_year") or {}).items()}

    book = Workbook()
    main_sheet = book.active
    main_sheet.title = "Сигналы"
    header = [
        "№", "Направление", "Технология (датасет)", "Формулировка для поиска",
        "Имя в литературе", "Работ по имени в литературе", "Стадия по имени в литературе",
        "Стадия по динамике", "Почему так", "Источник стадии",
        *[f"Работ {year}" for year in YEARS],
        "Работ всего", "Работ за 2 года", "Доля за 2 года", "Работ до 2022", "Первый год",
        "Организаций", "Стран", "Доля ведущей организации",
        "Работ всего (S2)", "Работ за 2 года (S2)",
        "Препринтов arXiv", "Репозиториев GitHub", "Звёзд GitHub",
        "Упоминаний всего", "Упоминаний за 2 года",
        "Википедия", "Статья создана", "Размер статьи, КБ", "Просмотров за год",
        "В корпусе: фраза", "В корпусе: все слова", "Судьба в конвейере", "Найдена в ТОП-15",
    ]
    main_sheet.append(header)

    works_sheet = book.create_sheet("Публикации по годам")
    works_sheet.append(["№", "Технология", "Формулировка", "Источник", *[str(y) for y in YEARS], "Всего"])
    news_sheet = book.create_sheet("Упоминания по годам")
    news_sheet.append(["№", "Технология", "Формулировка", *[str(y) for y in YEARS], "Всего"])

    for item in items:
        term = item["phrase"]
        openalex = read(cache, "openalex", term)
        hn = read(cache, "hackernews", term)
        wiki = read(cache, "wikipedia", term)
        arxiv = read(cache, "arxiv", term)
        github = read(cache, "github", term)

        works = series(openalex, "works_by_year")
        mentions = series(hn, "mentions_by_year")
        s2_years = {int(y): v for y, v in (s2.get(term) or {}).items()}

        # Стадия считается по тому источнику, который вообще что-то знает: у OpenAlex приоритет,
        # Semantic Scholar подхватывает, когда первый молчит или не нашёл ни одной работы.
        if sum(works.values()) > 0:
            stage, why = stage_of(works)
            stage_source = "OpenAlex"
        elif sum(s2_years.values()) > 0:
            stage, why = stage_of(s2_years)
            stage_source = "Semantic Scholar"
        else:
            stage, why = stage_of(works or s2_years)
            stage_source = "OpenAlex" if works else ("Semantic Scholar" if s2_years else "нет источника")

        lit = literature.get(item["number"]) or {}
        lit_years = {int(y): int(v) for y, v in (lit.get("by_year") or {}).items()}
        lit_stage, _ = stage_of(lit_years) if lit_years else ("нет данных", "")
        lit_name = lit.get("best")
        lit_total = lit.get("total")
        total_works = sum(works.values()) if works else None
        recent_works = sum(v for y, v in works.items() if y >= 2025) if works else None
        first_year = min((y for y, v in works.items() if v), default=None) if works else None
        total_s2 = sum(s2_years.values()) if s2_years else None
        recent_s2 = sum(v for y, v in s2_years.items() if y >= 2025) if s2_years else None
        total_mentions = sum(mentions.values()) if mentions else None
        recent_mentions = sum(v for y, v in mentions.items() if y >= 2025) if mentions else None

        main_sheet.append([
            item["number"], item["area"], item["name"], term,
            lit_name, lit_total, lit_stage if lit_years else None,
            stage, why, stage_source,
            *[works.get(year) if works else None for year in YEARS],
            total_works, recent_works,
            round(recent_works / total_works, 3) if total_works else None,
            sum(v for y, v in works.items() if y <= 2021) if works else None,
            first_year,
            (openalex or {}).get("distinct_institutions"),
            (openalex or {}).get("distinct_countries"),
            round((openalex or {}).get("top_institution_share"), 3)
            if (openalex or {}).get("top_institution_share") is not None else None,
            total_s2, recent_s2,
            (arxiv or {}).get("preprints_window_total"),
            (github or {}).get("repos_window_total"),
            (github or {}).get("stars_total"),
            total_mentions, recent_mentions,
            "есть" if (wiki or {}).get("exists") else ("нет" if wiki else None),
            (wiki or {}).get("created_on"),
            round((wiki or {}).get("size_bytes", 0) / 1024, 1) if (wiki or {}).get("size_bytes") else None,
            (wiki or {}).get("pageviews_12m"),
            item.get("corpusPhraseDocuments"), item.get("corpusAllWordsDocuments"),
            item.get("fate"), item.get("top15"),
        ])
        works_sheet.append([item["number"], item["name"], term, "OpenAlex",
                            *[works.get(y) if works else None for y in YEARS],
                            total_works])
        works_sheet.append([item["number"], item["name"], term, "Semantic Scholar",
                            *[s2_years.get(y) if s2_years else None for y in YEARS],
                            total_s2])
        news_sheet.append([item["number"], item["name"], term,
                           *[mentions.get(y) if mentions else None for y in YEARS],
                           total_mentions])

    lit_sheet = book.create_sheet("Имена в литературе")
    lit_sheet.append(["№", "Технология", "Формулировка аналитика", "Имя в литературе",
                      *[str(y) for y in YEARS], "Всего", "Другие проверенные имена"])
    for item in items:
        lit = literature.get(item["number"]) or {}
        years = {int(y): int(v) for y, v in (lit.get("by_year") or {}).items()}
        others = "; ".join(f"{c['name']} ({c['total']})" for c in (lit.get("checked") or [])[:5])
        lit_sheet.append([item["number"], item["name"], item["phrase"], lit.get("best"),
                          *[years.get(y) if years else None for y in YEARS],
                          lit.get("total"), others])
    style(lit_sheet, {1: 5, 2: 44, 3: 30, 4: 34, 17: 60}, "E2", 4 + len(YEARS) + 2)

    summary = book.create_sheet("Свод")
    summary.append(["Направление", "Стадия по динамике", "Технологий"])
    counts: dict[tuple[str, str], int] = {}
    for row in main_sheet.iter_rows(min_row=2, values_only=True):
        counts[(row[1], row[7])] = counts.get((row[1], row[7]), 0) + 1
    for (area, stage), count in sorted(counts.items()):
        summary.append([area, stage, count])
    summary.append([])
    summary.append(["Всего по стадиям", "", ""])
    totals: dict[str, int] = {}
    for (_, stage), count in counts.items():
        totals[stage] = totals.get(stage, 0) + count
    for stage, count in sorted(totals.items(), key=lambda pair: -pair[1]):
        summary.append(["", stage, count])

    method = book.create_sheet("Как собрано")
    for line in [
        ["Что это"],
        ["Сто технологий размеченного датасета «100 слабых технологических сигналов (сентябрь 2026)»,"],
        ["для каждой — динамика публикаций по годам и всё, что о ней знают открытые источники."],
        [""],
        ["Источники"],
        ["OpenAlex — публикации по годам, число организаций и стран среди авторов, доля ведущей организации."],
        ["  Поиск точной фразой по заголовку и аннотации, окно 2016–2026."],
        ["Semantic Scholar — независимый ряд публикаций по годам той же точной фразой."],
        ["arXiv — препринты; GitHub — репозитории и звёзды; Hacker News — упоминания по годам."],
        ["Википедия (английская) — есть ли статья, дата создания, размер, просмотры за год."],
        ["Корпус стенда — сколько документов собранного корпуса содержат фразу целиком и все её слова."],
        [""],
        ["Чего нет"],
        ["Патентов: источника с посрочным поиском, доступного без ключа и без нарушения robots.txt,"],
        ["не существует (проверены PatentsView, USPTO ODP, EPO OPS, Google Patents)."],
        [""],
        ["Как читать"],
        ["Пустая клетка — источник промолчал или его не спрашивали. Ноль — источник ответил, работ нет."],
        ["«Формулировка для поиска» — язык аналитика. «Имя в литературе» — как ту же вещь называют статьи:"],
        ["варианты предложила модель, а число работ по ним измерено в Semantic Scholar; выбран вариант с наибольшим числом."],
        ["у 66 технологий из ста следов по формулировке аналитика почти нет, а под именем литературы они есть."],
        ["Стадия считается по ряду публикаций: зарождающаяся — до 2022 года работ нет, а за последние два года есть;"],
        ["быстро растущая — половина и больше всех работ пришлась на последние два года;"],
        ["растущая — от четверти; зрелая или спадающая — до 2022 года работ больше, чем после 2024."],
        [""],
        [f"Собрано: {date.today().isoformat()}"],
    ]:
        method.append(line)
    method.column_dimensions["A"].width = 110
    method["A1"].font = BOLD
    for row in (5, 13, 17):
        method.cell(row=row, column=1).font = BOLD

    style(main_sheet, {1: 5, 2: 20, 3: 52, 4: 32, 5: 34, 6: 14, 7: 22, 8: 22, 9: 44, 10: 16}, "E2", len(header))
    style(works_sheet, {1: 5, 2: 44, 3: 30, 4: 18}, "E2", 4 + len(YEARS) + 1)
    style(news_sheet, {1: 5, 2: 44, 3: 30}, "D2", 3 + len(YEARS) + 1)
    for cell in summary[1]:
        cell.font = BOLD
        cell.fill = HEAD_FILL
    summary.column_dimensions["A"].width = 24
    summary.column_dimensions["B"].width = 26
    summary.column_dimensions["C"].width = 12

    book.save(scratch / sys.argv[2])
    print("листов:", len(book.sheetnames), "| технологий:", main_sheet.max_row - 1)


if __name__ == "__main__":
    main()
