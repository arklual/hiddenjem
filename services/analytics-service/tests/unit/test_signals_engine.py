"""Расчёт движка `signals`: балл, вклады признаков и три способа его испортить.

Движок отличается от методологии ровно одним числом — баллом ранжирования, — и это число берётся
не из кода, а из обученного артефакта. Отсюда предмет проверок: не «правильный ли балл» (правильный
знает только замер на размеченных данных), а что его нельзя получить незаметно неправильным.

Четыре способа, каждый из которых даёт правдоподобный отчёт:

* артефакта нет, а движок посчитал — методологией, под чужой подписью;
* веса легли не на те признаки, потому что порядок в артефакте разошёлся со списком в домене;
* источник промолчал, а его молчание сложилось в балл нулями и стало свойством технологии;
* два прогона на одних и тех же данных разошлись.

Сеть здесь не участвует и не подменяется: источник — это порт, и в проверках стоит его реализация,
которая честно отвечает тем, что ей дали. Прогон движка целиком, на эталонном корпусе и
предзаполненном кэше, — в `tests/integration/test_signals_engine_run.py`.
"""

from __future__ import annotations

import json
import math
import re
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

import pytest
from tests.conftest import make_document, make_series, make_topic

from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.domain.engines import EngineContext, SignalsConfiguration, build_engine
from horizon_analytics.domain.models import Document, EmergenceResult, ScoredIndicator
from horizon_analytics.domain.signal_scoring import (
    ALPHAXIV_MAX_POINTS,
    ALPHAXIV_POINTS_PER_PAPER,
    CORPUS_FEATURES,
    DEFAULT_PROPOSED_LIMIT,
    EXTERNAL_FEATURES,
    FEATURES,
    MODEL_SCHEMA,
    ProposedCandidates,
    ScoredTopic,
    SignalsModel,
    SignalsModelError,
    SignalsRescorer,
    corpus_features,
    external_search_phrase,
)

MODEL = Path(__file__).resolve().parents[1] / "fixtures" / "signals_model.json"


# ───────────────────────────── стенд ─────────────────────────────


class Probe:
    """Источник признаков, отвечающий ровно тем, что ему положили.

    Реализация порта, а не подмена сети: сеть живёт в адаптере, а движок по контракту знает лишь
    «фраза → признаки, и ключа может не быть».
    """

    def __init__(
        self,
        answers: Mapping[str, Mapping[str, float]] | None = None,
        sources: Mapping[str, Sequence[Document]] | None = None,
    ) -> None:
        """Запомнить ответы и то, о чём спрашивали."""
        self.answers = dict(answers or {})
        self.sources = dict(sources or {})
        self.asked: list[list[str]] = []

    @property
    def source_id(self) -> str:
        return "fixture"

    def features(self, terms: Sequence[str]) -> Mapping[str, Mapping[str, float]]:
        self.asked.append(list(terms))
        return {term: self.answers[term] for term in terms if term in self.answers}

    def works(self, terms: Sequence[str]) -> Mapping[str, Sequence[Document]]:
        return {term: self.sources[term] for term in terms if term in self.sources}


def result_for(
    key: str, *, title: str | None = None, df: Sequence[int] = (1, 2, 4, 8)
) -> EmergenceResult:
    """Строка ранжирования методологии — вход второго движка."""
    series = make_series(df)
    return EmergenceResult(
        trend_key=key,
        title=title or key,
        aliases=(),
        score=42.0,
        confidence=0.8,
        low_evidence=False,
        indicators=(
            ScoredIndicator(
                name="novelty",
                value=0.5,
                weight=1.0,
                multiplier=1.0,
                shortfall_share=0.0,
                explanation="",
                diagnostics={},
            ),
        ),
        lifecycle_stage="EMERGING",
        first_mention_year=2020,
        total_documents=sum(df),
        series=series,
        dov=(0.0,) * len(df),
        dod=(0.0,) * len(df),
        burst=None,
        confidence_diagnostics={},
        confidence_explanation="",
    )


def topic_for(key: str, documents: int) -> tuple[Any, tuple[Document, ...]]:
    """Тема с заданным числом документов — от него зависит, кого спросят снаружи."""
    ids = [f"{key}-{index}" for index in range(documents)]
    return make_topic(key, documents=ids), tuple(
        make_document(document_id, year=2024) for document_id in ids
    )


def row(key: str, *, documents: int = 3, title: str | None = None) -> ScoredTopic:
    """Строка `(результат, тема, документы)` в том виде, в каком её передаёт конвейер."""
    topic, docs = topic_for(key, documents)
    return result_for(key, title=title), topic, docs


def artifact(**changes: Any) -> dict[str, Any]:
    """Содержимое настоящего артефакта с точечной правкой."""
    payload = json.loads(MODEL.read_text(encoding="utf-8"))
    payload.update(changes)
    return payload


def written(path: Path, payload: Mapping[str, Any]) -> Path:
    """Положить артефакт на диск и вернуть путь — загрузка читает только с диска."""
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


def rescorer(probe: Probe, *, top: int = 150) -> SignalsRescorer:
    return SignalsRescorer(model=SignalsModel.load(MODEL), probe=probe, top_candidates=top)


# ───────────────────────────── артефакт ─────────────────────────────


