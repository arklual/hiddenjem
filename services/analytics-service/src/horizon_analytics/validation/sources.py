"""Живые открытые источники: сколько и когда писали о технологии.

Каждый источник отвечает на свой вопрос, и ни один не отвечает на все.

* **OpenAlex** — научная субстанция: сколько работ и как их число менялось по годам.
* **arXiv** — самый ранний сигнал: препринт выходит раньше публикации на месяцы и годы.
* **GitHub** — миграция разработчиков: у технологии, которую кто-то начал делать, появляется код.
* **Hacker News** — раннее внимание инженерного сообщества, с датами.
* **Википедия** — зрелость: у массово внедрённой технологии есть большая и давняя статья, у
  зарождающейся её нет вовсе.

Запросы — **точной фразой**, и это не деталь. Поиск по мешку слов даёт мусор в объёмах, на которые
нельзя опереться: «AI red teaming» без кавычек находит 20 129 работ 2025 года (всё, где есть слово
«AI»), с кавычками — 43. Первое число сообщает о размере области, второе — о технологии.

Отказ источника — не отказ замера: каждая функция возвращает «нет данных», и признак считается по
оставшимся. Источник, который лёг, обязан уменьшать уверенность, а не портить число.
"""

from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date
from typing import Final

from horizon_analytics.openalex_keys import keys

__all__ = [
    "SourceEvidence",
    "collect_evidence",
    "collect_maturity_evidence",
    "set_contact_email",
    "set_openalex_api_key",
]

_CONTACT_EMAIL = "ops@horizon.example"
_USER_AGENT = "HorizonWeakSignals/1.0 (+https://horizon.dev; mailto:{email})"

#: Пауза между обращениями к одному источнику. Значения — из их же условий использования:
#: arXiv просит не чаще одного запроса в три секунды, GDELT — одного в пять, OpenAlex допускает
#: сотню в секунду, но вежливость дешевле блокировки.
_DELAYS: Mapping[str, float] = {
    "openalex": 0.2,
    "arxiv": 3.0,
    # Шестнадцать секунд, а не пять из их же документации. Замер: на 5.5 с источник отвечал 429
    # почти всегда, на 8 с — в 135 случаях из 173, то есть новостного следа замер не получал
    # вовсе. Интервал дорог (174 темы — три четверти часа только на новости), но альтернатива —
    # не признак, а его отсутствие, записанное как признак.
    "gdelt": 16.0,
    "github": 6.5,
    "hn": 0.5,
    "wikipedia": 0.3,
}

_LAST_CALL: dict[str, float] = {}


def set_contact_email(email: str) -> None:
    """Задать контактный адрес, который уходит в User-Agent и в ``mailto``.

    Без настоящего адреса источники рано или поздно отвечают 429 — это уже случалось и разобрано
    в ранбуке «Источник отвечает 429».
    """
    global _CONTACT_EMAIL
    _CONTACT_EMAIL = email


_OPENALEX_API_KEY = ""


def set_openalex_api_key(key: str) -> None:
    """Задать ключ OpenAlex. Без ключа суточный бюджет — $0,10, то есть сто поисковых запросов.

    Замер заголовков ответа стенду 2026-09-18: ``x-ratelimit-limit-usd: 0.1``, запрос с поиском
    стоит 10 кредитов из 1000. Бесплатный ключ поднимает бюджет до $1 в сутки.
    """
    global _OPENALEX_API_KEY
    _OPENALEX_API_KEY = key.strip()


def _throttle(source: str) -> None:
    delay = _DELAYS.get(source, 1.0)
    last = _LAST_CALL.get(source)
    if last is not None:
        wait = delay - (time.monotonic() - last)
        if wait > 0:
            time.sleep(wait)
    _LAST_CALL[source] = time.monotonic()


