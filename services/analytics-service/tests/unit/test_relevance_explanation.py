"""Что́ трассировка сообщает аналитику, когда тема не попала в отчёт.

Объяснимость — то, чем продукт отличается от списка слов: аналитик, увидевший отчёт без известной
ему технологии, спрашивает «почему её здесь нет», и ответ должен выдерживать проверку. Здесь
проверяется именно ответ, а не то, что он существует.

Дефект, который эти проверки закрывают, выглядел безобидно. Трассировка печатала
``threshold=parameters.relevance_threshold`` — статический параметр настроек, — тогда как решает
``cut``, вычисляемый от базовой доли направления в корпусе. На золотом корпусе выходило
«similarity=0.0; threshold=0.0»: числа, из которых следует, что тему надо было оставить, рядом с
сообщением, что она отброшена. Объяснение, спорящее с собственным вердиктом, хуже отсутствия
объяснения — оно подрывает доверие ко всему остальному отчёту, а не только к себе.
"""

from __future__ import annotations

import pytest

from horizon_analytics.domain.models import Posting, TermCandidate, Topic
from horizon_analytics.domain.pipeline import AnalysisPipeline, _RelevanceVerdict
from horizon_analytics.domain.scoring.profile import MethodologyProfile

PARAMETERS = MethodologyProfile.default().parameters


def topic(key: str, document_ids: list[str]) -> Topic:
    member = TermCandidate(
        key=key,
        surface=key,
        surface_forms=(key,),
        postings=tuple(
            Posting(document_id=document_id, occurrences=1) for document_id in sorted(document_ids)
        ),
        termhood=1.0,
        token_count=len(key.split(" ")),
    )
    return Topic(key=key, label=key, members=(member,))


class TestTheExplanationAgreesWithTheVerdict:
    """Причина отказа обязана называть то правило, по которому отказано."""

    def test_the_named_threshold_is_the_one_actually_applied(self) -> None:
        # Тот самый дефект: печатался статический параметр, а решал порог прогона. Разойдясь,
        # они превращают объяснение в опровержение: 0.0 не меньше 0.0, а тема отброшена.
        verdict = _RelevanceVerdict(measure={"t": 0.03}, cut=0.29, by_subjects=True)

        reason, detail = verdict.explain("t")

        assert detail["cut"] == pytest.approx(0.29)
        assert "29%" in reason

    def test_the_numbers_explain_the_refusal_rather_than_contradict_it(self) -> None:
        # Единственная проверка, которая поймала бы прежнее поведение вне зависимости от имён
        # полей: у отброшенной темы измеренная величина обязана быть ниже порога.
        verdict = _RelevanceVerdict(measure={"t": 0.03}, cut=0.29, by_subjects=True)

        _, detail = verdict.explain("t")

        assert float(detail["measure"]) < float(detail["cut"])

    def test_the_subject_regime_speaks_of_documents_not_of_closeness(self) -> None:
        # В режиме кодов рубрик мера — доля документов темы, отнесённых к направлению. Называть её
        # «близостью» значит описывать не то вычисление, которое произошло, и увести аналитика в
        # догадки про эмбеддинги там, где решали коды источника.
        reason, detail = _RelevanceVerdict(measure={"t": 0.0}, cut=0.29, by_subjects=True).explain(
            "t"
        )

        assert "классифицировано" in reason
        assert detail["rule"] == "subject-share"

    def test_the_fallback_regime_says_so_plainly(self) -> None:
        # Запасной путь — лексическая близость, и у неё нет корпусонезависимой шкалы. Аналитик,
        # видящий «0.12 при пороге 0.20», должен понимать, что это другое измерение.
        reason, detail = _RelevanceVerdict(
            measure={"t": 0.12}, cut=0.20, by_subjects=False
        ).explain("t")

        assert "близость" in reason
        assert detail["rule"] == "embedding-cut"

    def test_a_term_the_run_never_measured_is_reported_as_zero_not_as_a_crash(self) -> None:
        # Трассируют то, что запросил аналитик, а запросить он может что угодно.
        reason, detail = _RelevanceVerdict(measure={}, cut=0.29, by_subjects=True).explain("нет")

        assert detail["measure"] == pytest.approx(0.0)
        assert reason