def test_without_an_artifact_the_engine_refuses_to_be_built() -> None:
    # Не молчаливый откат к методологии: отчёт вышел бы подписанным `signals` и посчитанным
    # методологией, и отличить это от исправной работы по выдаче нельзя ничем.
    with pytest.raises(SignalsModelError) as error:
        build_engine(
            "signals",
            EngineContext(
                embedding_provider=TfidfSvdEmbeddingProvider(),
                signals=SignalsConfiguration(model_path=None, probe=Probe()),
            ),
        )

    assert "HORIZON_SIGNALS_MODEL" in str(error.value)


def test_a_missing_artifact_file_names_the_path(tmp_path: Path) -> None:
    absent = tmp_path / "нет-такого.json"

    with pytest.raises(SignalsModelError) as error:
        SignalsModel.load(absent)

    assert str(absent) in str(error.value)


def test_a_model_without_a_holdout_metric_is_refused(tmp_path: Path) -> None:
    # Модель без замера нельзя ни принять, ни сравнить со следующей: «обучили и выкатили» — это
    # и есть тот случай, когда качество выдачи меняется, а узнать об этом неоткуда.
    payload = artifact()
    del payload["holdout"]

    with pytest.raises(SignalsModelError, match="отложенной части"):
        SignalsModel.load(written(tmp_path / "model.json", payload))


def test_a_feature_list_in_the_wrong_order_is_refused(tmp_path: Path) -> None:
    # Самый тихий из отказов: веса лягут не на те признаки, балл посчитается, отчёт соберётся, и
    # единственным следом останется другой порядок тем.
    payload = artifact()
    payload["features"][0], payload["features"][1] = payload["features"][1], payload["features"][0]

    with pytest.raises(SignalsModelError) as error:
        SignalsModel.load(written(tmp_path / "model.json", payload))

    assert "порядок" in str(error.value)


def test_a_missing_feature_is_named(tmp_path: Path) -> None:
    payload = artifact()
    dropped = payload["features"].pop(5)["name"]

    with pytest.raises(SignalsModelError) as error:
        SignalsModel.load(written(tmp_path / "model.json", payload))

    assert dropped in str(error.value)
    assert "недостающие" in str(error.value)


def test_an_unknown_feature_is_named(tmp_path: Path) -> None:
    payload = artifact()
    payload["features"].append(
        {"name": "github.stars", "weight": 1.0, "transform": "log1p", "center": 0.0, "scale": 1.0}
    )

    with pytest.raises(SignalsModelError) as error:
        SignalsModel.load(written(tmp_path / "model.json", payload))

    assert "github.stars" in str(error.value)


def test_an_artifact_of_another_schema_is_refused(tmp_path: Path) -> None:
    with pytest.raises(SignalsModelError, match=re.escape(MODEL_SCHEMA)):
        SignalsModel.load(written(tmp_path / "model.json", artifact(schema="horizon.signals/9")))


def test_a_zero_scale_is_refused_before_the_first_candidate(tmp_path: Path) -> None:
    payload = artifact()
    payload["features"][0]["scale"] = 0.0

    with pytest.raises(SignalsModelError, match="scale"):
        SignalsModel.load(written(tmp_path / "model.json", payload))


def test_the_artifact_carries_what_the_report_has_to_disclose() -> None:
    model = SignalsModel.load(MODEL)

    assert model.version and model.trained_on.year >= 2026
    assert model.holdout["metric"] and "value" in model.holdout


# ───────────────────────────── балл ─────────────────────────────


def test_the_score_is_the_weighted_sum_through_the_link() -> None:
    # Балл обязан быть проверяемым вручную: строка отчёта «почему эта тема поднялась» — это и
    # есть слагаемые этой суммы, и если они не складываются в неё, объяснение ничего не объясняет.
    model = SignalsModel.load(MODEL)
    features = dict.fromkeys(FEATURES, 1.0)

    score, raw, contributions, missing = model.score(features)

    assert not missing
    assert raw == pytest.approx(model.bias + sum(item.contribution for item in contributions))
    assert score == pytest.approx(100.0 / (1.0 + math.exp(-raw)))
    assert tuple(item.name for item in contributions) == FEATURES


def test_two_runs_over_the_same_data_agree_to_the_last_digit() -> None:
    probe = Probe({"alpha": dict.fromkeys(EXTERNAL_FEATURES, 1.0)})
    rows = [row("alpha"), row("beta")]

    first, first_scores = rescorer(probe).rescore(rows, termhood={}, drops={})
    second, second_scores = rescorer(probe).rescore(rows, termhood={}, drops={})

    assert [result.score for result, _, _ in first] == [result.score for result, _, _ in second]
    assert first_scores == second_scores


def test_a_silent_source_leaves_the_corpus_part_and_says_so() -> None:
    # Отказ источника не должен ни ронять анализ, ни исчезать из него: тема остаётся, балл
    # собран по корпусу, и это написано в отчёте, а не угадывается по нулям.
    rescored, scores = rescorer(Probe()).rescore([row("alpha")], termhood={}, drops={})

    assert len(rescored) == 1
    assert scores[0].external is False
    assert set(scores[0].missing) == set(EXTERNAL_FEATURES)
    present = {item.name for item in scores[0].contributions if item.value is not None}
    assert present == {*CORPUS_FEATURES, "methodology_score"}
    assert all(item.contribution == 0.0 for item in scores[0].contributions if item.value is None)


