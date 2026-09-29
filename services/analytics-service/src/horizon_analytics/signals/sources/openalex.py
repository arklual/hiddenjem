"""OpenAlex: сколько про технологию написано и кем — три запроса на термин.

Всю работу делает ``group_by``: OpenAlex считает сам, а мы забираем готовые группы. Годовой ряд —
один запрос вместо девяти, состав организаций — один запрос вместо выборки работ. Для живого
анализа сотни кандидатов это решающая разница: девять запросов на термин превращают двести
кандидатов в час ожидания, один — в минуту.

Дату в фильтр намеренно не кладём. ``group_by=publication_year`` и так возвращает все годы, и без
ограничения по дате тот же единственный запрос даёт заодно ``meta.count`` за всю историю — а это
как раз то, что отличает «тему придумали позавчера» от «о теме пишут с 2001 года».

Фильтр ``title_and_abstract.search`` с фразой в кавычках, а не ``search``: полнотекстовый поиск
OpenAlex цепляет фразу из списка литературы, и тогда «работа про технологию» и «работа, где
технология упомянута» перестают различаться.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from horizon_analytics.openalex_keys import keys
from horizon_analytics.signals.http import DEFAULT_CONTACT, Fetcher, Pace, SourceUnavailableError

__all__ = [
    "DEFAULT_EXAMPLE_LIMIT",
    "EXAMPLES_NAME",
    "EXAMPLES_VERSION",
    "NAME",
    "VERSION",
    "collect",
    "collect_examples",
    "has_key",
    "make_pace",
    "parse_concentration",
    "parse_group_count",
    "parse_works",
    "parse_year_groups",
]

NAME = "openalex"
#: Версия разбора: меняется, когда меняется состав полей, и обнуляет кэш этого источника.
VERSION = 4

_WORKS = "https://api.openalex.org/works"

#: Потолок одной страницы группировки. ``meta.groups_count`` не может его превысить, поэтому
#: ровно 200 различных организаций означает «двести и больше», а не «ровно двести».
_GROUP_PAGE = 200


def has_key() -> bool:
    """Есть ли хотя бы один ключ OpenAlex."""
    return bool(keys.current())


def _get(fetcher: Fetcher, params: dict[str, Any]) -> dict[str, Any]:
    while True:
        key = keys.current()
        try:
            return fetcher.get(
                _WORKS,
                params=params,
                headers={"Authorization": f"Bearer {key}"} if key else None,
                rotate_on_limit=bool(key),
            ).json()
        except SourceUnavailableError as exc:
            if key and exc.status in (403, 429) and keys.exhausted(key):
                continue
            raise


def make_pace() -> Pace:
    """Десять запросов в секунду в вежливом пуле, вдвое быстрее — с ключом."""
    return Pace(0.05 if has_key() else 0.1)


def collect(
    fetcher: Fetcher,
    term: str,
    *,
    years: Sequence[int],
    contact: str = DEFAULT_CONTACT,
) -> dict[str, Any]:
    """Собирает по фразе годовой ряд работ, число организаций и число стран."""
    query = f'title_and_abstract.search:"{term}"'
    base: dict[str, Any] = {"filter": query}
    if contact:
        base["mailto"] = contact

    # Окно применяется к организациям и странам фильтром, а не нарезкой: у годового ряда лишние
    # годы просто отбрасываются, а число организаций приходит одним числом за всё время. Без этого
    # фильтра признак «сколько организаций» подсматривал бы то, что случилось после окна, — и
    # замер «что было видно год назад» показывал бы сегодняшнее состояние.
    windowed = dict(base)
    windowed["filter"] = (
        f"{query},from_publication_date:{min(years)}-01-01,to_publication_date:{max(years)}-12-31"
    )
    grouped = _get(fetcher, {**base, "group_by": "publication_year"})
    institutions = _get(fetcher, {**windowed, "group_by": "institutions.id"})
    countries = _get(fetcher, {**windowed, "group_by": "institutions.country_code"})

    series = parse_year_groups(grouped, years)
    distinct_institutions, institutions_truncated = parse_group_count(institutions)
    distinct_countries, countries_truncated = parse_group_count(countries)
    return {
        **series,
        "distinct_institutions": distinct_institutions,
        "distinct_institutions_truncated": institutions_truncated,
        "distinct_countries": distinct_countries,
        "distinct_countries_truncated": countries_truncated,
        **parse_concentration(institutions, series["works_all_time"]),
    }


def parse_year_groups(payload: Mapping[str, Any], years: Sequence[int]) -> dict[str, Any]:
    """Годовой ряд, размер окна и доля последних двух лет.

    Годы, которых нет в ответе, — это ноль работ, а не пропуск: OpenAlex не возвращает пустые
    группы. Доля последних двух лет считается от окна, а не от всей истории: у старых терминов
    хвост за 1990-е размывает её до нуля и перестаёт отвечать на вопрос «растёт ли сейчас».
    """
    groups = {
        str(row.get("key")): int(row.get("count") or 0) for row in payload.get("group_by", [])
    }
    by_year = {str(year): groups.get(str(year), 0) for year in years}
    window_total = sum(by_year.values())
    recent = [str(year) for year in sorted(years)[-2:]]
    recent_total = sum(by_year[year] for year in recent)
    return {
        "works_by_year": by_year,
        "works_window_total": window_total,
        "works_all_time": int((payload.get("meta") or {}).get("count") or 0),
        "recent_two_year_share": (recent_total / window_total) if window_total else None,
        "recent_two_year_works": recent_total,
    }


def parse_group_count(payload: Mapping[str, Any]) -> tuple[int, bool]:
    """Сколько различных непустых групп вернул ``group_by`` и упёрлось ли это в потолок.

    Считаются именно различные организации, а не авторские позиции: одна работа тридцати авторов
    из одного института — по-прежнему один институт, и признак «тема вышла за пределы одной
    лаборатории» должен это видеть.

    Признак усечения хранится рядом с числом, потому что OpenAlex отдаёт не больше двухсот групп.
    Без него двести организаций у широкой темы выглядели бы точным числом, и модель научилась бы,
    что «двести» — это потолок популярности, а не потолок страницы.
    """
    rows = payload.get("group_by") or []
    non_empty = sum(1 for row in rows if int(row.get("count") or 0) > 0)
    reported = int((payload.get("meta") or {}).get("groups_count") or non_empty)
    count = max(non_empty, reported) if reported else non_empty
    return count, count >= _GROUP_PAGE


def parse_concentration(payload: Mapping[str, Any], works: int) -> dict[str, Any]:
    """Насколько тема сосредоточена в одной лаборатории.

    Одного числа организаций мало: страница группировки кончается на двухстах, и у любой
    сколько-нибудь заметной темы счётчик упирается в потолок — двести и у темы из пятисот работ,
    и у темы из трёхсот тысяч. Признак перестаёт различать.

    Доля самой активной организации потолка не знает и отвечает на тот же вопрос точнее: «каждая
    пятая работа выходит из одной лаборатории» — это ранняя стадия, «самая активная организация
    даёт процент работ» — это уже поле, а не лаборатория.
    """
    rows = payload.get("group_by") or []
    if not rows or works <= 0:
        return {"top_institution": None, "top_institution_share": None}
    top = max(rows, key=lambda row: int(row.get("count") or 0))
    return {
        "top_institution": top.get("key_display_name"),
        "top_institution_share": int(top.get("count") or 0) / works,
    }


# ───────────────────────────── примеры работ ─────────────────────────────
#
# Отдельный источник со своим именем и своей версией, а не ещё одно поле в `collect`. Причина
# практическая: `collect` считает признаки и кэшируется под именем `openalex`; добавив в него
# выборку работ, мы обнулили бы весь уже собранный кэш и заставили пересобрать обучающую выборку
# ради данных, которые нужны не каждому термину, а только предложенным моделью именам.
#
# Зачем выборка вообще. Групповые счётчики отвечают «сколько», но не «что именно», а тема,
# предложенная моделью, обязана прийти в отчёт с доказательной базой — иначе правило «тренд без
# доказательств не публикуется» (BR-A6) не выполнено, и выдача опирается на знания модели.

EXAMPLES_NAME = "openalex_examples"
#: Версия разбора выборки работ. Своя, потому что источник свой.
EXAMPLES_VERSION = 1

#: Сколько работ забирать на термин. Пять — потолок доказательной базы одной темы в отчёте по
#: умолчанию, и брать больше значит платить трафиком за то, что всё равно не покажут.
DEFAULT_EXAMPLE_LIMIT = 5


def collect_examples(
    fetcher: Fetcher,
    term: str,
    *,
    years: Sequence[int],
    limit: int = DEFAULT_EXAMPLE_LIMIT,
    contact: str = DEFAULT_CONTACT,
) -> dict[str, Any]:
    """Несколько самых цитируемых работ по точной фразе — доказательная база темы.

    Сортировка по цитированиям, а не по дате: доказательство должно быть проверяемым, а работа с
    цитированиями пережила чужую проверку. Окно ограничено теми же годами, что и признаки, — иначе
    в доказательствах темы «на ранней стадии» оказалась бы работа 2003 года.
    """
    ordered = sorted(years)
    query = f'title_and_abstract.search:"{term}",from_publication_date:{ordered[0]}-01-01'
    params: dict[str, Any] = {
        "filter": query,
        "per_page": max(1, min(25, limit)),
        "sort": "cited_by_count:desc",
        "select": ",".join(
            (
                "id",
                "doi",
                "title",
                "publication_date",
                "publication_year",
                "type",
                "cited_by_count",
                "primary_location",
                "authorships",
                "abstract_inverted_index",
            )
        ),
    }
    if contact:
        params["mailto"] = contact
    payload = _get(fetcher, params)
    return {"works": parse_works(payload, limit=limit)}


def parse_works(payload: Mapping[str, Any], *, limit: int) -> list[dict[str, Any]]:
    """Разобрать ответ в список работ: то, из чего собирается источник в отчёте."""
    rows: list[dict[str, Any]] = []
    for item in (payload.get("results") or [])[:limit]:
        if not isinstance(item, Mapping):
            continue
        title = str(item.get("title") or "").strip()
        published = str(item.get("publication_date") or "").strip()
        if not title or len(published) != 10:
            # Работа без имени или без даты не годится в доказательство: показать её нечем, а
            # достроить дату по году значило бы выдумать наблюдение.
            continue
        rows.append(
            {
                "id": str(item.get("id") or "").rsplit("/", 1)[-1],
                "doi": _doi(item.get("doi")),
                "title": title[:500],
                "published_on": published,
                "type": str(item.get("type") or "").strip(),
                "cited_by_count": int(item.get("cited_by_count") or 0),
                "venue": _venue(item.get("primary_location")),
                "url": _url(item),
                "authors": _authors(item.get("authorships")),
                "abstract": _abstract(item.get("abstract_inverted_index")),
            }
        )
    return rows


def _doi(value: object) -> str | None:
    """DOI без префикса адреса — так его печатают в списке литературы."""
    text = str(value or "").strip()
    if not text:
        return None
    return text.removeprefix("https://doi.org/") or None


def _venue(location: object) -> str | None:
    """Имя журнала или репозитория, если источник его назвал."""
    if not isinstance(location, Mapping):
        return None
    source = location.get("source")
    if not isinstance(source, Mapping):
        return None
    name = str(source.get("display_name") or "").strip()
    return name[:300] or None


def _url(item: Mapping[str, Any]) -> str:
    """Ссылка, по которой работу можно открыть: полный текст, иначе страница OpenAlex."""
    location = item.get("primary_location")
    if isinstance(location, Mapping):
        landing = str(location.get("landing_page_url") or "").strip()
        if landing.startswith("http"):
            return landing[:1000]
    return str(item.get("id") or "").strip()[:1000]


def _authors(authorships: object) -> list[dict[str, Any]]:
    """Авторы с организациями: BRULE-1 считает именно различные организации."""
    rows: list[dict[str, Any]] = []
    if not isinstance(authorships, Sequence) or isinstance(authorships, str | bytes):
        return rows
    for entry in authorships[:20]:
        if not isinstance(entry, Mapping):
            continue
        author = entry.get("author")
        name = (
            str((author or {}).get("display_name") or "").strip()
            if isinstance(author, Mapping)
            else ""
        )
        if not name:
            continue
        institutions = entry.get("institutions")
        organization = ""
        country = ""
        if isinstance(institutions, Sequence) and not isinstance(institutions, str | bytes):
            for institution in institutions:
                if isinstance(institution, Mapping):
                    organization = str(institution.get("display_name") or "").strip()
                    country = str(institution.get("country_code") or "").strip().upper()
                    if organization:
                        break
        rows.append(
            {"name": name[:200], "organization": organization[:300], "country": country[:2]}
        )
    return rows


def _abstract(inverted: object) -> str | None:
    """Восстановить аннотацию из обратного индекса OpenAlex.

    Восстановление, а не пересказ: слова и их позиции — данные источника, и собранный из них текст
    равен исходной аннотации. Без неё в отчёте у темы остались бы одни заголовки, а мотивация и
    определение собираются из предложений.
    """
    if not isinstance(inverted, Mapping) or not inverted:
        return None
    positions: list[tuple[int, str]] = []
    for word, places in inverted.items():
        if not isinstance(places, Sequence) or isinstance(places, str | bytes):
            continue
        for place in places:
            try:
                positions.append((int(place), str(word)))
            except (TypeError, ValueError):
                continue
    if not positions:
        return None
    positions.sort()
    return " ".join(word for _, word in positions)[:4000] or None