class TestTheVerdictTravelsWithItsRun:
    """Диагностика принадлежит прогону, а не классу конвейера."""

    def test_the_pipeline_keeps_no_mutable_state_on_the_class(self) -> None:
        # Прежде измеренные величины складывались в словарь-атрибут класса и переприсваивались на
        # каждом прогоне. Последовательно это работает, поэтому ни один тест этого не замечал; два
        # одновременных анализа в одном процессе затирали друг другу диагностику, и трассировка
        # одного запроса показывала числа другого — молча и правдоподобно.
        #
        # Проверка структурная намеренно: воспроизвести гонку детерминированно нельзя, а запретить
        # её причину — можно.
        mutable = {
            name: value
            for name, value in vars(AnalysisPipeline).items()
            if isinstance(value, (dict, list, set))
        }

        assert mutable == {}

    def test_the_selection_returns_its_own_verdict(self) -> None:
        # Раз состояния на классе нет, решение обязано возвращаться наружу — иначе трассировке
        # неоткуда взять числа, и она снова начнёт брать их откуда придётся.
        documents = ["a", "b", "c", "d"]
        document_index = {document_id: index for index, document_id in enumerate(documents)}
        vectors = [[1.0, 0.0] for _ in documents]
        inside = topic("внутри", ["a", "b", "c", "d"])
        outside = topic("снаружи", ["c", "d"])

        kept, _dropped, verdict = AnalysisPipeline._select_relevant(
            [inside, outside],
            [0.0, 1.0],
            frozenset(),
            PARAMETERS,
            document_vectors=vectors,
            document_index=document_index,
            subject_relevance={"a": 1.0, "b": 1.0},
            corpus_size=len(documents),
        )

        assert verdict.by_subjects is True
        assert verdict.measure["внутри"] == pytest.approx(0.5)
        assert verdict.measure["снаружи"] == pytest.approx(0.0)
        # Порог выводится из базовой доли направления в корпусе (2 из 4), а не берётся из настроек.
        assert verdict.cut == pytest.approx(AnalysisPipeline._direction_cut(2 / 4, PARAMETERS))
        # Обе темы остались, и это не изъян: при слишком малом числе прошедших фильтр вырождается
        # в ранжирование и добирает лучшие — пустой отчёт неотличим для аналитика от «в этой
        # области ничего не происходит». Проверяется здесь именно поэтому: решение о добивке не
        # должно подменять собой объяснение. Тема, добранная правилом минимума, обязана оставаться
        # в вердикте той, что порога не взяла.
        assert "внутри" in [candidate.key for candidate in kept]
        assert verdict.measure["снаружи"] < verdict.cut


class TestTheDirectionCutDoesNotSaturate:
    """Порог предметного режима — удвоение шансов, а не доли.

    Удвоение доли при базовой доле ≥ 0,5 требует, чтобы направлением были размечены все документы
    темы до единого. Корпус продукт собирает под направление, и базовая доля там высока: 0,44 и
    0,53 на двух снапшотах стенда по ИИ 2026-09-18. На втором отчёт схлопнулся до одной темы.
    """

    def test_a_small_base_rate_keeps_the_old_meaning(self) -> None:
        # При малой доле «вдвое концентрированнее» по шансам и по доле почти совпадают.
        cut = AnalysisPipeline._direction_cut(0.05, PARAMETERS)

        assert cut == pytest.approx(2 * 0.05, abs=0.01)

    def test_a_high_base_rate_does_not_demand_purity(self) -> None:
        cut = AnalysisPipeline._direction_cut(0.53, PARAMETERS)

        assert cut < 0.75, "порог требует почти полной чистоты темы"

    def test_a_minority_direction_is_held_above_the_corpus(self) -> None:
        # Пока направление — меньшинство корпуса, правило прежнее: тема концентрированнее корпуса.
        for base in (0.05, 0.2, 0.3):
            assert AnalysisPipeline._direction_cut(base, PARAMETERS) > base

    def test_a_majority_direction_asks_for_the_majority(self) -> None:
        # Корпус Edge, 1800 документов живого сбора (разбор 101): база 0,93, порог по шансам 0,963.
        # Тема, у которой из двух размеченных работ одна в cs.DC, а другая в cs.NI, выбывала как
        # чужая. Когда направление — большинство корпуса, вопрос «принадлежит ли тема ему»
        # задаётся прямо: большая ли часть её размеченной литературы в нём.
        assert AnalysisPipeline._direction_cut(0.93, PARAMETERS) == pytest.approx(0.5)
        assert AnalysisPipeline._direction_cut(0.53, PARAMETERS) == pytest.approx(0.5)


