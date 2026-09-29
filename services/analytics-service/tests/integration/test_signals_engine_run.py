"""Прогон движка `signals` целиком: эталонный корпус, предзаполненный кэш, ноль запросов в сеть.

Почему кэш, а не подмена источника. Движок собран своей же фабрикой, со своим адаптером и своим
сбором: между проверкой и живым анализом нет ни одной подменённой детали, кроме той, что ответы
источников уже лежат на диске. Это ровно тот режим, в котором сбор задуман (`cache_only`):
собранное берётся из кэша, несобранное честно отмечается отказом. Подменив вместо этого сам
сборщик, мы проверили бы путь, которым продукт не ходит.

Что здесь доказывается и не доказывается нигде больше:

* общая часть двух движков — одна и та же: корпус, кандидаты, слияние, направление;
* балл берётся из модели, а порядок тем — из балла;
* тема, о которой источники промолчали, остаётся в отчёте и помечена;
* отчёт второго движка проходит тот же контракт, что и отчёт методологии.
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from datetime import date
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.adapters.nlp.http import HttpNlpClient, HttpTechnologyProposer
from horizon_analytics.adapters.signals.live import LiveSignalsProbe
from horizon_analytics.application.dto import build_domain_analyzed
from horizon_analytics.domain.engines import EngineContext, SignalsConfiguration, build_engine
from horizon_analytics.domain.models import AnalysisParams
from horizon_analytics.domain.pipeline import PipelineRequest, PipelineResult
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.domain.signal_scoring import (
    CORPUS_FEATURES,
    FEATURES,
    external_search_phrase,
)
from horizon_analytics.signals.cache import SignalCache
from horizon_analytics.signals.models import SourceResult
from horizon_analytics.signals.sources import hackernews, openalex, wikipedia

CORPUS = Path(__file__).resolve().parents[4] / "fixtures" / "corpus" / "documents.jsonl"
SCHEMAS = Path(__file__).resolve().parents[4] / "contracts" / "schemas"
MODEL = Path(__file__).resolve().parents[1] / "fixtures" / "signals_model.json"
TODAY = date(2026, 1, 1)
#: Годы, за которые лежат ответы в кэше, — то же окно, что у сбора.
YEARS = tuple(range(2018, 2027))


def request_for(top_n: int = 15) -> PipelineRequest:
    """Тот же запрос, которым проверяется контракт события, — чтобы числа были сравнимы.

    ``top_n`` шире там, где проверяется второй источник кандидатов: предложенная тема
    соревнуется с корпусными на общих основаниях и в пятнадцать строк попадает не всегда. Широкая
    выдача здесь — способ увидеть её место, а не поблажка: правила отсева те же.
    """
    assert CORPUS.is_file(), f"эталонный корпус не найден: {CORPUS}"
    return PipelineRequest(
        normalized_query="artificial intelligence machine learning",
        query="artificial intelligence machine learning",
        documents=tuple(load_documents(CORPUS)),
        params=AnalysisParams(top_n=top_n, years_window=8),
        profile=MethodologyProfile.default(),
        window_from=date(2018, 1, 1),
        window_to=date(2025, 12, 31),
        today=TODAY,
    )


def prefill(cache_dir: Path, phrases: Sequence[str]) -> None:
    """Положить в кэш ответы трёх быстрых источников — по одному на фразу.

    Числа взяты правдоподобные и **разные**: одинаковые сделали бы проверку порядка тем
    бессодержательной, а именно порядок здесь и проверяется.
    """
    cache = SignalCache(cache_dir)
    for index, phrase in enumerate(phrases):
        weight = index + 1
        works = {str(year): weight * (year - 2017) for year in YEARS}
        total = sum(works.values())
        cache.put(
            phrase,
            SourceResult(
                source=openalex.NAME,
                ok=True,
                collected_on=TODAY,
                version=openalex.VERSION,
                data={
                    "works_by_year": works,
                    "works_window_total": total,
                    "works_all_time": total * 2,
                    "recent_two_year_share": (works["2025"] + works["2026"]) / total,
                    "recent_two_year_works": works["2025"] + works["2026"],
                    "distinct_institutions": 12 * weight,
                    "distinct_institutions_truncated": False,
                    "distinct_countries": 4 * weight,
                    "distinct_countries_truncated": False,
                    "top_institution": "Acme Research Lab",
                    "top_institution_share": 0.3 / weight,
                },
            ),
        )
        mentions = {str(year): weight * max(0, year - 2020) for year in YEARS}
        mentioned = sum(mentions.values())
        cache.put(
            phrase,
            SourceResult(
                source=hackernews.NAME,
                ok=True,
                collected_on=TODAY,
                version=hackernews.VERSION,
                data={
                    "mentions_by_year": mentions,
                    "mentions_window_total": mentioned,
                    "recent_two_year_share": (
                        (mentions["2025"] + mentions["2026"]) / mentioned if mentioned else None
                    ),
                    "extra_year_queries": 0,
                },
            ),
        )
        cache.put(
            phrase,
            SourceResult(
                source=wikipedia.NAME,
                ok=True,
                collected_on=TODAY,
                version=wikipedia.VERSION,
                data={
                    "exists": True,
                    "title": phrase,
                    "created_on": "2021-05-01",
                    "size_bytes": 14_000 * weight,
                    "age_days": 1_000 + 100 * weight,
                    "pageviews_12m": 50_000 * weight,
                    "pageview_months": 12,
                },
            ),
        )


#: Имена, которых в эталонном корпусе нет ни разу, — то самое, ради чего заведён второй
#: источник кандидатов: технология есть, а в корпус она не попала.
PROPOSED = ("photonic processors", "analog compute-in-memory")
#: Имя, которое модель назовёт, а источники не подтвердят: граница ТЗ §3.1 в прогоне целиком.
UNCONFIRMED = "quantum telepathy accelerator"


class Proposer:
    """Модель, называющая технологии направления. Реализация порта, а не подмена сети."""

    @property
    def model_id(self) -> str:
        return "fixture-model"

    def propose(self, direction: str, limit: int) -> Sequence[str]:
        return (*PROPOSED, UNCONFIRMED)[:limit]


def prefill_works(cache_dir: Path, phrases: Sequence[str]) -> None:
    """Положить в кэш по четыре настоящих по форме работы на фразу.

    Четыре и из четырёх организаций — ровно чтобы пройти BRULE-1 (не меньше двух документов из не
    меньше чем двух организаций): предложенная тема проходит то же правило достоверности, что и
    корпусная, а не льготное.
    """
    cache = SignalCache(cache_dir)
    for phrase in phrases:
        works = [
            {
                "id": f"W{index}{abs(hash(phrase)) % 1000}",
                "doi": f"10.0000/{index}",
                "title": f"{phrase}: measured study {index}",
                "published_on": f"{year}-06-0{index + 1}",
                "type": "article",
                "cited_by_count": 10 - index,
                "venue": "Journal of Measured Things",
                "url": f"https://example.org/{index}",
                "authors": [
                    {
                        "name": f"Author {index}",
                        "organization": f"Institute {index}",
                        "country": "US",
                    }
                ],
                "abstract": (
                    f"We study {phrase} on real hardware. The method reduces latency and energy "
                    f"cost, and the paper reports measurements on public benchmarks."
                ),
            }
            for index, year in enumerate((2023, 2024, 2025, 2025))
        ]
        cache.put(
            phrase,
            SourceResult(
                source=openalex.EXAMPLES_NAME,
                ok=True,
                collected_on=TODAY,
                version=openalex.EXAMPLES_VERSION,
                data={"works": works},
            ),
        )


def signals_engine(cache_dir: Path, proposer: object | None = None) -> Any:
    """Движок, собранный тем же `build_engine`, что и в боевом пути."""
    return build_engine(
        "signals",
        EngineContext(
            embedding_provider=TfidfSvdEmbeddingProvider(),
            signals=SignalsConfiguration(
                proposer=proposer,  # type: ignore[arg-type]
                model_path=MODEL,
                # Настоящий адаптер и настоящий сбор — не ходят в сеть только потому, что им
                # велено брать всё из кэша.
                probe=LiveSignalsProbe(
                    cache_dir=cache_dir, cache_only=True, years=YEARS, today=lambda: TODAY
                ),
            ),
        ),
    )


@pytest.fixture(scope="module")
def baseline() -> PipelineResult:
    """Отчёт конвейера с баллом из индикаторов по тому же корпусу — с чем сравнивать общую часть.

    Движка методологии больше нет, но конвейер, на котором он стоял, остался — и на нём же стоит
    ``signals``; его и сравниваем, как это делают офлайн-инструменты.
    """
    return (
        EngineContext(embedding_provider=TfidfSvdEmbeddingProvider()).pipeline().run(request_for())
    )


@pytest.fixture(scope="module")
def prepared(
    tmp_path_factory: pytest.TempPathFactory, baseline: PipelineResult
) -> tuple[Path, tuple[str, ...]]:
    """Кэш, заполненный ответами про первые три темы отчёта методологии.

    Три, а не все: половина тем обязана остаться без внешних признаков — иначе путь «источник
    промолчал» в проверке не участвует, а он самый частый.
    """
    cache_dir = tmp_path_factory.mktemp("signals-cache")
    known = tuple(external_search_phrase(outcome.result.title) for outcome in baseline.trends[:3])
    prefill(cache_dir, known)
    return cache_dir, known


@pytest.fixture(scope="module")
def report(prepared: tuple[Path, tuple[str, ...]]) -> PipelineResult:
    """Отчёт второго движка."""
    return signals_engine(prepared[0]).run(request_for())


@pytest.mark.integration
def test_the_shared_part_of_both_engines_is_literally_the_same(
    report: PipelineResult, baseline: PipelineResult
) -> None:
    # Отбор корпуса, извлечение кандидатов, слияние и отнесение к направлению взяты у методологии
    # как есть. Разойдись они — расхождение выдачи двух движков перестало бы быть объяснимым
    # одним числом, а аналитику пришлось бы выбирать между двумя непрозрачными списками.
    assert report.documents_analyzed == baseline.documents_analyzed
    assert report.candidates_evaluated == baseline.candidates_evaluated
    assert report.direction_recognized == baseline.direction_recognized
    assert report.diagnostics["topics_credible"] == baseline.diagnostics["topics_credible"]


@pytest.mark.integration
def test_every_published_trend_carries_the_whole_breakdown_of_its_score(
    report: PipelineResult,
) -> None:
    assert report.trends, "отчёт пуст — сравнивать нечего"
    assert len(report.signal_scores) == len(report.trends)
    for score in report.signal_scores:
        # Признаки модели и отдельной строкой — доля балла методологии, которая держит порядок
        # там, где внешних признаков нет.
        assert tuple(item.name for item in score.contributions) == (*FEATURES, "methodology_score")
        assert score.model_version == "fixture-0.0.0"
        assert 0.0 <= score.score <= 100.0


@pytest.mark.integration
def test_the_order_of_the_report_is_the_order_of_the_model_score(report: PipelineResult) -> None:
    # Балл модели обязан решать состав и порядок. Если бы он менял только числа, движок был бы
    # заглушкой с другой подписью — ровно тем, чем был до этой работы.
    scores = [outcome.result.score for outcome in report.trends]

    assert scores == sorted(scores, reverse=True)
    by_key = {score.trend_key: score.score for score in report.signal_scores}
    for outcome in report.trends:
        assert outcome.result.score == pytest.approx(by_key[outcome.result.trend_key])


@pytest.mark.integration
def test_topics_the_sources_kept_silent_about_stay_and_are_marked(
    report: PipelineResult, prepared: tuple[Path, tuple[str, ...]]
) -> None:
    known = set(prepared[1])
    marked = {score.term: score.external for score in report.signal_scores}

    assert marked, "движок не посчитал ни одной темы"
    # Отказ источника — нормальное состояние, а не авария: тема остаётся в отчёте с баллом по
    # корпусной части, и это написано, а не угадывается по нулям.
    silent = [term for term, external in marked.items() if not external]
    assert silent, "в проверке не осталось ни одной темы без внешних признаков"
    for term in silent:
        assert term not in known
    assert any(marked.values()), "ни одна тема не получила внешних признаков из кэша"
    for score in report.signal_scores:
        if score.external:
            assert "openalex.works_window_total" not in score.missing


@pytest.mark.integration
def test_two_runs_of_the_second_engine_agree(
    report: PipelineResult, prepared: tuple[Path, tuple[str, ...]]
) -> None:
    again = signals_engine(prepared[0]).run(request_for())

    assert [outcome.result.trend_key for outcome in again.trends] == [
        outcome.result.trend_key for outcome in report.trends
    ]
    assert again.signal_scores == report.signal_scores


@pytest.mark.integration
def test_the_report_of_the_second_engine_satisfies_the_published_contract(
    report: PipelineResult,
) -> None:
    payload = build_domain_analyzed(
        report,
        research_request_id="019fd789-0000-7000-8000-000000000001",
        attempt=1,
        snapshot_id="019fd789-0000-7000-8000-000000000002",
        profile=MethodologyProfile.default(),
        engine="signals",
    )
    schema = json.loads((SCHEMAS / "domain-analyzed.event.json").read_text(encoding="utf-8"))

    Draft202012Validator(schema).validate(payload)

    assert payload["engine"] == "signals"
    assert payload["trends"][0]["signals"]["contributions"]
    # Устойчивость места меряет перевзвешивание индикаторов методологии — утверждение про балл,
    # которым здесь не ранжировали. Под этим отчётом его быть не должно.
    assert payload["reweightingScenarios"] == 0
    assert "rankStability" not in payload["trends"][0]


# ───────────────────────────── второй источник кандидатов ─────────────────────────────


@pytest.fixture(scope="module")
def proposing(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Кэш, в котором подтверждены два предложенных имени и не подтверждено третье.

    Третье имя — не украшение проверки, а её предмет: именно на нём видно, что граница ТЗ §3.1
    проходит по измерению, а не по тому, назвала ли модель имя убедительно.
    """
    cache_dir = tmp_path_factory.mktemp("signals-proposed")
    prefill(cache_dir, PROPOSED)
    prefill_works(cache_dir, PROPOSED)
    return cache_dir


