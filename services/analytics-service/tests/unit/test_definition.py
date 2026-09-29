"""Определение тренда — то, что аналитик читает первым и цитирует комитету.

Найдено разбором настоящего отчёта по золотому корпусу, а не чтением кода. Четыре карточки из
пятнадцати ставили определением ровно ту фразу, которая двумя строками ниже стояла проблемой:

    определение: «In known systems, draft-target mismatch under distribution shift is limited by
                  the available memory footprint, and reliable operation remains challenging.»
    проблема:    «… In known systems, draft-target mismatch under distribution shift is limited by
                  the available memory footprint, and reliable operation remains challenging.»

Два поля с разными подписями и одинаковым содержимым читаются как незаполненные — и читаются верно.
Причина проста: определение и мотивация выбирали предложения независимо и из одних и тех же
аннотаций. Ничто не мешало им выбрать одно.

Ещё шесть карточек получали определением продолжение чужой мысли: «However, deployments still
suffer from…». Такое предложение ссылается на предшествующее, которого аналитик не увидит; в роли
определения оно не объясняет тему, а обрывает спор на середине. Определение — единственное поле,
которое стоит вырванным из текста совсем без соседей, поэтому требование к нему строже, чем к
цитатам мотивации: цитата обязана сохранять смысл в отрыве от источника.
"""

from __future__ import annotations

from tests.conftest import make_document, make_topic

from horizon_analytics.domain.evidence import EvidenceSelection
from horizon_analytics.domain.models import Evidence
from horizon_analytics.domain.narration.extractive import _stands_on_its_own, build_definition

TERM = "linear-time model"


def evidence_of(*abstracts: str, year: int = 2024) -> EvidenceSelection:
    documents = tuple(
        make_document(f"doc-{index}", year=year, title="Linear-time models", abstract=text)
        for index, text in enumerate(abstracts)
    )
    items = tuple(
        Evidence(
            source_id="arxiv",
            source_class="PREPRINT",
            title=document.title,
            published_on=document.published_on,
            url=document.url,
            relevance=1.0,
            document_id=document.document_id,
        )
        for document in documents
    )
    return EvidenceSelection(items=items, documents=documents)


def definition_of(*abstracts: str, avoid: tuple[str, ...] = ()) -> str:
    evidence = evidence_of(*abstracts)
    topic = make_topic(TERM, documents=[document.document_id for document in evidence.documents])
    return build_definition(topic, evidence, avoid=avoid)


class TestTheDefinitionDoesNotRepeatTheMotivation:
    SENTENCE = (
        "A linear-time model processes a million-token context without quadratic attention cost."
    )

    def test_a_sentence_the_motivation_already_used_is_not_repeated(self) -> None:
        # Тот самый дефект: буквальный повтор в двух полях одной карточки.
        assert definition_of(self.SENTENCE, avoid=(self.SENTENCE,)) != self.SENTENCE

    def test_a_swapped_tail_does_not_bring_the_repeat_back(self) -> None:
        # Отсев по равенству строк обошёлся бы сменой хвоста, а для читателя это то же утверждение.
        # Мера здесь та же, что внутри самой мотивации, — иначе правило обходится опечаткой.
        near = (
            "A linear-time model processes a million-token context without quadratic attention "
            "overhead."
        )

        assert definition_of(self.SENTENCE, avoid=(near,)) != self.SENTENCE

    def test_an_unrelated_sentence_is_still_quoted(self) -> None:
        # Обратная ошибка: правило, отбрасывающее лишнее, обедняет отчёт молча. Определение должно
        # исчезать только тогда, когда его действительно уже сказали.
        assert definition_of(self.SENTENCE, avoid=("Something else entirely happens here.",)) == (
            self.SENTENCE
        )

    def test_the_fallback_states_measured_facts_rather_than_nothing(self) -> None:
        # Когда цитировать нечего, поле не пустеет: сводка честно говорит, по скольким документам и
        # за какой период тема выделена. Это хуже хорошей цитаты и лучше повтора.
        definition = definition_of(self.SENTENCE, avoid=(self.SENTENCE,))

        assert "выделена по" in definition
        assert "2024" in definition