def _fetch(
    source: str, url: str, *, timeout: float = 45.0, attempts: int = 3,
    rotate_on_limit: bool = False,
    api_key: str = "",
) -> str | None:
    """Забрать тело ответа; ``None`` — источник не ответил.

    ``attempts`` различается по источникам не из аккуратности, а по замеру. GDELT отвечает 429
    примерно за девять секунд и не меняет ответа от повтора: три попытки с паузами превращали
    сорок секунд ожидания в тот же отказ и растягивали замер на часы впустую. Там, где повтор
    помогает — сетевой сбой у OpenAlex, — он остаётся.
    """
    _throttle(source)
    headers = {"User-Agent": _USER_AGENT.format(email=_CONTACT_EMAIL)}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(url, headers=headers)
    for attempt in range(max(1, attempts)):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body: bytes = response.read()
            return body.decode("utf-8", errors="replace")
        except urllib.error.HTTPError as error:
            if rotate_on_limit and (
                error.code == 429
                or (error.code == 403 and (
                    error.headers.get("x-ratelimit-remaining") == "0"
                    or error.headers.get("Retry-After")
                ))
            ):
                raise
            if error.code in {429, 500, 502, 503, 504} and attempt < attempts - 1:
                time.sleep(4.0 * (attempt + 1))
                continue
            return None
        except Exception:
            if attempt < attempts - 1:
                time.sleep(2.0 * (attempt + 1))
                continue
            return None
    return None


@dataclass(slots=True)
class SourceEvidence:
    """Что открытые источники знают об одной технологии.

    Ряды по годам, а не суммы: методологию интересует форма кривой, и сумма её теряет. Год без
    записей присутствует нулём — пробел в ряду это тоже факт.
    """

    query: str
    #: Фраза, по которой данные действительно получены.
    #:
    #: Может быть короче запрошенной: формулировка аналитика описывает технологию предложением
    #: («Autonomous procurement and supply chain agents in ERP»), а в источниках она называется
    #: фразой из двух-трёх слов. Точная фраза целиком не находится нигде, и тогда запрос
    #: сокращается справа до первой находки. Величина записывается в отчёт: замер обязан говорить,
    #: по чему он сделан, иначе ноль по длинной фразе неотличим от отсутствия технологии.
    effective_query: str = ""
    #: OpenAlex: {год: число работ}
    works_by_year: dict[int, int] = field(default_factory=dict)
    #: arXiv: общее число препринтов с этой фразой
    preprints: int | None = None
    #: arXiv: год самого раннего препринта
    preprint_first_year: int | None = None
    #: GitHub: число репозиториев и год самого раннего
    repositories: int | None = None
    repository_first_year: int | None = None
    #: Hacker News: {год: число обсуждений}
    stories_by_year: dict[int, int] = field(default_factory=dict)
    #: Википедия: {язык: (год создания статьи, длина в байтах)}
    wikipedia: dict[str, tuple[int, int]] = field(default_factory=dict)
    #: Новости: {год: число публикаций}. Отдельный вид источника, а не вариант остальных.
    #:
    #: Без него замер систематически терял именно те сигналы, ради которых существует. Датасет
    #: методологов держится на отраслевых медиа и объявлениях компаний — раунд, выход из stealth,
    #: первый аудит, — а этого следа нет ни в OpenAlex, ни на arXiv, ни в репозиториях. Двадцать
    #: положительных строк из ста получали вердикт «следов слишком мало» при живой технологии.
    news_by_year: dict[int, int] = field(default_factory=dict)
    #: Домены, где о технологии писали. Разные домены — разные свидетельства, один домен —
    #: одно, сколько бы заметок он ни выпустил.
    news_domains: tuple[str, ...] = ()
    #: Источники, которые не ответили. Уменьшают уверенность, а не портят числа.
    unavailable: tuple[str, ...] = ()

    @property
    def works_total(self) -> int:
        """Всего научных работ по точной фразе."""
        return sum(self.works_by_year.values())

    def works_since(self, year: int) -> int:
        """Работы, вышедшие не раньше ``year``."""
        return sum(count for published, count in self.works_by_year.items() if published >= year)


#: До какого момента OpenAlex считается недоступным (``time.monotonic``). Исчерпанный суточный
#: бюджет отвечает 429 на каждый запрос, и три попытки с паузами стоили двенадцать секунд на тему:
#: проверка зрелости с бюджетом в две минуты успевала десять тем, остальные проходили непроверенными
#: (стенд, 29.09). Отказ — пауза в десять минут, дальше ответ «недоступен» сразу.
_OPENALEX_DOWN_UNTIL = 0.0
_OPENALEX_PAUSE_SECONDS = 600.0