@pytest.fixture(scope="module")
def proposed_report(proposing: Path) -> PipelineResult:
    """Отчёт движка со вторым источником кандидатов."""
    return signals_engine(proposing, Proposer()).run(request_for(top_n=50))


@pytest.mark.integration
def test_a_name_the_sources_did_not_confirm_never_reaches_the_report(
    proposed_report: PipelineResult,
) -> None:
    # Граница ТЗ §3.1 в прогоне целиком: имя названо моделью, источники о нём молчат, в выдаче
    # его нет. Зато оно есть в перечне исключённого — аналитик видит и предложение, и отказ.
    titles = {outcome.result.title.lower() for outcome in proposed_report.trends}

    assert UNCONFIRMED.lower() not in titles
    codes = {row.code: row for row in proposed_report.exclusions}
    assert "proposed_no_evidence" in codes
    assert UNCONFIRMED in codes["proposed_no_evidence"].examples


@pytest.mark.integration
def test_a_confirmed_name_reaches_the_report_marked_as_the_model_s(
    proposed_report: PipelineResult,
) -> None:
    published = {outcome.result.title.lower() for outcome in proposed_report.trends}
    assert published & {
        name.lower() for name in PROPOSED
    }, "ни одно подтверждённое имя не дошло до отчёта — проверять происхождение не на чем"

    payload = build_domain_analyzed(
        proposed_report,
        research_request_id="019fd789-0000-7000-8000-000000000003",
        attempt=1,
        snapshot_id="019fd789-0000-7000-8000-000000000002",
        profile=MethodologyProfile.default(),
        engine="signals",
    )
    schema = json.loads((SCHEMAS / "domain-analyzed.event.json").read_text(encoding="utf-8"))
    Draft202012Validator(schema).validate(payload)

    origins = {trend["title"].lower(): trend["origin"] for trend in payload["trends"]}
    assert all(origin in {"corpus", "model"} for origin in origins.values())
    assert {origins[name] for name in published & {n.lower() for n in PROPOSED}} == {"model"}
    assert "corpus" in set(origins.values()), "все темы оказались предложенными — так не бывает"
    # Тема, предложенная моделью, приходит с доказательной базой: без неё публиковать её нельзя.
    for trend in payload["trends"]:
        if trend["origin"] == "model":
            assert trend["evidence"], "предложенная тема опубликована без источников"
            assert trend["signals"]["external"] is True