def test_an_absent_feature_is_not_the_same_as_a_zero() -> None:
    # Ровно та подмена, на которой модель научилась бы считать недоступность источника признаком
    # незрелости технологии.
    model = SignalsModel.load(MODEL)
    corpus = corpus_features(result_for("alpha"))

    zeroed, *_ = model.score({**corpus, **dict.fromkeys(EXTERNAL_FEATURES, 0.0)})
    absent, *_ = model.score(corpus)

    assert zeroed != pytest.approx(absent)


def test_the_score_replaces_the_methodology_one_on_the_row_the_pipeline_ranks() -> None:
    probe = Probe({"alpha": dict.fromkeys(EXTERNAL_FEATURES, 2.0)})

    rescored, scores = rescorer(probe).rescore([row("alpha")], termhood={}, drops={})

    assert rescored[0][0].score == pytest.approx(scores[0].score)
    assert rescored[0][0].score != pytest.approx(42.0)
    # Всё остальное строки не меняется: состав отчёта, доказательная база и стадия остаются те же.
    assert rescored[0][1] is not None
    assert rescored[0][0].total_documents == 15


def test_the_score_stays_inside_the_range_the_contract_declares() -> None:
    model = SignalsModel.load(MODEL)

    for value in (-1e9, 0.0, 1e9):
        score, *_ = model.score(dict.fromkeys(FEATURES, value))
        assert 0.0 <= score <= 100.0


# ───────────────────────────── кого спрашивают ─────────────────────────────


def test_only_the_top_candidates_are_asked_and_the_rest_are_still_scored() -> None:
    # Сбор стоит сети, и тратить её на кандидата, который не попадёт и в первую сотню, незачем.
    # Но непрошенный кандидат не выбывает — он считается по корпусной части.
    probe = Probe()
    rows = [row("alpha", documents=9), row("beta", documents=2), row("gamma", documents=5)]

    rescored, scores = rescorer(probe, top=2).rescore(rows, termhood={}, drops={})

    assert probe.asked == [["alpha", "gamma"]]
    assert len(rescored) == 3
    assert {score.trend_key for score in scores} == {"alpha", "beta", "gamma"}


def test_termhood_breaks_the_tie_between_equally_frequent_candidates() -> None:
    probe = Probe()
    rows = [row("alpha", documents=4), row("beta", documents=4)]

    rescorer(probe, top=1).rescore(rows, termhood={"beta": 0.9, "alpha": 0.1}, drops={})

    assert probe.asked == [["beta"]]


def test_an_acronym_label_is_searched_by_its_expansion() -> None:
    # «speculative decoding (SD)» точной фразой не находится нигде, и тема получила бы «источник
    # молчит» там, где источник просто не спрашивали.
    probe = Probe({"speculative decoding": dict.fromkeys(EXTERNAL_FEATURES, 1.0)})

    _, scores = rescorer(probe).rescore(
        [row("speculative decoding", title="speculative decoding (SD)")], termhood={}, drops={}
    )

    assert probe.asked == [["speculative decoding"]]
    assert scores[0].external is True
    assert external_search_phrase("speculative decoding (SD)") == "speculative decoding"


def test_the_run_counts_what_it_asked_and_what_it_got() -> None:
    # Без этих счётчиков «посчитано по внешним признакам» и «внешних признаков не было ни у кого»
    # выглядят в журнале одинаково.
    drops: dict[str, int] = {}
    probe = Probe({"alpha": dict.fromkeys(EXTERNAL_FEATURES, 1.0)})

    rescorer(probe).rescore([row("alpha"), row("beta")], termhood={}, drops=drops)

    assert drops["signals_scored"] == 2
    assert drops["signals_asked"] == 2
    assert drops["signals_with_external"] == 1
    assert "signals_blind" not in drops


def test_a_run_almost_without_external_features_says_so() -> None:
    # Источник может отказать целиком: кончился суточный лимит каталога, закрыт кэш, пришёл 403.
    # Движок при этом продолжает работать и выдаёт темы с почти одинаковым баллом — и по отчёту
    # это неотличимо от честного результата, если прогон не помечен.
    drops: dict[str, int] = {}
    probe = Probe({})

    rescorer(probe).rescore(
        [row("alpha"), row("beta"), row("gamma"), row("delta")], termhood={}, drops=drops
    )

    assert drops["signals_with_external"] == 0
    assert drops["signals_blind"] == 1


# ───────────────────────────── второй источник кандидатов ─────────────────────────────


class Proposer:
    """Модель, называющая технологии направления. Реализация порта, а не подмена сети."""

    def __init__(self, names: Sequence[str]) -> None:
        """Запомнить имена и то, о чём спрашивали."""
        self.names = list(names)
        self.asked: list[tuple[str, int]] = []

    @property
    def model_id(self) -> str:
        return "fixture-model"

    def propose(self, direction: str, limit: int) -> Sequence[str]:
        self.asked.append((direction, limit))
        return self.names[:limit]


class SilentProposer:
    """Модель, которая не ответила: отказ сервиса, пустой список, анализ продолжается."""

    @property
    def model_id(self) -> str:
        return "fixture-model"

    def propose(self, direction: str, limit: int) -> Sequence[str]:
        return ()


def work(
    document_id: str, *, year: int = 2024, organization: str = "Acme Research Lab"
) -> Document:
    """Настоящая работа в доказательной базе предложенной темы."""
    return make_document(document_id, year=year, organizations=(organization,))


def candidates(
    proposer: object, probe: Probe, *, limit: int = DEFAULT_PROPOSED_LIMIT
) -> ProposedCandidates:
    return ProposedCandidates(proposer=proposer, probe=probe, limit=limit)  # type: ignore[arg-type]


