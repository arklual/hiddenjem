"""Мотивация не повторяет саму себя.

Мотивация — первое, что аналитик читает в карточке тренда, и первое, что видит комитет. До этой
правки рядом оказывались два предложения, различающиеся только хвостом:

    «We show that the design enables linear-time inference over million-token contexts,
     which the published state of the art cannot provide.»
    «We show that the design enables linear-time inference over million-token contexts,
     which prior distillation pipelines cannot provide.»

Формально разные строки, поэтому отсев по равенству их пропускал. Для читателя это одно утверждение,
напечатанное дважды, — и ничто не выдаёт машину быстрее.

Обратная ошибка не менее вредна: два предложения с одинаковой рамкой, но разным содержанием
(«reduces latency by 38%» и «reduces error rate by 53%») — это два разных факта, и выбросить второй
значит обеднить отчёт молча. Проверки ниже держат обе границы.
"""

from __future__ import annotations

from tests.conftest import make_document, make_topic

from horizon_analytics.domain.evidence import EvidenceSelection
from horizon_analytics.domain.models import Evidence
from horizon_analytics.domain.narration.base import NarrationRequest
from horizon_analytics.domain.narration.extractive import ExtractiveNarrator, _says_the_same


class TestSaysTheSame:
    """Одно ли утверждение перед нами."""

    def test_the_same_body_with_a_swapped_tail_is_one_statement(self) -> None:
        # Тот самый случай, ради которого правка сделана.
        first = (
            "We show that the design enables linear-time inference over million-token contexts, "
            "which the published state of the art cannot provide."
        )
        second = (
            "We show that the design enables linear-time inference over million-token contexts, "
            "which prior distillation pipelines cannot provide."
        )

        assert _says_the_same(second, first)

    def test_the_same_frame_with_different_content_is_two_statements(self) -> None:
        # Общая у них только рамка «we show that the design enables … cannot provide», а сказано
        # разное. Выбросить второе значит потерять факт и не сказать об этом.
        first = (
            "We show that the design enables kernel-level observability with no application "
            "changes, which a manually maintained inventory cannot provide."
        )
        second = (
            "We show that the design enables policy enforcement at syscall granularity, "
            "which a manually maintained inventory cannot provide."
        )

        assert not _says_the_same(second, first)

    def test_two_measurements_of_different_things_are_two_statements(self) -> None:
        first = (
            "The method reduces end-to-end latency by 38% and achieves constant memory decoding "
            "on a held-out multilingual benchmark."
        )
        second = (
            "The method reduces error rate by 53% and achieves constant memory decoding "
            "on an internal production trace."
        )

        assert not _says_the_same(second, first)

    def test_a_literal_repeat_is_one_statement(self) -> None:
        sentence = "A key bottleneck is drift under distribution shift."

        assert _says_the_same(sentence, sentence)

    def test_unrelated_sentences_are_two_statements(self) -> None:
        assert not _says_the_same(
            "A key bottleneck is drift under distribution shift.",
            "The approach achieves a 3x speedup while reducing memory by 40%.",
        )

    def test_an_empty_sentence_never_swallows_another(self) -> None:
        # Пустая строка формально «содержится» в любой. Считать её повтором значило бы выбросить
        # настоящее предложение из-за пустого кандидата.
        assert not _says_the_same("", "A key bottleneck is drift under distribution shift.")
        assert not _says_the_same("A key bottleneck is drift under distribution shift.", "")

    def test_comparison_is_symmetric(self) -> None:
        # Порядок кандидатов зависит от их баллов, и решение не должно от него зависеть:
        # несимметричная мера дала бы разный отчёт при равных баллах (ADR-0015).
        first = "However, recovery time is limited by the absence of build-time provenance."
        second = (
            "However, recovery time is limited by the absence of build-time provenance "
            "and remains challenging outside curated benchmarks."
        )

        assert _says_the_same(first, second) == _says_the_same(second, first)

    def test_case_and_punctuation_do_not_make_two_statements(self) -> None:
        assert _says_the_same(
            "A KEY BOTTLENECK is drift under distribution shift!",
            "A key bottleneck is drift under distribution shift.",
        )