def _openalex_years(phrase: str) -> tuple[dict[int, int], bool]:
    """Число работ по годам для точной фразы; второй элемент — удался ли запрос."""
    global _OPENALEX_DOWN_UNTIL
    if time.monotonic() < _OPENALEX_DOWN_UNTIL:
        return {}, False
    quoted = urllib.parse.quote(f'"{phrase}"')
    url = (
        "https://api.openalex.org/works"
        f"?filter=title_and_abstract.search:{quoted}"
        f"&group_by=publication_year&mailto={urllib.parse.quote(_CONTACT_EMAIL)}"
    )
    while True:
        key = keys.current(_OPENALEX_API_KEY)
        try:
            body = _fetch("openalex", url, rotate_on_limit=bool(key), api_key=key)
            break
        except urllib.error.HTTPError:
            if not keys.exhausted(key, _OPENALEX_API_KEY):
                return {}, False
    if body is None:
        _OPENALEX_DOWN_UNTIL = time.monotonic() + _OPENALEX_PAUSE_SECONDS
        return {}, False
    try:
        payload = json.loads(body)
    except ValueError:
        return {}, False
    years: dict[int, int] = {}
    for group in payload.get("group_by", []):
        key = str(group.get("key", ""))
        if key.isdigit():
            years[int(key)] = int(group.get("count", 0))
    return years, True


def _semanticscholar_recent(phrase: str, since_year: int) -> tuple[int | None, bool]:
    """Число работ с точной фразой начиная с года — запасной путь проверки зрелости.

    Когда OpenAlex недоступен, правило мейнстрима теряет число работ и пропускает давно массовые
    технологии («data augmentation», «graph neural networks»). Semantic Scholar без суточного
    бюджета отвечает на тот же вопрос одним запросом с фильтром по годам; порядок чисел тот же.
    """
    quoted = urllib.parse.quote(f'"{phrase}"')
    url = (
        "https://api.semanticscholar.org/graph/v1/paper/search/bulk"
        f"?query={quoted}&year={since_year}-&fields=year"
    )
    body = _fetch("semanticscholar", url)
    if body is None:
        return None, False
    try:
        total = json.loads(body).get("total")
    except ValueError:
        return None, False
    return (int(total), True) if isinstance(total, int) else (None, False)


def _arxiv_count(phrase: str) -> tuple[int | None, int | None, bool]:
    """Число препринтов и год самого раннего."""
    quoted = urllib.parse.quote(f'"{phrase}"')
    url = (
        "https://export.arxiv.org/api/query"
        f"?search_query=all:{quoted}&start=0&max_results=1"
        "&sortBy=submittedDate&sortOrder=ascending"
    )
    body = _fetch("arxiv", url)
    if body is None:
        return None, None, False
    total = re.search(r"opensearch:totalResults[^>]*>(\d+)<", body)
    published = re.search(r"<published>(\d{4})-", body)
    return (
        int(total.group(1)) if total else None,
        int(published.group(1)) if published else None,
        True,
    )


def _github_repositories(phrase: str) -> tuple[int | None, int | None, bool]:
    """Число репозиториев по точной фразе и год самого раннего.

    Без токена поиск GitHub разрешает десять запросов в минуту, поэтому здесь ровно один запрос
    на технологию: сортировка по возрастанию даты создания даёт и счётчик, и первый год.
    """
    quoted = urllib.parse.quote(f'"{phrase}"')
    url = (
        "https://api.github.com/search/repositories"
        f"?q={quoted}&sort=updated&order=asc&per_page=1"
    )
    body = _fetch("github", url)
    if body is None:
        return None, None, False
    try:
        payload = json.loads(body)
    except ValueError:
        return None, None, False
    if "total_count" not in payload:
        return None, None, False
    items = payload.get("items") or []
    first_year = None
    if items:
        created = str(items[0].get("created_at") or "")
        if len(created) >= 4 and created[:4].isdigit():
            first_year = int(created[:4])
    return int(payload["total_count"]), first_year, True