ACTIVE = {"openalex.works_window_total": 42.0, "openalex.distinct_institutions": 7.0}
SILENT: dict[str, float] = {}
NAMED_ONLY = {"wikipedia.exists": 1.0, "openalex.works_window_total": 0.0}


def test_a_name_without_measured_evidence_never_becomes_a_topic() -> None:
    # Здесь проходит граница ТЗ §3.1: модель предложила, источники промолчали — имени в выдаче
    # нет. Без этой проверки выдача опиралась бы на знания модели, и выглядело бы это нормально.
    probe = Probe({"photonic processors": SILENT}, {"photonic processors": (work("w1"),)})
    drops: dict[str, int] = {}

    batch = candidates(Proposer(["photonic processors"]), probe).propose(
        "optics", known=frozenset(), drops=drops
    )

    assert batch.accepted == ()
    assert batch.rejected == (("photonic processors", "proposed_no_evidence"),)
    assert drops["proposed_without_evidence"] == 1
    assert drops["proposed_accepted"] == 0


class CorpusProposer(Proposer):
    """Имена из веб-корпуса с адресами страниц, на которых сборщик их прочитал (разбор 110)."""

    def __init__(self, evidence: Mapping[str, Sequence[str]]) -> None:
        """Запомнить имена и их страницы."""
        super().__init__(list(evidence))
        self.evidence = dict(evidence)

    def propose_with_evidence(
        self, direction: str, limit: int
    ) -> tuple[Sequence[str], Mapping[str, Sequence[str]]]:
        return self.propose(direction, limit), self.evidence


def test_a_corpus_name_is_confirmed_by_its_pages_in_the_analysis_corpus() -> None:
    # Рыночного сигнала нет в OpenAlex: внешние источники молчат, работ нет. Доказательная база —
    # страницы веб-корпуса, уже собранные в корпус анализа, на которых сборщик прочитал имя.
    page = work("p1", organization="Pulse 2.0")
    other = work("p2", organization="SiliconANGLE")
    pages = {"agent identity management": [page.url, other.url + "/"]}
    probe = Probe({"agent identity management": SILENT})
    drops: dict[str, int] = {}

    batch = candidates(CorpusProposer(pages), probe).propose(
        "защита ии", known=frozenset(), drops=drops, documents=(page, other, work("p3"))
    )

    assert [p.topic.key for p in batch.accepted] == ["agent identity management"]
    assert {d.document_id for d in batch.accepted[0].documents} == {"p1", "p2"}
    assert drops["proposed_from_webcorpus"] == 1
    assert probe.sources == {}


def test_a_corpus_name_whose_pages_are_not_in_the_analysis_is_checked_like_any_other() -> None:
    probe = Probe({"agent identity management": SILENT})
    drops: dict[str, int] = {}

    batch = candidates(
        CorpusProposer({"agent identity management": ["https://example.com/missing"]}), probe
    ).propose("защита ии", known=frozenset(), drops=drops, documents=(work("p3"),))

    assert batch.accepted == ()
    assert batch.rejected == (("agent identity management", "proposed_no_evidence"),)


class FetchingProposer(CorpusProposer):
    """Страницы, которых нет в корпусе анализа, отдаёт сервис моделей по адресам."""

    def __init__(self, evidence: Mapping[str, Sequence[str]], pages: Sequence[Document]) -> None:
        """Запомнить имена, адреса и страницы у сервиса моделей."""
        super().__init__(evidence)
        self.pages = list(pages)

    def evidence_documents(self, urls: Sequence[str]) -> Sequence[Document]:
        return [page for page in self.pages if page.url in urls]


def test_fetched_pages_obey_the_report_parameters() -> None:
    # Классы источников и окно лет отчёта ограничивали собранный корпус, а страницы-доказательства,
    # подтянутые у сервиса моделей, проходили мимо: тема на новостях попадала в отчёт без новостей.
    news = make_document("n1", year=2026, source_class="NEWS")
    proposer = FetchingProposer({"agent identity management": [news.url]}, [news])
    probe = Probe({"agent identity management": SILENT})

    batch = candidates(proposer, probe).propose(
        "защита ии", known=frozenset(), drops={}, documents=(),
        admit=lambda document: document.source_class != "NEWS",
    )

    assert batch.accepted == ()
    assert batch.rejected == (("agent identity management", "proposed_no_evidence"),)


def test_external_budget_skips_candidates_known_to_be_ineligible() -> None:
    probe = Probe({"alpha": ACTIVE, "beta": ACTIVE})
    drops: dict[str, int] = {}

    rescored, _ = rescorer(probe, top=1).rescore(
        [row("alpha"), row("beta")],
        termhood={},
        drops=drops,
        eligible_keys=frozenset({"beta"}),
    )

    assert probe.asked == [["beta"]]
    assert len(rescored) == 2
    assert drops["signals_asked"] == 1


def test_broad_proposals_are_rejected_before_external_search() -> None:
    concrete = "distributed ledger storage"
    probe = Probe({concrete: ACTIVE}, {concrete: (work("w1"),)})
    drops: dict[str, int] = {}

    batch = candidates(
        Proposer(["financial infrastructure", concrete]), probe
    ).propose("fintech", known=frozenset(), drops=drops)

    assert probe.asked == [[concrete]]
    assert batch.rejected == (("financial infrastructure", "proposed_generic_name"),)
    assert [item.topic.label for item in batch.accepted] == [concrete]
    assert drops["proposed_generic_name"] == 1