class TestUnlabelledDocumentsAreNoEvidence:
    """Документ без предметных кодов не голосует ни «за» направление, ни «против».

    Crossref, Europe PMC и Hacker News кодов не присылают вовсе. Пока доля считалась по всем
    документам темы, каждый такой документ засчитывался как «чужое направление», и каждый новый
    источник размывал долю каждой темы: замер стенда 2026-09-18 — правило признало чужими 671
    тему из 871, отчёт по ИИ сжался до четырёх тем.
    """

    def test_unlabelled_documents_do_not_dilute_the_share(self) -> None:
        documents = ["a", "b", "c", "d"]
        document_index = {document_id: index for index, document_id in enumerate(documents)}
        vectors = [[1.0, 0.0] for _ in documents]
        mixed = topic("смешанная", ["a", "b", "c", "d"])

        share = AnalysisPipeline._relevance(
            mixed,
            vectors,
            document_index,
            [1.0, 0.0],
            subject_relevance={"a": 1.0, "b": 1.0},
            classified=frozenset({"a", "b"}),
        )

        assert share == pytest.approx(1.0), "неразмеченные c и d засчитаны как чужое направление"

    def test_a_topic_without_labelled_documents_has_no_evidence(self) -> None:
        documents = ["c", "d"]
        document_index = {document_id: index for index, document_id in enumerate(documents)}
        vectors = [[1.0, 0.0] for _ in documents]

        share = AnalysisPipeline._relevance(
            topic("без разметки", ["c", "d"]),
            vectors,
            document_index,
            [1.0, 0.0],
            subject_relevance={"a": 1.0},
            classified=frozenset({"a"}),
        )

        assert share == 0.0


class TestAnAnalystsWordingIsTraced:
    """`/explain` обязан понимать термин так, как его пишет аналитик.

    Ключи кандидатов стеммированы («speculative decod»), а наблюдение сравнивало их с сырым текстом
    напрямую — и не совпадало никогда: на любой обычный вопрос ответ был «никогда не извлекалась»,
    в том числе про тему, стоявшую в том же отчёте третьей (разбор 90).
    """

    def test_the_wording_is_matched_by_key_and_returned_as_asked(self) -> None:
        from horizon_analytics.domain.pipeline import _Tracer

        tracer = _Tracer.for_terms(frozenset({"Speculative Decoding"}), None)
        tracer.observe("speculative decod", "extracted")

        [trace] = tracer.finish(frozenset({"speculative decod"}))

        assert trace.key == "Speculative Decoding"
        assert trace.outcome == "ranked"
        assert trace.canonical_key is None

    def test_a_key_is_still_accepted(self) -> None:
        from horizon_analytics.domain.pipeline import _Tracer

        tracer = _Tracer.for_terms(frozenset({"speculative decod"}), None)
        tracer.observe("speculative decod", "extracted")

        [trace] = tracer.finish(frozenset({"speculative decod"}))

        assert trace.outcome == "ranked"


class TestAnUnlabelledTopicIsJudgedByItsNeighbours:
    """Тема без единого размеченного документа судится соседством, а не выбывает (разбор 101).

    Semantic Scholar, Europe PMC и Hacker News рубрик не присылают вовсе, и тема, которую писали
    только они, до сих пор получала долю 0 — «чужая» без единого свидетельства против.
    """

    def _select(self, topics: list[Topic], vectors: list[list[float]], ids: list[str]):
        return AnalysisPipeline._select_relevant(
            topics,
            [1.0, 0.0, 0.0],
            frozenset(),
            PARAMETERS,
            document_vectors=vectors,
            document_index={document_id: index for index, document_id in enumerate(ids)},
            subject_relevance={"in1": 1.0, "in2": 1.0},
            corpus_size=len(ids),
            classified=frozenset({"in1", "in2", "out1"}),
        )

    def test_a_topic_near_the_direction_is_kept_and_one_near_the_rest_is_not(self) -> None:
        ids = ["in1", "in2", "out1", "near", "far"]
        vectors = [
            [1.0, 0.0, 0.0],
            [0.9, 0.1, 0.0],
            [0.0, 1.0, 0.0],
            [0.95, 0.05, 0.0],
            [0.05, 0.95, 0.0],
        ]
        near = topic("рядом с направлением", ["near"])
        far = topic("рядом с чужим", ["far"])

        kept, _, verdict = self._select([near, far], vectors, ids)

        assert near.key in verdict.inferred and far.key in verdict.inferred
        assert verdict.measure[near.key] == pytest.approx(1.0)
        assert verdict.measure[far.key] == 0.0
        # Добивка по минимуму числа тем здесь тоже сработает — но добранная тема обязана остаться
        # в вердикте той, что порога не взяла.
        assert verdict.measure[far.key] < verdict.cut <= verdict.measure[near.key]
        assert near in kept
        reason, detail = verdict.explain(far.key)
        assert detail["rule"] == "subject-share-inferred"
        assert "рубрицировал" in reason

    def test_a_topic_with_a_labelled_document_is_judged_by_labels(self) -> None:
        ids = ["in1", "in2", "out1", "near"]
        vectors = [[1.0, 0.0, 0.0], [0.9, 0.1, 0.0], [0.0, 1.0, 0.0], [0.95, 0.05, 0.0]]
        labelled = topic("размеченная", ["out1", "near"])

        _, _, verdict = self._select([labelled], vectors, ids)

        assert labelled.key not in verdict.inferred
        assert verdict.measure[labelled.key] == 0.0