def _hacker_news(phrase: str) -> tuple[dict[int, int], bool]:
    """Обсуждения по годам, у которых фраза стоит в заголовке."""
    url = (
        "https://hn.algolia.com/api/v1/search"
        f"?query={urllib.parse.quote(phrase)}&tags=story&hitsPerPage=200"
        "&restrictSearchableAttributes=title"
    )
    body = _fetch("hn", url)
    if body is None:
        return {}, False
    try:
        payload = json.loads(body)
    except ValueError:
        return {}, False
    needle = phrase.lower()
    years: dict[int, int] = {}
    for hit in payload.get("hits", []):
        title = (hit.get("title") or "").lower()
        if needle not in title:
            # Алгоритм поиска допускает частичное совпадение, а нас интересует именно фраза:
            # «tool poisoning» и «poisoning» — разные утверждения.
            continue
        stamp = hit.get("created_at_i")
        if not isinstance(stamp, int):
            continue
        year = time.gmtime(stamp).tm_year
        years[year] = years.get(year, 0) + 1
    return years, True


def _wikipedia(phrase: str, language: str) -> tuple[tuple[int, int] | None, bool]:
    """Год создания статьи и её длина; ``None`` — статьи нет.

    Отсутствие статьи — сильный признак незрелости, а её наличие вместе с возрастом и размером —
    признак обратного. Поэтому различать «статьи нет» и «источник не ответил» обязательно: первое
    это факт о технологии, второе — о сети.
    """
    url = (
        f"https://{language}.wikipedia.org/w/api.php?action=query&format=json"
        "&prop=revisions|info&rvlimit=1&rvdir=newer&rvprop=timestamp&redirects=1"
        f"&titles={urllib.parse.quote(phrase)}"
    )
    body = _fetch("wikipedia", url)
    if body is None:
        return None, False
    try:
        payload = json.loads(body)
        pages = payload["query"]["pages"]
    except (ValueError, KeyError):
        return None, False
    page = next(iter(pages.values()))
    if "missing" in page:
        return None, True
    revisions = page.get("revisions") or [{}]
    stamp = str(revisions[0].get("timestamp") or "")
    year = int(stamp[:4]) if len(stamp) >= 4 and stamp[:4].isdigit() else 0
    return (year, int(page.get("length") or 0)), True


#: До скольких слов разрешено сокращать формулировку. Ниже двух фраза перестаёт называть
#: технологию и начинает называть область: «inference» — не «confidential inference».
_MINIMUM_PHRASE_WORDS: Final[int] = 2


def _resolve_phrase(phrase: str) -> tuple[str, dict[int, int], bool]:
    """Подобрать самую длинную фразу, которая хоть что-то находит.

    Каскад идёт только по OpenAlex, и это осознанное ограничение цены: один запрос туда стоит
    доли секунды, а тот же каскад по arXiv и GitHub упёрся бы в их ограничения частоты и
    растянул бы замер на часы. Найденная фраза дальше используется для всех источников разом.

    Порядок — от длинной к короткой, и останавливаемся на первой попавшей: длинная фраза
    специфичнее, а сокращение всегда рискует заменить технологию областью.
    """
    words = phrase.split()
    variants = [phrase]
    for cut in range(len(words) - 1, _MINIMUM_PHRASE_WORDS - 1, -1):
        variants.append(" ".join(words[:cut]))
    any_ok = False
    for variant in variants:
        years, ok = _openalex_years(variant)
        any_ok = any_ok or ok
        if ok and sum(years.values()) > 0:
            return variant, years, True
    return phrase, {}, any_ok


def _news(phrase: str) -> tuple[dict[int, int], tuple[str, ...], bool]:
    """Новости по точной фразе: число публикаций по годам и домены, где они вышли.

    GDELT просит не чаще одного запроса в пять секунд, и это самый дорогой источник замера. Он
    же — единственный, который видит раннее рыночное событие: раунд, выход из stealth, первый
    аудит. Академические базы о таком молчат по построению.
    """
    quoted = urllib.parse.quote(f'"{phrase}"')
    url = (
        "https://api.gdeltproject.org/api/v2/doc/doc"
        f"?query={quoted}&mode=artlist&format=json&timespan=36months&maxrecords=250"
    )
    body = _fetch("gdelt", url, attempts=1)
    if body is None:
        return {}, (), False
    try:
        payload = json.loads(body)
    except ValueError:
        # GDELT отвечает человекочитаемым текстом при превышении частоты — это не данные.
        return {}, (), False
    years: dict[int, int] = {}
    domains: set[str] = set()
    for article in payload.get("articles") or []:
        stamp = str(article.get("seendate") or "")
        if len(stamp) >= 4 and stamp[:4].isdigit():
            year = int(stamp[:4])
            years[year] = years.get(year, 0) + 1
        domain = str(article.get("domain") or "").strip().lower()
        if domain:
            domains.add(domain)
    return years, tuple(sorted(domains)), True