def test_a_name_known_only_to_wikipedia_is_not_activity() -> None:
    # Статья есть, работ ноль, упоминаний ноль: это след названия, а не технологии. Считать
    # наличие статьи активностью значило бы пускать в отчёт любое слово со страницей в словаре.
    probe = Probe({"quantum foam": NAMED_ONLY}, {"quantum foam": (work("w1"),)})

    batch = candidates(Proposer(["quantum foam"]), probe).propose(
        "physics", known=frozenset(), drops={}
    )

    assert batch.accepted == ()
    assert batch.rejected == (("quantum foam", "proposed_no_evidence"),)


def test_a_name_with_activity_but_no_sources_is_not_published() -> None:
    # Инвариант BR-A6 тот же, что у корпусных тем: тема без доказательной базы не публикуется.
    # Показать аналитику «модель считает, что это важно» и ни одной ссылки — нельзя.
    probe = Probe({"analog compute-in-memory": ACTIVE}, {})
    drops: dict[str, int] = {}

    batch = candidates(Proposer(["analog compute-in-memory"]), probe).propose(
        "hardware", known=frozenset(), drops=drops
    )

    assert batch.accepted == ()
    assert batch.rejected == (("analog compute-in-memory", "proposed_no_sources"),)
    assert drops["proposed_without_sources"] == 1


def test_a_confirmed_name_becomes_a_topic_backed_by_the_works_that_confirmed_it() -> None:
    documents = (work("w1"), work("w2", organization="Beta Institute"))
    probe = Probe({"photonic processors": ACTIVE}, {"photonic processors": documents})

    batch = candidates(Proposer(["photonic processors"]), probe).propose(
        "optics", known=frozenset(), drops={}
    )

    assert len(batch.accepted) == 1
    proposal = batch.accepted[0]
    assert proposal.topic.label == "photonic processors"
    assert proposal.documents == documents
    assert proposal.topic.document_frequency == 2
    # Корпусных признаков у предложенной темы нет и быть не может: её нет в корпусе.
    assert set(proposal.features) <= set(EXTERNAL_FEATURES)


def test_a_name_the_corpus_already_found_is_not_proposed_twice() -> None:
    probe = Probe({"photonic processors": ACTIVE}, {"photonic processors": (work("w1"),)})
    drops: dict[str, int] = {}

    batch = candidates(Proposer(["Photonic Processors"]), probe).propose(
        "optics", known=frozenset({"photonic processors"}), drops=drops
    )

    assert batch.accepted == ()
    assert drops["proposed_already_known"] == 1
    assert probe.asked == []


def test_a_silent_model_leaves_the_corpus_candidates_alone() -> None:
    # Отказ сервиса моделей — пустой набор, а не исключение: отчёт выходит по корпусу.
    probe = Probe()
    drops: dict[str, int] = {}

    batch = candidates(SilentProposer(), probe).propose("optics", known=frozenset(), drops=drops)

    assert batch.accepted == () and batch.rejected == ()
    assert drops["proposed_asked"] == 0
    assert probe.asked == []


def test_the_proposed_topic_is_scored_without_inventing_its_corpus_features() -> None:
    # Подставить ноль вместо «неизвестно» значило бы утверждать «ноль документов направления» —
    # ровно та подмена, которая запрещена и для молчащего источника.
    probe = Probe({"alpha": dict.fromkeys(EXTERNAL_FEATURES, 1.0)})
    rows = [row("alpha")]

    _, scores = rescorer(probe).rescore(
        rows,
        termhood={},
        drops={},
        proposed={"alpha": dict.fromkeys(EXTERNAL_FEATURES, 1.0)},
    )

    assert set(scores[0].missing) == set(CORPUS_FEATURES)
    assert scores[0].external is True
    # Внешние признаки предложенной темы уже собраны — спрашивать их второй раз незачем.
    assert probe.asked == []


# ───────────────────────────── alphaXiv в балле ─────────────────────────────


def _with_alphaxiv(key: str, papers: int) -> ScoredTopic:
    """Строка, у которой часть документов пришла из alphaXiv."""
    result, topic, documents = row(key)
    extra = tuple(
        make_document(
            f"{key}-ax-{index}", year=2025, source_id="alphaxiv", title=f"On {key} in practice"
        )
        for index in range(papers)
    )
    return result, topic, documents + extra


def test_alphaxiv_papers_raise_the_score_and_say_so() -> None:
    # Темы с одинаковым баллом модели: статьи alphaXiv поднимают ту, у которой они есть, и
    # прибавка стоит строкой объяснения рядом с признаками модели.
    rescored, scores = rescorer(Probe()).rescore(
        [row("plain"), _with_alphaxiv("backed", 2)], termhood={}, drops={}
    )

    plain = next(item for item in scores if item.trend_key == "plain")
    backed = next(item for item in scores if item.trend_key == "backed")
    assert backed.score == pytest.approx(min(100.0, plain.score + 2 * ALPHAXIV_POINTS_PER_PAPER))
    bonus = [item for item in backed.contributions if item.name == "alphaxiv_papers"]
    assert bonus and bonus[0].value == 2.0
    assert not [item for item in plain.contributions if item.name == "alphaxiv_papers"]
    by_key = {result.trend_key: result.score for result, _, _ in rescored}
    assert by_key["backed"] > by_key["plain"]


