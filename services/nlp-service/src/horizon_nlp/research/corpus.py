"""Собственный веб-корпус: страницы, собранные заранее по корзине вероятных запросов (разбор 110).

Открытых каталогов проекта мало для рыночной стадии: эталонные сигналы — раунды, выходы из
stealth, пилоты, — и их первоисточники лежат на сайтах компаний, в пресс-релизах и отраслевых
изданиях, которых нет ни в одном API. Поэтому такие страницы собраны заранее сборщиком
по шести направлениям и произвольным темам, а здесь по ним ищут.

**Граница та же, что у живого чтения.** Каждая страница корпуса скачана с проверкой
``robots.txt`` в момент сбора: запрет для всех или поимённо ИИ-краулерам — отказ, и такая
страница в корпус не попала. Обходных путей (архивы, кэши, зеркала) сборщик не использовал.
В записи хранятся адрес, дата, текст и отметка проверки ``robots``.

**Поиск — BM25 по заголовку и тексту** плюс совпадение с темой корзины: запрос «Финтех» — это
не слово в тексте, а направление, и под ним лежат все страницы, собранные для этого направления.
Индекс строится в памяти при первом обращении; корпус — несколько тысяч страниц, и на это
уходят секунды.

Файл — JSON Lines (``HORIZON_NLP_WEBCORPUS_PATH``). Нет файла — источник недоступен, и это
отказ, а не ноль.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import threading
from collections import Counter
from dataclasses import dataclass
from datetime import date
from itertools import pairwise
from pathlib import Path

from horizon_nlp.research.language import detect_language
from horizon_nlp.research.web import parse_date

__all__ = ["CorpusDocument", "WebCorpus", "corpus"]

_WORD = re.compile(r"[\w][\w\-]*", re.UNICODE)
_STOP = frozenset(
    ["a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "in", "into", "is", "it", "its", "of", "on", "or", "that", "the", "this", "to", "was", "were", "will", "with", "new", "how", "what", "why", "who", "your", "our", "their", "about", "more", "than", "after", "over", "also", "can", "и", "в", "во", "не", "на", "с", "со", "по", "к", "из", "за", "от", "до", "для", "что", "как", "это", "или", "а", "но", "о", "об", "при", "у", "же", "ли"]
)

#: Направления корзины и как их называют в запросах. Совпадение запроса с направлением отдаёт все
#: страницы направления, а не только те, где слово стоит в тексте.
TOPIC_ALIASES: dict[str, tuple[str, ...]] = {
    "Финтех": ("финтех", "fintech", "финансовые технологии", "financial technology", "金融科技"),
    "Защита ИИ": ("защита ии", "защита искусственного интеллекта", "безопасность искусственного интеллекта", "ai security", "безопасность ии", "security of ai", "ai safety security",
                  "人工智能安全"),
    "Индустриальный ИИ": ("индустриальный ии", "индустриальный искусственный интеллект",
                          "промышленный искусственный интеллект", "industrial ai", "промышленный ии", "ии в промышленности",
                          "工业人工智能"),
    "Роботы": ("роботы", "робототехника", "роботизация", "робот", "robots", "robotics", "机器人"),
    "Инфраструктура ИИ": ("инфраструктура ии", "инфраструктура искусственного интеллекта", "ai infrastructure",
                          "人工智能基础设施"),
    "Edge": ("edge", "edge ai", "edge computing", "периферийный ии", "периферийный искусственный интеллект", "граничные вычисления", "периферийные вычисления", "边缘计算"),
    # Темы корзины (разбор 110): без синонимов «квантовые вычисления» не находили тему «Квантовые
    # технологии», «open banking» — «Открытый банкинг», и заготовки на таких запросах молчали.
    "Открытый банкинг": ("open banking", "open finance", "открытые финансы", "открытые api банков"),
    "3D-печать": ("аддитивные технологии", "аддитивное производство", "additive manufacturing", "3d printing"),
    "Квантовые технологии": ("квантовые вычисления", "quantum computing", "квантовые компьютеры", "quantum technologies",
                             "квантовые сенсоры", "квантовая связь", "квантовая криптография"),
    "Кибербезопасность": ("cybersecurity", "информационная безопасность", "кибербез", "киберзащита"),
    "Цифровые двойники и IoT": ("iot", "интернет вещей", "цифровые двойники", "digital twins", "промышленный интернет вещей"),
    "Биотех и медтех": ("биотехнологии", "медтех", "медицинские технологии", "здравоохранение", "healthtech", "biotech",
                        "цифровое здоровье", "digital health"),
    "Транспорт и дроны": ("беспилотники", "беспилотные аппараты", "дроны", "беспилотный транспорт", "мобильность",
                          "автономный транспорт", "drones"),
    "Энергетика и климат": ("энергетика", "климатические технологии", "climate tech", "возобновляемая энергетика",
                            "водород", "накопители энергии", "энергопереход"),
    "Полупроводники": ("чипы", "микроэлектроника", "semiconductors", "микросхемы"),
    "Космос и связь": ("космос", "космические технологии", "спутники", "space tech", "спутниковая связь"),
    "Генеративный ИИ": ("генеративный искусственный интеллект", "genai", "generative ai", "большие языковые модели", "llm"),
    "Цифровые валюты": ("цифровой рубль", "cbdc", "криптовалюты", "стейблкоины", "цифровые активы"),
    "Регтех и антифрод": ("regtech", "антифрод", "противодействие мошенничеству", "fraud prevention", "комплаенс"),
    "Биометрия и цифровая идентичность": ("биометрия", "цифровая идентичность", "digital identity", "биометрическая идентификация"),
    "ИИ в банке": ("ии в банкинге", "искусственный интеллект в банке", "банковский ии", "ai in banking"),
    "Страхтех и управление капиталом": ("insurtech", "страхтех", "страхование", "wealthtech", "управление капиталом"),
    "Ритейл и логистика": ("ритейл", "логистика", "retail tech", "розница", "цепочки поставок"),
    "Агротех и новые материалы": ("агротех", "agritech", "сельское хозяйство", "новые материалы", "фудтех"),
    "Госуслуги, образование и HR": ("govtech", "госуслуги", "цифровое государство"),
    # Темы, добранные 29.09 после оценки запросов вне корзины.
    "Юридические технологии": ("legaltech", "легалтех", "правовые технологии", "юридический ии", "legal tech",
                               "технологии для юристов"),
    "Нейроинтерфейсы": ("нейротехнологии", "интерфейсы мозг-компьютер", "brain-computer interfaces", "bci",
                        "нейрокомпьютерные интерфейсы", "neurotech"),
    "Технологии для недвижимости": ("proptech", "недвижимость", "технологии недвижимости", "real estate tech"),
    "Строительные технологии": ("contech", "строительство", "технологии в строительстве", "construction tech"),
    "Умные города": ("умный город", "smart city", "smart cities", "городские технологии", "урбантех"),
    "AR/VR и пространственные вычисления": ("дополненная реальность", "виртуальная реальность", "ar vr", "xr",
                                            "пространственные вычисления", "метавселенные", "spatial computing"),
    "Образовательные технологии": ("edtech", "образование", "технологии в образовании", "цифровое образование"),
    "HR-технологии": ("hrtech", "hr", "управление персоналом", "рекрутинг", "технологии для hr"),
    "Маркетинговые и рекламные технологии": ("маркетинговые технологии", "martech", "adtech", "маркетинг", "реклама",
                                             "рекламные технологии"),
    "Медиа, игры и развлечения": ("медиа", "игры", "gaming", "развлечения", "медиа и развлечения", "геймдев"),
    "Нефтегазовые технологии": ("нефтегаз", "нефть и газ", "oil and gas", "нефтегазовая отрасль", "нефтегазовые технологии",
                                "технологии в нефтегазе"),
    "ESG и устойчивое развитие": ("esg", "устойчивое развитие", "зелёные финансы", "зеленые финансы", "углеродный рынок"),
    "Телеком и 6G": ("телеком", "телекоммуникации", "6g", "связь", "telecom"),
    "ИИ-агенты": ("ai agents", "агенты", "агентный ии", "agentic ai", "ии агенты", "автономные агенты"),
}


def _tokens(text: str) -> list[str]:
    return [t for t in (w.lower() for w in _WORD.findall(text)) if len(t) >= 2 and t not in _STOP]


#: Длина основы: «банкинг»/«банкинга», «payment»/«payments» совпадают, а русские окончания не
#: рвут фразу. Грубее стемминга, зато одинаково для всех языков корпуса.
_STEM = 6


def _stems(text: str) -> list[str]:
    return [t[:_STEM] for t in _tokens(text)]


def _normalize(text: str) -> str:
    return " ".join(_tokens(text))


def _topic_key(text: str) -> str:
    """Ключ темы запроса: основы слов по порядку — «открытого банкинга» = «Открытый банкинг»."""
    return " ".join(_stems(text))


def topic_index(extra_topics: set[str]) -> dict[str, str]:
    """Ключ → тема: шесть направлений с синонимами и все темы корзины, что есть в корпусе."""
    index: dict[str, str] = {}
    for topic in sorted(extra_topics):
        if topic:
            index.setdefault(_topic_key(topic), topic)
    for topic, aliases in TOPIC_ALIASES.items():
        for alias in (topic, *aliases):
            index[_topic_key(alias)] = topic
    return index


@dataclass(frozen=True, slots=True)
class CorpusDocument:
    """Страница корпуса такой, какой её сохранил сборщик."""

    url: str
    title: str
    text: str
    published: date | None
    host: str
    sitename: str | None
    language: str | None
    topic: str
    fetched_at: str
    robots: str
    sha256: str
    #: Сколько страниц-доказательств у самого подтверждённого сигнала, ссылающегося на страницу;
    #: ноль — страница не доказательство.
    evidence: int = 0


def _page_language(recorded: object, title: object, text: str) -> str | None:
    """Язык страницы: записанный сборщиком двухбуквенный код, иначе — определённый по тексту.

    Сборщик писал в поле ``lang`` строку ``"None"``, когда не знал языка; такая метка не язык.
    """
    code = str(recorded or "").strip().lower()
    if len(code) == 2 and code.isalpha():
        return code
    return detect_language(f"{title or ''}\n{text}")


class WebCorpus:
    """Индекс BM25 по страницам корпуса. Потокобезопасен на чтение после построения."""

    K1 = 1.4
    B = 0.75
    #: Заголовок — самое плотное место страницы: его слова считаются трижды.
    TITLE_WEIGHT = 3

    def __init__(self, documents: list[CorpusDocument]) -> None:
        """Построить индекс по страницам."""
        self.documents = documents
        self.by_url: dict[str, CorpusDocument] = {}
        for document in documents:
            self.by_url.setdefault(_canonical(document.url), document)
        self._postings: dict[str, list[tuple[int, int]]] = {}
        self._lengths: list[int] = []
        for index, document in enumerate(documents):
            counts = Counter(_stems(document.title) * self.TITLE_WEIGHT + _stems(document.text))
            self._lengths.append(sum(counts.values()))
            for term, count in counts.items():
                self._postings.setdefault(term, []).append((index, count))
        self._average = (sum(self._lengths) / len(self._lengths)) if self._lengths else 1.0
        self._topics = topic_index({document.topic for document in documents})

    @classmethod
    def load(cls, path: Path) -> WebCorpus:
        """Прочитать JSON Lines сборщика. Страницы без даты остаются: окно их просто не пропустит."""
        documents: list[CorpusDocument] = []
        seen: set[str] = set()
        with path.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                record = json.loads(line)
                url = str(record.get("url") or "")
                text = str(record.get("text") or "")
                if not url or len(text) < 200 or _canonical(url) in seen:
                    continue
                seen.add(_canonical(url))
                documents.append(
                    CorpusDocument(
                        url=url,
                        title=str(record.get("title") or ""),
                        text=text,
                        published=parse_date(record.get("published")),
                        host=str(record.get("host") or ""),
                        sitename=record.get("sitename"),
                        language=_page_language(record.get("lang"), record.get("title"), text),
                        topic=str(record.get("topic") or ""),
                        fetched_at=str(record.get("fetched_at") or ""),
                        robots=str(record.get("robots") or ""),
                        sha256=hashlib.sha256(text.encode("utf-8")).hexdigest(),
                        evidence=int(record.get("evidence") or 0),
                    )
                )
        return cls(documents)

    def topic_of(self, query: str) -> str | None:
        """Направление корзины, если запрос — само направление («Финтех», «edge AI»)."""
        return self._topics.get(_topic_key(query))

    def search(
        self, query: str, *, start: date, end: date, limit: int = 20
    ) -> list[tuple[CorpusDocument, float]]:
        """Лучшие страницы в окне дат.

        Запрос-направление отдаёт страницы направления: сначала те, на которые сборщики сослались
        как на доказательство сигнала, внутри — по свежести.
        """
        in_window = [
            i for i, d in enumerate(self.documents) if d.published is not None and start <= d.published <= end
        ]
        allowed = set(in_window)
        topic = self.topic_of(query)
        if topic is not None:
            hits = sorted(
                (i for i in in_window if self.documents[i].topic == topic),
                key=lambda i: (self.documents[i].evidence, self.documents[i].published or date.min),
                reverse=True,
            )
            return [(self.documents[i], 1.0) for i in hits[:limit]]
        scores: dict[int, float] = {}
        matched: Counter[int] = Counter()
        total = len(self.documents)
        sequence = _stems(query)
        terms = [t for t in dict.fromkeys(sequence) if t in self._postings]
        for term in terms:
            postings = self._postings[term]
            idf = math.log(1 + (total - len(postings) + 0.5) / (len(postings) + 0.5))
            for index, count in postings:
                if index not in allowed:
                    continue
                norm = count + self.K1 * (1 - self.B + self.B * self._lengths[index] / self._average)
                scores[index] = scores.get(index, 0.0) + idf * count * (self.K1 + 1) / norm
                matched[index] += 1
        # Слова запроса по отдельности — не запрос: «open banking» на странице, где есть «open
        # source» и «banking» в разных абзацах, — шум, и такой шум заполнял выдачу страницами про
        # ИИ-агентов. Короткий запрос обязан стоять на странице фразой (с точностью до окончаний),
        # длинный — всеми словами, кроме одного, и хотя бы одной парой соседних слов подряд.
        unique = list(dict.fromkeys(sequence))
        need = len(unique) if len(unique) <= 3 else len(unique) - 1
        candidates = [i for i, _ in sorted(scores.items(), key=lambda item: item[1], reverse=True)
                      if matched[i] >= min(need, len(terms))]
        ranked: list[tuple[CorpusDocument, float]] = []
        for index in candidates:
            if len(ranked) >= limit:
                break
            if len(sequence) >= 2 and not self._has_phrase(index, sequence):
                continue
            ranked.append((self.documents[index], scores[index]))
        return ranked

    def _has_phrase(self, index: int, sequence: list[str]) -> bool:
        """Фраза запроса на странице: целиком для 2–3 слов, парой соседних — для длинных."""
        document = self.documents[index]
        text = " " + " ".join(_stems(document.title + "\n" + document.text)) + " "
        if len(sequence) <= 3:
            return " " + " ".join(sequence) + " " in text
        return any(f" {a} {b} " in text for a, b in pairwise(sequence))

    def page(self, url: str) -> CorpusDocument | None:
        """Страница корпуса по адресу — прочитать без повторного обращения к сайту."""
        return self.by_url.get(_canonical(url))


def _canonical(url: str) -> str:
    return url.split("#")[0].rstrip("/").lower().replace("://www.", "://")


_lock = threading.Lock()
_loaded: dict[str, WebCorpus] = {}


def corpus(path: str) -> WebCorpus | None:
    """Корпус по пути настройки; ``None`` — файла нет (источник недоступен)."""
    if not path:
        return None
    file = Path(path)
    if not file.is_file():
        return None
    key = f"{file.resolve()}:{file.stat().st_mtime_ns}"
    with _lock:
        if key not in _loaded:
            _loaded.clear()
            _loaded[key] = WebCorpus.load(file)
        return _loaded[key]


@dataclass(frozen=True, slots=True)
class CorpusSignal:
    """Кандидат, которого сборщик назвал по прочитанным страницам, с адресами этих страниц."""

    name: str
    name_ru: str
    direction: str
    description_ru: str
    companies: tuple[str, ...]
    evidence: tuple[str, ...]
    #: Заранее размеченный вердикт по критериям жюри (разбор 110): ``True`` — «да», ``False`` —
    #: «нет», ``None`` — не размечен. Разметка офлайн, по тем же четырём критериям, что у жюри.
    approved: bool | None = None
    verdict_reason: str = ""


def load_signals(path: str) -> list[CorpusSignal]:
    """Сигналы сборщиков (``signals.jsonl`` рядом с корпусом); нет файла — пустой список."""
    if not path or not Path(path).is_file():
        return []
    out: list[CorpusSignal] = []
    with Path(path).open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            record = json.loads(line)
            evidence = tuple(str(u) for u in record.get("evidence") or [] if u)
            if not record.get("name") or not evidence:
                continue
            out.append(
                CorpusSignal(
                    name=str(record["name"]),
                    name_ru=str(record.get("name_ru") or ""),
                    direction=str(record.get("direction") or ""),
                    description_ru=str(record.get("description_ru") or ""),
                    companies=tuple(str(c) for c in record.get("companies") or []),
                    evidence=evidence,
                    approved=record["jury"] if isinstance(record.get("jury"), bool) else None,
                    verdict_reason=str(record.get("jury_reason") or ""),
                )
            )
    return out


def signals_for(signals: list[CorpusSignal], query: str, limit: int) -> list[CorpusSignal]:
    """Сигналы корпуса под запрос: всё направление, если запрос — направление, иначе по словам.

    Внутри направления первыми идут сигналы с большим числом страниц-доказательств: их
    подтвердили несколько источников. Вне направления — пересечение слов запроса с именем,
    описанием и компаниями; нужно больше половины слов запроса.

    Размеченные заранее «нет» не предлагаются вовсе, «да» идут первыми (разбор 110): в пуле
    ТОП-15 прогонов критерии жюри выполняли 16% позиций, среди сигналов корпуса — больше половины,
    и отобрать их заранее дешевле, чем надеяться, что их найдёт ранжирование.
    """
    signals = [s for s in signals if s.approved is not False]
    topic = topic_index({signal.direction for signal in signals}).get(_topic_key(query))
    chosen = [s for s in signals if s.direction == topic] if topic is not None else []
    if chosen:
        return sorted(chosen, key=lambda s: (not s.approved, -len(s.evidence)))[:limit]
    words = set(_stems(query))
    if not words:
        return []
    need = len(words) // 2 + 1 if len(words) >= 2 else 1
    scored = []
    for signal in signals:
        bag = set(_stems(" ".join((signal.name, signal.name_ru, signal.description_ru, *signal.companies))))
        hit = len(words & bag)
        if hit >= need:
            scored.append((hit, len(signal.evidence), signal))
    scored.sort(key=lambda item: (-item[0], not item[2].approved, -item[1]))
    return [signal for _, _, signal in scored[:limit]]