@pytest.mark.integration
def test_a_proposed_topic_is_scored_without_corpus_features(
    proposed_report: PipelineResult,
) -> None:
    # Корпусные признаки предложенной темы неизвестны — и остаются неизвестными, а не нулями.
    proposed = {
        score.trend_key for score in proposed_report.signal_scores
    } & proposed_report.proposed_keys
    assert proposed, "в отчёте нет ни одной предложенной темы"

    for score in proposed_report.signal_scores:
        if score.trend_key in proposed_report.proposed_keys:
            missing = set(score.missing)
            assert missing >= set(CORPUS_FEATURES)
            values = {item.name for item in score.contributions if item.value is not None}
            assert values and not values & set(CORPUS_FEATURES)


@pytest.mark.integration
def test_two_runs_with_the_second_source_agree(
    proposing: Path, proposed_report: PipelineResult
) -> None:
    again = signals_engine(proposing, Proposer()).run(request_for(top_n=50))

    assert [outcome.result.trend_key for outcome in again.trends] == [
        outcome.result.trend_key for outcome in proposed_report.trends
    ]
    assert again.signal_scores == proposed_report.signal_scores
    assert again.proposed_keys == proposed_report.proposed_keys


@pytest.mark.integration
def test_a_model_service_that_does_not_answer_leaves_the_report_to_the_corpus(
    prepared: tuple[Path, tuple[str, ...]], report: PipelineResult
) -> None:
    # Настоящий адаптер против мёртвого адреса: клиент отвечает пустым списком, а не исключением,
    # и отчёт выходит по корпусу — ровно тот, что и без второго источника.
    dead = HttpTechnologyProposer(HttpNlpClient("http://127.0.0.1:1", timeout=1.0), "absent")

    result = signals_engine(prepared[0], dead).run(request_for())

    assert result.trends, "отказ сервиса моделей уронил анализ"
    assert result.proposed_keys == frozenset()
    assert [outcome.result.trend_key for outcome in result.trends] == [
        outcome.result.trend_key for outcome in report.trends
    ]