def test_alphaxiv_bonus_has_a_ceiling() -> None:
    _, scores = rescorer(Probe()).rescore(
        [row("plain"), _with_alphaxiv("manifold learning", 10)], termhood={}, drops={}
    )

    plain = next(item for item in scores if item.trend_key == "plain")
    many = next(item for item in scores if item.trend_key == "manifold learning")
    assert many.score == pytest.approx(min(100.0, plain.score + ALPHAXIV_MAX_POINTS))


def test_an_alphaxiv_paper_that_does_not_name_the_topic_earns_nothing() -> None:
    # Статья alphaXiv, прибившаяся к теме через кластер, но не называющая её, прибавки не даёт:
    # иначе тема про мимику лица поднималась за статьи об атаках на детекторы.
    result, topic, documents = row("action unit (AU)")
    stray = make_document(
        "stray-ax", year=2025, source_id="alphaxiv", title="ODPure: backdoor purification (AU metrics)"
    )
    _, scores = rescorer(Probe()).rescore(
        [row("plain"), (result, topic, documents + (stray,))], termhood={}, drops={}
    )

    plain = next(item for item in scores if item.trend_key == "plain")
    stray_topic = next(item for item in scores if item.trend_key == "action unit (AU)")
    assert stray_topic.score == pytest.approx(plain.score)
    assert not [item for item in stray_topic.contributions if item.name == "alphaxiv_papers"]


# ───────────────────────────── переранжирование верхушки ─────────────────────────────


class Reranker:
    """Модель, которая упорядочивает верхушку и снимает лишнее — реализация порта судьи."""

    def __init__(self, order: list[int], remove: dict[int, str]) -> None:
        """Запомнить ответ модели."""
        self.order, self.remove = order, remove
        self.asked: list[list[str]] = []

    def rerank(self, query: str, titles, contexts):  # type: ignore[no-untyped-def]
        self.asked.append(list(titles))
        return self.order, self.remove


def test_the_model_order_becomes_a_bonus_and_removed_rows_leave_the_head() -> None:
    from horizon_analytics.domain.pipeline import RERANK_MAX_POINTS, AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta"), row("gamma")]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()
    drops: dict[str, int] = {}
    bonuses: dict[str, float] = {}

    out = AnalysisPipeline._rerank_head(
        list(rows), request, drops, _Tracer.for_terms(frozenset(), None), Reranker([3, 1], {2: "не по запросу"}), bonuses
    )

    assert [r[0].trend_key for r in out] == ["gamma", "alpha"]
    assert bonuses["gamma"] == RERANK_MAX_POINTS
    assert drops["rerank_removed"] == 1


def test_a_silent_reranker_keeps_the_score_order() -> None:
    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta")]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()
    drops: dict[str, int] = {}

    out = AnalysisPipeline._rerank_head(list(rows), request, drops, _Tracer.for_terms(frozenset(), None), Reranker([], {}))

    assert out == rows
    assert drops["rerank_unavailable"] == 1


def test_a_corpus_name_carries_its_description_into_the_topic() -> None:
    # Имя предложенной темы дословно в аннотациях почти не встречается, и карточка получала
    # шаблон «выделена по N документам». Описание сборщика по тем же страницам — её определение.
    page = work("p1", organization="Pulse 2.0")
    other = work("p2", organization="SiliconANGLE")
    proposer = CorpusProposer({"agent identity management": [page.url, other.url]})
    proposer.details = {"agent identity management": "Идентичность и полномочия ИИ-агентов в корпоративных системах."}

    batch = candidates(proposer, Probe()).propose(
        "защита ии", known=frozenset(), drops={}, documents=(page, other)
    )

    assert batch.accepted[0].topic.description == proposer.details["agent identity management"]


class Jury:
    """Три эксперта: единогласно «да» только первой позиции."""

    def rerank(self, query: str, titles, contexts):  # type: ignore[no-untyped-def]
        return [], {}

    def jury(self, query: str, items):  # type: ignore[no-untyped-def]
        return [{"n": 1, "yes": 3}, {"n": 2, "yes": 1}, {"n": 3, "yes": 2}]


def test_experts_push_a_rejected_row_below_the_approved_ones_without_dropping_it() -> None:
    from horizon_analytics.domain.pipeline import JURY_PENALTY, AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta"), row("gamma")]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()
    drops: dict[str, int] = {}
    adjustments: dict[str, float] = {}

    out = AnalysisPipeline._jury_head(
        list(rows), request, drops, _Tracer.for_terms(frozenset(), None), Jury(), adjustments
    )

    # Двое «да» из трёх (gamma) выше одного (beta); штраф — не меньше JURY_PENALTY.
    assert [r[0].trend_key for r in out] == ["alpha", "gamma", "beta"]
    assert set(adjustments) == {"beta", "gamma"}
    assert all(value <= -JURY_PENALTY for value in adjustments.values())
    assert drops["jury_rejected"] == 2


class Harsh:
    """Модель снимает всё, кроме первой позиции каждой порции, — и считает, сколько порций видела."""

    def __init__(self) -> None:
        self.calls = 0

    def rerank(self, query: str, titles, contexts):  # type: ignore[no-untyped-def]
        self.calls += 1
        return [1], {n: "общее понятие" for n in range(2, len(titles) + 1)}