class TestMotivationDoesNotRepeatItself:
    """Отбор предложений обязан пользоваться этой мерой, а не равенством строк.

    Проверки выше держат саму меру. Эта — проводку: без неё снятие вызова из отбора прошло бы
    незамеченным, что и случилось при первой проверке несущести.
    """

    TAIL_ONLY = (
        "We show that the design enables linear-time inference over million-token contexts, "
        "which the published state of the art cannot provide.",
        "We show that the design enables linear-time inference over million-token contexts, "
        "which prior distillation pipelines cannot provide.",
    )
    DIFFERENT = (
        "We show that the design enables kernel-level observability with no application changes, "
        "which a manually maintained inventory cannot provide.",
        "We show that the design enables policy enforcement at syscall granularity, "
        "which a manually maintained inventory cannot provide.",
    )

    @staticmethod
    def _motivation(sentences: tuple[str, ...]) -> str:
        documents = tuple(
            make_document(f"doc-{index}", year=2024, title="Linear-time models", abstract=text)
            for index, text in enumerate(sentences)
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
        request = NarrationRequest(
            topic=make_topic("linear-time model", documents=[d.document_id for d in documents]),
            evidence=EvidenceSelection(items=items, documents=documents),
        )
        return ExtractiveNarrator().narrate(request).benefit

    def test_a_swapped_tail_is_printed_once(self) -> None:
        benefit = self._motivation(self.TAIL_ONLY)

        assert benefit.count("linear-time inference over million-token contexts") == 1

    def test_two_different_statements_are_both_printed(self) -> None:
        # Обратная граница: отбор, выбрасывающий всё похожее, обеднил бы отчёт молча.
        benefit = self._motivation(self.DIFFERENT)

        assert "kernel-level observability" in benefit
        assert "policy enforcement at syscall granularity" in benefit


class TestQuotesStandOnTheirOwn:
    """Цитата-проблема одинока так же, как определение, и правило обязано быть общим.

    В карточке и в записке проблема подаётся дословной цитатой: другого контекста у неё нет.
    Предложение, открывающееся «However», спорит с тем, чего читатель не видит, — и на эталонном
    корпусе так выглядели 29 формулировок проблемы из 60.

    Правило нельзя заменить обрезкой связки: продукт обещает дословность («перевод перестал бы быть
    цитатой»), а фраза со снятым первым словом уже не является тем, что написано в источнике.
    """

    @staticmethod
    def _narrate(sentences: tuple[str, ...]) -> tuple[str, str]:
        documents = tuple(
            make_document(f"doc-{index}", year=2024, title="Linear-time models", abstract=text)
            for index, text in enumerate(sentences)
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
        request = NarrationRequest(
            topic=make_topic("linear-time model", documents=[d.document_id for d in documents]),
            evidence=EvidenceSelection(items=items, documents=documents),
        )
        motivation = ExtractiveNarrator().narrate(request)
        return motivation.problem, motivation.benefit

    def test_prefers_the_standalone_sentence_over_the_continuation(self) -> None:
        # Оба предложения годятся по признакам проблемы, и оба про тему. Разница только в том, что
        # первое можно процитировать, а второе спорит с невидимым соседом.
        problem, _ = self._narrate(
            (
                "However, linear-time models suffer from draft-target mismatch on long contexts.",
                "Current linear-time models suffer from draft-target mismatch on long contexts.",
            )
        )

        assert problem.startswith("Current linear-time models")

    def test_falls_back_to_the_template_rather_than_quoting_a_continuation(self) -> None:
        # Обратная граница, и она важнее первой: правило, тихо пропускающее связку, когда другого
        # кандидата нет, оставило бы дефект ровно там, где источник беден, — а беден он чаще всего
        # у слабых сигналов, ради которых продукт и сделан.
        problem, _ = self._narrate(
            ("However, linear-time models suffer from draft-target mismatch on long contexts.",)
        )

        assert not problem.lower().startswith("however")

    def test_still_quotes_a_sentence_whose_connective_sits_inside_it(self) -> None:
        # Слишком широкое правило обеднило бы отчёт молча: связка в середине фразы — часть
        # рассуждения, а не объявление предложения продолжением.
        problem, _ = self._narrate(
            (
                "Linear-time models suffer from draft-target mismatch, however carefully they are tuned.",
            )
        )

        assert problem.startswith("Linear-time models suffer from")