def collect_news(phrase: str) -> tuple[dict[int, int], tuple[str, ...], bool]:
    """Дозапросить только новости — для кэша, в котором этот источник отказал.

    Отдельная точка входа, а не флаг внутри общего сбора: остальные пять источников отвечают
    надёжно, и перезапрашивать их ради одного отказавшего значит платить за них второй раз.
    """
    return _news(phrase)


def collect_evidence(phrase: str) -> SourceEvidence:
    """Собрать всё, что открытые источники знают о технологии с таким названием."""
    effective, years, ok = _resolve_phrase(phrase)
    evidence = SourceEvidence(query=phrase, effective_query=effective)
    unavailable: list[str] = []

    evidence.works_by_year = years
    if not ok:
        unavailable.append("openalex")
    phrase = effective

    preprints, first_preprint, ok = _arxiv_count(phrase)
    evidence.preprints = preprints
    evidence.preprint_first_year = first_preprint
    if not ok:
        unavailable.append("arxiv")

    repositories, first_repository, ok = _github_repositories(phrase)
    evidence.repositories = repositories
    evidence.repository_first_year = first_repository
    if not ok:
        unavailable.append("github")

    stories, ok = _hacker_news(phrase)
    evidence.stories_by_year = stories
    if not ok:
        unavailable.append("hn")

    news_years, news_domains, ok = _news(phrase)
    evidence.news_by_year = news_years
    evidence.news_domains = news_domains
    if not ok:
        unavailable.append("gdelt")

    for language in ("en", "ru"):
        article, ok = _wikipedia(phrase, language)
        if article is not None:
            evidence.wikipedia[language] = article
        if not ok:
            unavailable.append(f"wikipedia.{language}")

    evidence.unavailable = tuple(unavailable)
    return evidence


def collect_maturity_evidence(phrase: str, *, works: bool = True) -> SourceEvidence:
    """Собрать только то, что отвечает на вопрос «не массовая ли уже технология».

    Два источника из шести: число работ в OpenAlex по годам и статья английской Википедии. Ровно
    на них стоит правило мейнстрима классификатора, проверенного на размеченном датасете, —
    остальные источники отвечают на другие вопросы (есть ли след, когда появился) и продукту на
    открытом запросе уже известны из собственного корпуса.

    Фраза не сокращается, в отличие от :func:`collect_evidence`: сокращение ищет, где у
    технологии есть **хоть какой-то** след, а здесь спрашивается, не слишком ли его **много**, и
    укороченная фраза («inference» вместо «confidential inference») дала бы мейнстрим там, где
    его нет.
    """
    evidence = SourceEvidence(query=phrase, effective_query=phrase)
    unavailable: list[str] = []
    # ``works=False`` — только Википедия. Поиск OpenAlex платный по суточному бюджету, и проверка
    # зрелости, спрашивающая его о каждой теме, за один-два анализа съедала бюджет, после чего
    # OpenAlex отказывал и самому сбору (разбор 88).
    if works:
        years, ok = _openalex_years(phrase)
        evidence.works_by_year = years
        if not ok:
            unavailable.append("openalex")
            # Запасной путь: число работ за три года из Semantic Scholar одной суммой на первый год
            # окна — правилу мейнстрима нужна именно сумма за окно.
            since = date.today().year - 2
            total, s2_ok = _semanticscholar_recent(phrase, since)
            if s2_ok and total is not None:
                evidence.works_by_year = {since: total}
    else:
        unavailable.append("openalex")
    article, ok = _wikipedia(phrase, "en")
    if article is not None:
        evidence.wikipedia["en"] = article
    if not ok:
        unavailable.append("wikipedia.en")
    evidence.unavailable = tuple(unavailable)
    return evidence