def test_when_most_of_the_head_is_removed_the_next_portion_is_read_instead_of_passing_unread() -> None:
    from horizon_analytics.domain.pipeline import RERANK_DEPTH, AnalysisPipeline, _Tracer

    rows = [row(f"t{i:03d}") for i in range(RERANK_DEPTH * 2 + 5)]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()
    harsh = Harsh()

    out = AnalysisPipeline._rerank_head(list(rows), request, {}, _Tracer.for_terms(frozenset(), None), harsh)

    # Три порции прочитаны; из каждой осталась одна — непрочитанного хвоста в голове нет.
    assert harsh.calls == 3
    assert [r[0].trend_key for r in out] == ["t000", f"t{RERANK_DEPTH:03d}", f"t{RERANK_DEPTH * 2:03d}"]


def test_a_corpus_approved_row_is_reordered_but_never_removed_by_the_reranker() -> None:
    # Размеченные заранее «да» по критериям жюри (разбор 110): модель переранжирования снимала
    # их как «не технология», хотя эти вопросы по ним решены на страницах-доказательствах.
    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta"), row("gamma")]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()
    drops: dict[str, int] = {}

    out = AnalysisPipeline._rerank_head(
        list(rows), request, drops, _Tracer.for_terms(frozenset(), None),
        Reranker([3, 1], {2: "не технология"}), {}, keep=frozenset({"beta"}),
    )

    assert {r[0].trend_key for r in out} == {"alpha", "beta", "gamma"}
    assert drops["rerank_remove_ignored"] == 1


class LateApproval:
    """Эксперты одобряют только последнюю позицию — у неё самый низкий балл."""

    def rerank(self, query: str, titles, contexts):  # type: ignore[no-untyped-def]
        return [], {}

    def jury(self, query: str, items):  # type: ignore[no-untyped-def]
        return [{"n": n, "yes": 3 if n == len(items) else 0} for n in range(1, len(items) + 1)]


def test_an_approved_row_ends_above_every_rejected_one_whatever_the_scores() -> None:
    # Штраф в пятнадцать пунктов не опускал отклонённую тему с высоким баллом ниже одобренной с
    # низким: в ТОП-15 v12 стояли «substantial» и «run alongside».
    from dataclasses import replace

    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta"), row("gamma")]
    rows = [(replace(r[0], score=s), r[1], r[2]) for r, s in zip(rows, (90.0, 80.0, 10.0), strict=True)]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()

    out = AnalysisPipeline._jury_head(list(rows), request, {}, _Tracer.for_terms(frozenset(), None), LateApproval(), {})

    assert out[0][0].trend_key == "gamma"


def test_a_corpus_approved_row_ranks_above_one_approved_only_by_the_experts() -> None:
    # Единогласие luna на отложенной выборке верно в двух случаях из трёх, разметка корпуса — по
    # страницам-доказательствам: «quantum computing» стояла восьмой в ТОП-15 открытого банкинга.
    from dataclasses import replace

    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta")]
    rows = [(replace(r[0], score=s), r[1], r[2]) for r, s in zip(rows, (90.0, 10.0), strict=True)]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()

    class Everyone:
        def rerank(self, query: str, titles, contexts):  # type: ignore[no-untyped-def]
            return [], {}

        def jury(self, query: str, items):  # type: ignore[no-untyped-def]
            return [{"n": n, "yes": 3} for n in range(1, len(items) + 1)]

    out = AnalysisPipeline._jury_head(
        list(rows), request, {}, _Tracer.for_terms(frozenset(), None), Everyone(), {},
        preapproved=frozenset({"beta"}),
    )

    assert [r[0].trend_key for r in out] == ["beta", "alpha"]


def test_a_topic_without_a_recent_source_does_not_reach_the_top() -> None:
    # Жюри засчитывает позицию, только если у неё есть источник 2024–2026 годов: три эксперта
    # отклонили по этой причине половину отчёта по нейроинтерфейсам (разбор 110).
    from datetime import date
    from types import SimpleNamespace

    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    fresh = row("fresh")
    topic, _ = topic_for("stale", 3)
    stale = (result_for("stale"), topic, tuple(make_document(f"s{i}", year=2021) for i in range(3)))
    request = SimpleNamespace(
        query="edge", normalized_query="edge", window_to=date(2026, 9, 29),
        params=SimpleNamespace(include_mature=False, min_confidence=None, suppressed_keys=frozenset(), top_n=15),
    )
    drops: dict[str, int] = {}

    selected, _ = AnalysisPipeline._rank(
        [fresh, stale], request, SimpleNamespace(mainstream_threshold=0.0), drops,  # type: ignore[arg-type]
        _Tracer.for_terms(frozenset(), None),
    )

    assert [r[0].trend_key for r in selected] == ["fresh"]
    assert drops["stale_evidence"] == 1


def test_the_score_never_rises_down_the_list_after_the_experts() -> None:
    # Отклонённая тема с баллом 51 стояла пятнадцатой под четырнадцатой с 35 («FAPI security
    # profile» в открытом банкинге): порядок верный, а балл с ним не согласован.
    from dataclasses import replace

    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta"), row("gamma")]
    rows = [(replace(r[0], score=s), r[1], r[2]) for r, s in zip(rows, (90.0, 80.0, 10.0), strict=True)]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()
    adjustments: dict[str, float] = {}

    out = AnalysisPipeline._jury_head(
        list(rows), request, {}, _Tracer.for_terms(frozenset(), None), LateApproval(), adjustments
    )

    scores = [r[0].score for r in out]
    assert [r[0].trend_key for r in out][0] == "gamma"
    assert scores == sorted(scores, reverse=True)
    assert all(adjustments[key] < 0 for key in ("alpha", "beta"))



