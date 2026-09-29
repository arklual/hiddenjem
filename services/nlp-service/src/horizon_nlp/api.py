"""HTTP-граница сервиса вывода моделей.

Контракт узкий намеренно: аналитический движок должен уметь работать без этого сервиса.
Каждый ответ несёт имя модели, которая его произвела, — это требование ТЗ §3.1 о раскрытии
выбора модели для конкретного ответа, и на нём же держится отметка «машинный перевод» рядом
с источником в интерфейсе.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from datetime import date
from typing import Any, Literal

import structlog
from fastapi import FastAPI, HTTPException, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, Field

from horizon_nlp.config import settings
from horizon_nlp.generative import generative
from horizon_nlp.research import Budget, Window, build_research
from horizon_nlp.research.corpus import CorpusDocument, load_signals, signals_for
from horizon_nlp.research.corpus import corpus as web_corpus
from horizon_nlp.research.jury import run_jury
from horizon_nlp.research.panel import run_panel
from horizon_nlp.translation import translator

__all__ = ["app", "create_app"]

log = structlog.get_logger(__name__)


class TranslateRequest(BaseModel):
    """Строки на перевод. Порядок ответа совпадает с порядком запроса."""

    texts: list[str] = Field(default_factory=list, max_length=512)
    source: Literal["en", "ru"] = "en"
    target: Literal["ru", "en"] = "ru"


class TranslateResponse(BaseModel):
    """Переводы и раскрытие модели."""

    texts: list[str]
    model: str
    cached: int


class JudgeRequest(BaseModel):
    """Кандидаты на проверку «это название технологии?»."""

    candidates: list[str] = Field(default_factory=list, max_length=200)
    contexts: dict[str, list[str]] = Field(default_factory=dict)


class JudgeVerdict(BaseModel):
    """Суждение по одному кандидату. ``null`` — модель не ответила, тему оставить."""

    candidate: str
    # Имя поля контракта: граница JSON в camelCase, как во всех остальных схемах проекта.
    isTechnology: bool | None
    reason: str


class JudgeResponse(BaseModel):
    """Суждения и раскрытие модели."""

    verdicts: list[JudgeVerdict]
    model: str


class TranslateTermsRequest(BaseModel):
    """Названия технологий на перевод."""

    terms: list[str] = Field(default_factory=list, max_length=60)


class TranslateTermsResponse(BaseModel):
    """Переводы названий и раскрытие модели."""

    terms: list[str]
    model: str


class SearchPhraseRequest(BaseModel):
    """Развёрнутые русские формулировки технологий."""

    formulations: list[str] = Field(default_factory=list, max_length=60)


class SearchPhraseResponse(BaseModel):
    """Короткие английские поисковые фразы и раскрытие модели."""

    phrases: list[str]
    model: str


class ExpandQueryRequest(BaseModel):
    query: str = Field(min_length=1, max_length=200)
    targets: list[str] = Field(default_factory=list, max_length=64)
    limit: int = Field(default=12, ge=1, le=30)
    #: Языки запросов к источникам; английский есть всегда.
    languages: list[Literal["en", "ru", "zh"]] = Field(
        default_factory=lambda: ["en"],  # type: ignore[arg-type]  # список литералов — тот же тип
        max_length=3,
    )


class ExpandedQuery(BaseModel):
    query: str
    group: str
    language: str = "en"


class TranslateDocumentsItem(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    title: str = Field(default="", max_length=2000)
    abstract: str = Field(default="", max_length=20000)


class TranslateDocumentsRequest(BaseModel):
    """Заголовки и аннотации документов для перевода на английский перед анализом."""

    items: list[TranslateDocumentsItem] = Field(min_length=1, max_length=40)


class TranslateDocumentsResponse(BaseModel):
    """Переведённые документы (не переведённых в ответе нет) и раскрытие модели."""

    items: list[TranslateDocumentsItem]
    model: str


class ExpandQueryResponse(BaseModel):
    queries: list[ExpandedQuery]
    model: str


class ProposeTechnologiesRequest(BaseModel):
    """Направление, для которого нужны имена технологий ранней стадии."""

    query: str = Field(min_length=1, max_length=200)
    limit: int = Field(default=60, ge=1, le=120)


class ProposeTechnologiesResponse(BaseModel):
    """Имена и раскрытие модели.

    Имя модели обязательно и по той же причине, что у всех остальных ответов: аналитик обязан
    знать, чьё это предложение. Само предложение выдачей не становится — движок публикует имя
    только после того, как открытые источники его подтвердили (ТЗ §3.1).
    """

    technologies: list[str]
    model: str
    #: Адреса страниц веб-корпуса, на которых сборщик нашёл имя (разбор 110). Только у имён из
    #: корпуса; у имён от модели доказательств нет — их ищут внешние источники движка.
    evidence: dict[str, list[str]] = Field(default_factory=dict)
    #: Описание имени по его страницам (тренд панели или описание сборщика) — определение карточки.
    details: dict[str, str] = Field(default_factory=dict)
    #: Имена, заранее размеченные «да» по критериям жюри (разбор 110): движок ставит их в голову
    #: списка, переранжирование не снимает, экспертная стадия получает готовый вердикт.
    approved: list[str] = Field(default_factory=list)


class SummarizeRequest(BaseModel):
    """Тема и фрагменты источников, по которым нужно русское резюме."""

    topic: str
    fragments: list[str] = Field(default_factory=list, max_length=20)


class SummarizeResponse(BaseModel):
    """Резюме, модель и признак опоры на переданные фрагменты."""

    text: str
    model: str
    grounded: bool


class TrendItem(BaseModel):
    """Одна тема отчёта: термин, факты и фрагменты источников, по которым пишется тренд."""

    direction: str = ""
    term: str
    title: str | None = None
    stage: str | None = None
    firstYear: int | None = None
    series: str | None = None
    fragments: list[str] = Field(default_factory=list, max_length=8)


class TrendStatementsRequest(BaseModel):
    """Все темы отчёта одним запросом: пятнадцать вызовов стоили бы пятнадцать прогревов."""

    items: list[TrendItem] = Field(default_factory=list, max_length=60)


class TrendStatementsResponse(BaseModel):
    """Предложение-тренд на каждую тему по порядку; ``None`` — модель не справилась честно."""

    statements: list[str | None]
    model: str


class DeepResearchRequest(BaseModel):
    """Направление, окно наблюдения и потолки прогона (не выше настроек сервиса)."""

    query: str = Field(min_length=1, max_length=200)
    targets: list[str] = Field(default_factory=list, max_length=32)
    windowFrom: date
    windowTo: date
    maxIterations: int | None = Field(default=None, ge=1, le=120)
    maxFetches: int | None = Field(default=None, ge=1, le=100)
    timeBudgetSeconds: float | None = Field(default=None, ge=30, le=1800)
    minSources: int = Field(default=10, ge=1, le=50)


class DeepResearchDocument(BaseModel):
    """Прочитанная страница, подтвердившая хотя бы одно имя, — будущий документ корпуса."""

    url: str
    title: str
    publishedOn: date
    sourceClass: str
    origin: str
    via: str
    host: str
    language: str
    excerpt: str
    sha256: str
    fetchedAt: str
    httpStatus: int
    authors: list[str]
    organization: str | None
    organizationIsCompany: bool
    doi: str | None
    arxivId: str | None
    venue: str | None
    readReason: str
    technologies: list[str]


class DeepResearchResponse(BaseModel):
    """Итог исследования. ``status=failed`` — отказ источника, а не «ничего не найдено»."""

    status: Literal["ok", "failed"]
    model: str
    documents: list[DeepResearchDocument]
    technologies: list[dict[str, object]]
    stats: dict[str, object]
    trace: list[dict[str, object]]
    report: str


class WebCorpusSearchRequest(BaseModel):
    """Формулировки направления и окно: поиск по собственному веб-корпусу без модели."""

    query: str = Field(min_length=1, max_length=200)
    targets: list[str] = Field(default_factory=list, max_length=32)
    windowFrom: date
    windowTo: date
    limit: int = Field(default=60, ge=1, le=300)


class JuryItem(BaseModel):
    """Позиция для экспертов: то же, что увидит жюри."""

    title: str
    definition: str = ""
    sources: list[dict[str, str]] = Field(default_factory=list)


class JuryRequest(BaseModel):
    """Запрос и позиции верхушки."""

    query: str = Field(min_length=1, max_length=200)
    items: list[JuryItem] = Field(max_length=60)


class JuryResponse(BaseModel):
    """Голоса экспертов по каждой позиции (номер с 1), раскрытие модели и время."""

    verdicts: list[dict[str, object]]
    model: str
    stats: dict[str, object]


class RerankRequest(BaseModel):
    """Верхушка кандидатов для переранжирования по запросу."""

    query: str = Field(min_length=1, max_length=200)
    titles: list[str] = Field(max_length=80)
    contexts: list[str] = Field(default_factory=list, max_length=80)


class RerankResponse(BaseModel):
    """Порядок (номера с 1, лучший первым), снимаемые номера с причиной и раскрытие модели."""

    order: list[int]
    remove: dict[int, str]
    model: str


class WebCorpusDocumentsRequest(BaseModel):
    """Адреса страниц корпуса, которые нужны движку как доказательства."""

    urls: list[str] = Field(default_factory=list, max_length=500)


#: Имена последних глубоких исследований по запросу (разбор 110): «имя → адреса страниц, где оно
#: подтверждено текстом». Сбор документов их отбрасывал — у документа остаётся только текст, — и
#: по «нейроинтерфейсам» 37 прочитанных агентом имён не дошли до отчёта, а эксперты не нашли в нём
#: ни одного продуктового сигнала. Исследование идёт на этапе сбора, предложение имён — на этапе
#: анализа того же запроса в этом же процессе.
_RESEARCH_NAMES: dict[str, dict[str, list[str]]] = {}
_RESEARCH_NAMES_KEPT = 64


def _query_key(query: str) -> str:
    return " ".join(query.lower().split())


def remember_research(query: str, documents: Sequence[Any]) -> int:
    """Запомнить имена исследования с адресами подтверждающих страниц; вернуть число имён."""
    names: dict[str, list[str]] = {}
    for document in documents:
        for name in getattr(document, "technologies", ()) or ():
            if name and document.url not in names.setdefault(name, []):
                names[name].append(document.url)
    if names:
        _RESEARCH_NAMES.pop(_query_key(query), None)
        _RESEARCH_NAMES[_query_key(query)] = names
        while len(_RESEARCH_NAMES) > _RESEARCH_NAMES_KEPT:
            _RESEARCH_NAMES.pop(next(iter(_RESEARCH_NAMES)))
    return len(names)


def research_names(query: str) -> dict[str, list[str]]:
    """Имена последнего исследования этого запроса (пусто — исследования не было)."""
    return _RESEARCH_NAMES.get(_query_key(query), {})


def create_app() -> FastAPI:
    """Собрать приложение."""
    structlog.configure(
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelNamesMapping()[settings.log_level.upper()]
        )
    )
    application = FastAPI(
        title="Horizon NLP",
        version="1.0.0",
        description="Локальный вывод моделей: перевод на русский и семантический фильтр кандидатов",
    )

    @application.get("/metrics")
    def metrics() -> Response:
        """Метрики в формате Prometheus.

        Служба, которую никто не опрашивает, отказывает молча — а эта отказывает особенно тихо:
        её отказ означает отчёт без русского слоя и без семантического фильтра, то есть выдачу,
        которая выглядит нормальной. Поэтому метрики есть с первого дня, а не «когда понадобятся».
        """
        return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @application.get("/health/live")
    def live() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/health/ready")
    def ready() -> dict[str, object]:
        """Готовность различает перевод и генерацию: они отказывают независимо."""
        return {
            "status": "UP",
            "translationModels": [
                settings.translate_en_ru_model,
                settings.translate_ru_en_model,
            ],
            "generativeModel": settings.generative_model,
            "generativeBackend": settings.generative_backend,
        }

    @application.post("/translate", response_model=TranslateResponse)
    def translate(request: TranslateRequest) -> TranslateResponse:
        # На русский — генеративной моделью: русский слой отчёта читает человек, и машинный
        # переводчик слово в слово портил в нём термины и целые предложения. Прочие направления
        # (служебные, для поиска) остаются за переводчиком: там важна скорость, а не слог.
        if request.target == "ru" and settings.generative_backend == "openai":
            texts, model = generative.translate_russian(request.texts)
            return TranslateResponse(texts=list(texts), model=model, cached=0)
        result = translator.translate(
            request.texts, source=request.source, target=request.target
        )
        return TranslateResponse(
            texts=list(result.texts), model=result.model, cached=result.cached
        )

    @application.post("/judge/technology", response_model=JudgeResponse)
    def judge(request: JudgeRequest) -> JudgeResponse:
        verdicts = generative.judge_technologies(request.candidates, request.contexts)
        return JudgeResponse(
            verdicts=[
                JudgeVerdict(
                    candidate=v.candidate, isTechnology=v.is_technology, reason=v.reason
                )
                for v in verdicts
            ],
            model=generative.model,
        )

    @application.post("/translate/terms", response_model=TranslateTermsResponse)
    def translate_terms(request: TranslateTermsRequest) -> TranslateTermsResponse:
        terms, model = generative.translate_terms(request.terms)
        return TranslateTermsResponse(terms=list(terms), model=model)

    @application.post("/expand-query", response_model=ExpandQueryResponse)
    def expand_query(request: ExpandQueryRequest) -> ExpandQueryResponse:
        """Узкие поисковые запросы по подтемам и стыкам направления — для сбора корпуса."""
        queries, model = generative.expand_query(
            request.query, request.targets, request.limit, request.languages
        )
        return ExpandQueryResponse(
            queries=[ExpandedQuery(query=q, group=g, language=lang) for q, g, lang in queries],
            model=model,
        )

    @application.post("/propose-technologies", response_model=ProposeTechnologiesResponse)
    def propose_technologies(
        request: ProposeTechnologiesRequest,
    ) -> ProposeTechnologiesResponse:
        """Имена технологий направления на ранней стадии — второй источник кандидатов движка.

        Отличается от ``/expand-query`` предметом: там — что спросить у каталогов, чтобы собрать
        корпус, здесь — как называется сама технология, которую потом будут проверять по внешним
        источникам. Отказ модели — пустой список, а не ошибка: движок считает по корпусу.
        """
        evidence: dict[str, list[str]] = {}
        details: dict[str, str] = {}
        corpus_signals = signals_for(load_signals(settings.webcorpus_signals_path), request.query, request.limit)
        # Сигналы, размеченные заранее «да» по критериям жюри, — первыми, раньше панели (разбор 110).
        for signal in corpus_signals:
            if signal.approved and len(evidence) < request.limit:
                evidence.setdefault(signal.name, list(signal.evidence))
                if signal.description_ru:
                    details.setdefault(signal.name, signal.description_ru)
        # Имена глубокого исследования этого запроса — со страницами, где агент их прочитал: на
        # запросе без заготовок в корпусе это единственные рыночные кандидаты с доказательствами.
        found = research_names(request.query)
        for name, urls in found.items():
            if len(evidence) >= request.limit:
                break
            evidence.setdefault(name, urls[:8])
        if found:
            log.info("propose.research", query=request.query, names=len(found))
        index = web_corpus(settings.webcorpus_path)
        # Одобренные заняли весь лимит — имена панели были бы отброшены, а она стоит около минуты.
        if settings.panel_enabled and index is not None and len(evidence) < request.limit:
            today = date.today()
            try:
                panel, stats = run_panel(
                    index, generative.complete_text, request.query,
                    start=date(today.year - 7, 1, 1), end=today, limit=min(settings.panel_limit, request.limit),
                )
                for c in panel:
                    evidence.setdefault(c.name, list(c.evidence))
                    if c.trend_ru:
                        details.setdefault(c.name, c.trend_ru)
                log.info("propose.panel", query=request.query, names=len(panel), seconds=stats["seconds"])
            except Exception as error:  # панель — дополнение: её отказ не роняет предложение имён
                log.warning("propose.panel_failed", error=str(error))
        # Сигналы корпуса — на весь лимит: у них есть страницы-доказательства, а у имён модели нет.
        # Разбор промахов Edge (29.09): спутниковый edge, федеративное обучение и вычисления в
        # сенсоре были в корпусе на 75–119 местах и не доходили до движка при доле в две трети.
        for signal in corpus_signals:
            if len(evidence) >= request.limit:
                break
            evidence.setdefault(signal.name, list(signal.evidence))
            if signal.description_ru:
                details.setdefault(signal.name, signal.description_ru)
        rest = request.limit - len(evidence)
        names, model = generative.propose_technologies(request.query, rest) if rest > 0 else ((), "none")
        merged = list(dict.fromkeys([*evidence, *names]))[: request.limit]
        if evidence:
            log.info("propose.webcorpus", query=request.query, corpus=len(evidence), model_names=len(names))
            model = f"{model}+webcorpus"
        approved = [s.name for s in corpus_signals if s.approved and s.name in merged]
        return ProposeTechnologiesResponse(
            technologies=merged, model=model, evidence=evidence, details=details, approved=approved
        )

    @application.post("/deep-research", response_model=DeepResearchResponse)
    def deep_research(request: DeepResearchRequest) -> DeepResearchResponse:
        """Глубокое исследование направления: агент ищет, читает целиком и записывает имена.

        Ответ — не выдача, а прочитанные документы: каждый подтверждает хотя бы одно имя
        технологии дословным текстом страницы, полученной в этом прогоне. Сервис сбора делает из
        них документы корпуса, и дальше они проходят тот же конвейер, что и документы остальных
        источников: доверенность, BRULE-1, скоринг (ТЗ §3.1). Отчёт модели возвращается для
        аудита и свидетельством не является.
        """
        if not generative.supports_tools:
            raise HTTPException(
                status_code=503,
                detail="глубокое исследование требует HORIZON_NLP_GENERATIVE_BACKEND=openai",
            )
        if request.windowFrom > request.windowTo:
            raise HTTPException(status_code=422, detail="windowFrom позже windowTo")
        budget = Budget(
            max_iterations=min(request.maxIterations or settings.research_max_iterations,
                               settings.research_max_iterations),
            max_fetches=min(request.maxFetches or settings.research_max_fetches,
                            settings.research_max_fetches),
            time_budget_seconds=min(request.timeBudgetSeconds or settings.research_time_budget_seconds,
                                    settings.research_time_budget_seconds),
            min_sources=request.minSources,
        )
        research = build_research(generative, generative.model)
        result = research.run(
            request.query, request.targets, Window(request.windowFrom, request.windowTo), budget
        )
        if result.status == "ok":
            remember_research(request.query, result.documents)
        return DeepResearchResponse(
            status="ok" if result.status == "ok" else "failed",
            model=generative.model,
            documents=[
                DeepResearchDocument(
                    url=d.url,
                    title=d.title,
                    publishedOn=d.published_on,
                    sourceClass=d.source_class,
                    origin=d.origin,
                    via=d.via,
                    host=d.host,
                    language=d.language,
                    excerpt=d.excerpt,
                    sha256=d.sha256,
                    fetchedAt=d.fetched_at,
                    httpStatus=d.http_status,
                    authors=list(d.authors),
                    organization=d.organization,
                    organizationIsCompany=d.organization_is_company,
                    doi=d.doi,
                    arxivId=d.arxiv_id,
                    venue=d.venue,
                    readReason=d.read_reason,
                    technologies=list(d.technologies),
                )
                for d in result.documents
            ],
            technologies=result.technologies,
            stats=result.stats,
            trace=result.trace,
            report=result.report,
        )

    @application.post("/webcorpus/search", response_model=DeepResearchResponse)
    def webcorpus_search(request: WebCorpusSearchRequest) -> DeepResearchResponse:
        """Страницы собственного веб-корпуса по направлению (разбор 110).

        Ответ — в той же форме, что у глубокого исследования: сервис сбора делает из страниц
        документы корпуса тем же разбором. Модели здесь нет: поиск BM25 и совпадение с темой
        корзины, поэтому ответ приходит за секунды и не зависит от ключа модели.
        """
        index = web_corpus(settings.webcorpus_path)
        if index is None:
            return DeepResearchResponse(
                status="failed", model="bm25", documents=[], technologies=[],
                stats={"reason": "веб-корпус не подключён (HORIZON_NLP_WEBCORPUS_PATH)"},
                trace=[], report="",
            )
        phrases = [request.query, *[t for t in request.targets if t.strip()]]
        found: dict[str, tuple[CorpusDocument, float, str]] = {}
        per_phrase = max(10, request.limit // max(1, len(phrases)))
        for phrase in phrases:
            for document, score in index.search(
                phrase, start=request.windowFrom, end=request.windowTo, limit=per_phrase
            ):
                if document.url not in found or found[document.url][1] < score:
                    found[document.url] = (document, score, phrase)
        ranked = sorted(found.values(), key=lambda item: item[1], reverse=True)[: request.limit]
        documents = []
        for d, score, phrase in ranked:
            documents.append(
                DeepResearchDocument(
                    url=d.url, title=d.title or d.url, publishedOn=d.published, sourceClass="NEWS",
                    origin="webcorpus", via="webcorpus", host=d.host, language=(d.language or "en")[:2],
                    excerpt=d.text[:4000], sha256=d.sha256, fetchedAt=d.fetched_at, httpStatus=200,
                    authors=[d.sitename or d.host], organization=d.host, organizationIsCompany=True,
                    doi=None, arxivId=None, venue=d.sitename or d.host,
                    readReason=f"веб-корпус: «{phrase}», BM25 {score:.2f}; robots.txt: {d.robots}",
                    technologies=[],
                )
            )
        log.info("webcorpus.search", query=request.query, phrases=len(phrases), documents=len(documents),
                 corpus=len(index.documents))
        return DeepResearchResponse(
            status="ok", model="bm25", documents=documents, technologies=[],
            stats={"phrases": len(phrases), "documentsEmitted": len(documents), "corpusSize": len(index.documents)},
            trace=[], report="",
        )

    @application.post("/jury", response_model=JuryResponse)
    def jury(request: JuryRequest) -> JuryResponse:
        """Три эксперта голосуют «да/нет» по критериям жюри — последняя стадия отбора в финал."""
        model = settings.jury_model or generative.model
        # Темы из размеченных заранее сигналов корпуса получают готовый вердикт (разбор 110):
        # разметка сделана по тем же критериям жюри на страницах-доказательствах, и спрашивать
        # о них модель значило бы заменить более строгое суждение менее строгим.
        labelled = {
            signal.name.lower(): signal
            for signal in load_signals(settings.webcorpus_signals_path)
            if signal.approved is not None
        }
        prejudged = {
            n: labelled[item.title.strip().lower()]
            for n, item in enumerate(request.items, 1)
            if item.title.strip().lower() in labelled
        }
        rest = [(n, item) for n, item in enumerate(request.items, 1) if n not in prejudged]
        verdicts: list[dict[str, object]] = []
        stats: dict[str, object] = {"seconds": 0.0, "experts": 0, "items": 0}
        if rest:
            asked, stats = run_jury(
                lambda system, user, tokens: generative.complete_with(model, system, user, tokens),
                request.query,
                [item.model_dump() for _, item in rest],
            )
            verdicts = [{**verdict, "n": n} for (n, _), verdict in zip(rest, asked, strict=True)]
        for n, signal in prejudged.items():
            verdicts.append({
                "n": n,
                "yes": 3 if signal.approved else 0,
                "votes": {"corpus_labels": [bool(signal.approved), signal.verdict_reason[:120]]},
            })
        verdicts.sort(key=lambda verdict: int(str(verdict["n"])))
        if prejudged:
            model = f"{model}+corpus-labels"
            log.info("jury.prejudged", query=request.query, prejudged=len(prejudged), asked=len(rest))
        return JuryResponse(verdicts=verdicts, model=model, stats={**stats, "prejudged": len(prejudged)})

    @application.post("/rerank-trends", response_model=RerankResponse)
    def rerank_trends(request: RerankRequest) -> RerankResponse:
        """Упорядочить верхушку по запросу и ранней стадии; отказ модели — пустой порядок."""
        order, remove, model = generative.rerank_trends(request.query, request.titles, request.contexts)
        return RerankResponse(order=order, remove=remove, model=model)

    @application.post("/panel")
    def panel(request: WebCorpusSearchRequest) -> dict[str, object]:
        """Панель экспертов по подсферам — для отладки и замеров (разбор 110)."""
        index = web_corpus(settings.webcorpus_path)
        if index is None:
            raise HTTPException(status_code=503, detail="веб-корпус не подключён")
        candidates, stats = run_panel(
            index, generative.complete_text, request.query,
            start=request.windowFrom, end=request.windowTo, limit=min(request.limit, 60),
        )
        return {
            "model": generative.model,
            "candidates": [
                {"name": c.name, "nameRu": c.name_ru, "trendRu": c.trend_ru, "subsphere": c.subsphere,
                 "companies": list(c.companies), "evidence": list(c.evidence)}
                for c in candidates
            ],
            "stats": stats,
        }

    @application.post("/webcorpus/documents", response_model=DeepResearchResponse)
    def webcorpus_documents(request: WebCorpusDocumentsRequest) -> DeepResearchResponse:
        """Страницы корпуса по адресам — доказательная база имён панели для движка."""
        index = web_corpus(settings.webcorpus_path)
        if index is None:
            return DeepResearchResponse(status="failed", model="bm25", documents=[], technologies=[],
                                        stats={}, trace=[], report="")
        documents = []
        for url in request.urls:
            d = index.page(url)
            if d is None or d.published is None:
                continue
            documents.append(DeepResearchDocument(
                url=d.url, title=d.title or d.url, publishedOn=d.published, sourceClass="NEWS",
                origin="webcorpus", via="webcorpus", host=d.host, language=(d.language or "en")[:2],
                excerpt=d.text[:4000], sha256=d.sha256, fetchedAt=d.fetched_at, httpStatus=200,
                authors=[d.sitename or d.host], organization=d.host, organizationIsCompany=True,
                doi=None, arxivId=None, venue=d.sitename or d.host,
                readReason=f"веб-корпус; robots.txt: {d.robots}", technologies=[],
            ))
        return DeepResearchResponse(status="ok", model="bm25", documents=documents, technologies=[],
                                    stats={"requested": len(request.urls), "found": len(documents)},
                                    trace=[], report="")

    @application.post("/translate/documents", response_model=TranslateDocumentsResponse)
    def translate_documents(request: TranslateDocumentsRequest) -> TranslateDocumentsResponse:
        """Перевод заголовков и аннотаций на английский — для документов на русском и китайском."""
        try:
            items, model = generative.translate_documents(
                [(item.id, item.title, item.abstract) for item in request.items]
            )
        except Exception as error:
            # Отказ модели — не отказ сбора: документы останутся без перевода до следующего раза.
            raise HTTPException(status_code=503, detail=f"перевод недоступен: {error}") from error
        return TranslateDocumentsResponse(
            items=[TranslateDocumentsItem(id=i, title=t, abstract=a) for i, t, a in items],
            model=model,
        )

    @application.post("/search-phrase", response_model=SearchPhraseResponse)
    def search_phrase(request: SearchPhraseRequest) -> SearchPhraseResponse:
        phrases, model = generative.search_phrases(request.formulations)
        return SearchPhraseResponse(phrases=list(phrases), model=model)

    @application.post("/summarize", response_model=SummarizeResponse)
    def summarize(request: SummarizeRequest) -> SummarizeResponse:
        summary = generative.summarize_russian(
            topic=request.topic, fragments=request.fragments
        )
        return SummarizeResponse(
            text=summary.text, model=summary.model, grounded=summary.grounded
        )

    @application.post("/trend-statements", response_model=TrendStatementsResponse)
    def trend_statements(request: TrendStatementsRequest) -> TrendStatementsResponse:
        statements, model = generative.formulate_trends(
            [item.model_dump() for item in request.items]
        )
        return TrendStatementsResponse(statements=list(statements), model=model)

    @application.on_event("startup")
    def preload() -> None:
        """Прогреть переводчик заранее: иначе первый живой запрос платит за загрузку весов."""
        if not settings.preload_translation:
            return
        try:
            translator.translate(["warm up"], source="en", target="ru")
            translator.translate(["прогрев"], source="ru", target="en")
            log.info("nlp.preloaded")
        except Exception as error:
            log.warning("nlp.preload_failed", error=str(error))

    return application


app = create_app()