class TestTheQuoteStandsOnItsOwn:
    def test_a_sentence_opening_with_a_connective_is_not_quoted(self) -> None:
        # «However, …» ссылается на предшествующее, которого в карточке нет. Аналитик получает
        # обрывок спора вместо объяснения темы.
        dangling = (
            "However, a linear-time model still suffers from recall loss on distant tokens in "
            "streaming inference."
        )

        assert definition_of(dangling) != dangling
        assert "выделена по" in definition_of(dangling)

    def test_a_connective_inside_the_sentence_is_harmless(self) -> None:
        # Проверяется первое слово, а не наличие связки: в середине фразы она часть рассуждения и
        # смысла в отрыве не отнимает. Правило, срабатывающее на любую связку, выбросило бы
        # половину годных предложений.
        fine = (
            "A linear-time model keeps constant memory; however, its recall on distant tokens "
            "depends on the state size."
        )

        assert definition_of(fine) == fine

    def test_the_rule_is_about_the_opening_word_only(self) -> None:
        assert _stands_on_its_own("Therefore the system scales.") is False
        assert _stands_on_its_own("The system scales; therefore latency falls.") is True
        assert _stands_on_its_own("") is False

    def test_the_list_stays_narrow_enough_to_keep_ordinary_sentences(self) -> None:
        # Слишком широкий список вернул бы карточку к сухому шаблону там, где источник сказал по
        # делу. Проверяются открывающие слова, которые встречаются в определениях постоянно.
        for opening in ("The", "A", "This", "We", "Our", "Existing", "Recent"):
            assert _stands_on_its_own(f"{opening} approach defines the term precisely enough.")


class TestADefinitionIsNotAProblemStatement:
    """Проблема под подписью «Определение» — два поля одного рода с разными подписями.

    Карточка показывает определение, а строкой ниже — «Проблема: цитата». Когда определением
    становится «A key bottleneck is …», аналитик читает две формулировки проблемы под разными
    именами; такая карточка читается как незаполненная, и читается верно.

    Замер на эталонном корпусе: семь из тридцати содержательных определений были формулировками
    проблемы. После правила — ноль, а шаблонных определений стало 67 из 90 вместо 60. Обмен
    осознан: шаблон честно говорит, по скольким документам и за какой период тема выделена, а
    проблема под чужой подписью не говорит ничего.

    Правило смотрит только на **сильные** признаки проблемы. Слабые — «however», «challenge» —
    встречаются в середине обычных предложений и назначения не выдают; на них первая редакция
    правила выбросила годное определение, и соседняя проверка это поймала.
    """

    # Предложения обязаны содержать сам термин темы: иначе они отбрасываются раньше проверяемого
    # правила — по несовпадению основ, — и проверка проходит по неверной причине. Первая редакция
    # этих двух проверок была именно такой, и подмена это показала.
    def test_a_bottleneck_sentence_is_not_a_definition(self) -> None:
        sentence = (
            "A key bottleneck is the memory footprint of the linear-time model in production."
        )

        assert definition_of(sentence) != sentence

    def test_a_limited_by_sentence_is_not_a_definition(self) -> None:
        sentence = "In known systems, the linear-time model is limited by the available throughput."

        assert definition_of(sentence) != sentence

    def test_a_weak_marker_inside_a_sentence_does_not_disqualify_it(self) -> None:
        # Обратная сторона: правило по слабым признакам выбросило бы годное определение, и карточка
        # осталась бы на шаблоне без причины.
        fine = (
            "A linear-time model keeps constant memory; however, its recall depends on state size."
        )

        assert definition_of(fine) == fine