class Unanimous:
    """Все трое против второй позиции."""

    def rerank(self, query: str, titles, contexts):  # type: ignore[no-untyped-def]
        return [], {}

    def jury(self, query: str, items):  # type: ignore[no-untyped-def]
        return [{"n": n, "yes": 0 if n == 2 else 3} for n in range(1, len(items) + 1)]


def test_a_row_all_three_experts_reject_does_not_fill_the_top() -> None:
    # «create opportunities» и «DNA sequences» добирали ТОП-15 кибербезопасности (29.09).
    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    rows = [row("alpha"), row("beta"), row("gamma")]
    request = type("Request", (), {"query": "edge", "normalized_query": "edge"})()
    drops: dict[str, int] = {}

    out = AnalysisPipeline._jury_head(list(rows), request, drops, _Tracer.for_terms(frozenset(), None), Unanimous(), {})

    assert [r[0].trend_key for r in out] == ["alpha", "gamma"]
    assert drops["jury_excluded"] == 1


def _card_request():
    from datetime import date
    from types import SimpleNamespace

    from horizon_analytics.domain.scoring.profile import MethodologyProfile

    return SimpleNamespace(
        query="edge", normalized_query="edge", profile=MethodologyProfile.default(),
        window_from=date(2020, 1, 1), window_to=date(2026, 9, 29),
    )


class Split:
    """Эксперты одобряют первую позицию, вторую отклоняют двумя голосами из трёх."""

    def rerank(self, query: str, titles, contexts):  # type: ignore[no-untyped-def]
        return [], {}

    def jury(self, query: str, items):  # type: ignore[no-untyped-def]
        return [
            {"n": 1, "yes": 3, "votes": {"domain": [True, "по теме"], "market": [True, "первые пилоты"],
                                         "evidence": [True, "источники 2026"]}},
            {"n": 2, "yes": 1, "votes": {"domain": [True, ""], "market": [False, "мейнстрим"],
                                         "evidence": [False, "шаблон"]}},
        ][: len(items)]


def test_a_rejected_row_without_a_definition_does_not_fill_the_top() -> None:
    # «code blocks» и «dark matter» добирали ТОП квантовых технологий с определением-шаблоном
    # «выделена по N документам»: жюри видит шаблон вместо описания и отвечает «нет».
    from horizon_analytics.domain.pipeline import AnalysisPipeline, _Tracer

    notes: dict[str, dict] = {}
    drops: dict[str, int] = {}
    out = AnalysisPipeline._jury_head(
        [row("alpha"), row("beta")], _card_request(), drops, _Tracer.for_terms(frozenset(), None), Split(), {},
        notes=notes,
    )

    assert [r[0].trend_key for r in out] == ["alpha"]
    assert drops["jury_excluded_templated"] == 1
    assert notes["alpha"]["votes"]["market"] == [True, "первые пилоты"]


def test_the_card_explains_the_status_and_the_confidence() -> None:
    # ТЗ: страница инсайта объясняет статус слабого сигнала и причины уверенности модели.
    from horizon_analytics.domain.evidence import select_evidence
    from horizon_analytics.domain.explanation import explain
    from horizon_analytics.domain.signal_scoring import FeatureContribution, SignalScore

    result, _, documents = row("alpha")
    request = _card_request()
    evidence = select_evidence(
        documents, window_from=request.window_from, window_to=request.window_to,
        parameters=request.profile.parameters,
    )
    signal = SignalScore(
        trend_key="alpha", term="alpha", score=62.0, raw=0.4, model_version="t", external=True, missing=(),
        contributions=(
            FeatureContribution(name="openalex.works_last_year", value=12.0, normalized=0.3, weight=1.2, contribution=0.36),
            FeatureContribution(name="methodology_score", value=50.0, normalized=0.5, weight=0.5, contribution=25.0),
            FeatureContribution(name="independent_sources", value=4.0, normalized=4.0, weight=3.0, contribution=6.0),
        ),
    )
    jury = {"yes": 3, "votes": {"market": [True, "первые пилоты"]}}

    items = dict(explain(result, evidence, signal=signal, jury=jury))

    assert "ранняя стадия, не мейнстрим — да (первые пилоты)" in items["Экспертная проверка"]
    assert "методология зарождения +25,0" in items["Из чего сложился балл"]
    assert "модель внешних признаков +31,0" in items["Из чего сложился балл"]
    # Уверенность — словами, из тех же частей, что в расчёте, а не строкой диагностики движка.
    from dataclasses import replace

    weak = replace(result, confidence=0.389, confidence_diagnostics={
        "documentFrequency": 5, "sourceClassCount": 1, "periodsWithData": 2, "spanReference": 4,
        "fit": 0.0, "threshold": 0.4,
    })
    why = dict(explain(weak, evidence))["Почему такая уверенность"]
    assert why.startswith("39 %.")
    assert "документов о теме — 5" in why and "классов источников — 1" in why and "за 2 года из 4" in why
    assert "ниже порога 40 %" in why and "evidence" not in why
    assert "Источники" in items
    corpus = dict(explain(result, evidence, jury={"yes": 3, "votes": {"corpus_labels": [True, "ранние пилоты"]}}))
    assert corpus["Экспертная проверка"].endswith(": ранние пилоты")
