"""«Почему этой темы нет» называет правило, если тему сняло правило.

Трасса кандидата — единственный ответ аналитику, не нашедшему в отчёте имя, которое он ожидал. До
этой правки на любое такое имя приходило `never_extracted`: правда, из которой ничего не следует.

Причина устройства, а не небрежности. `_Tracer.drop` записывает только кандидатов, а правило имени
отклоняет n-грамму **до** того, как она станет кандидатом, — вызова `drop` для неё не бывает никогда.
Оба случая поэтому сходились в одну строку:

* формулировки нет в собранных документах — искать нечего;
* формулировка есть, и её сняло правило имени — искать нужно в правиле.

Различие существеннее прочих: по правилу границы теряются **настоящие** названия. разбор 30 (`docs/01-analysis/30-boundary-filter-findings.md`) называет
`high bandwidth memory`, `high availability cluster`, `availability zone`, и там же записано, почему
общего лечения нет. Пока лечения нет, аналитик обязан хотя бы узнавать причину — иначе он не сможет о
ней и сообщить.

Спрашивается само правило, а не догадка о нём: ответ «правило отклоняет вот по такой причине» верен
независимо от того, встречается формулировка в корпусе или нет.
"""

from __future__ import annotations

from horizon_analytics.domain.extraction.blacklist import GenericTermFilter
from horizon_analytics.domain.pipeline import _REJECTION_REASONS, _Tracer


def _trace(term: str) -> tuple[str, str, str | None, dict[str, object]]:
    tracer = _Tracer(watch=frozenset({term}), term_filter=GenericTermFilter.default())
    records = tracer.finish(frozenset())
    assert len(records) == 1
    record = records[0]
    return record.stage, record.outcome, record.reason, dict(record.detail)


class TestARuleThatRemovedTheNameSaysSo:
    def test_a_boundary_rejection_names_the_rule(self) -> None:
        stage, outcome, reason, detail = _trace("strong transformer baselines")

        assert stage == "term_filter"
        assert outcome == "dropped"
        assert detail["rule"] == "generic_boundary"
        assert reason is not None and "границ" in reason

    def test_the_reason_is_a_sentence_and_not_a_machine_label(self) -> None:
        # Метка `generic_boundary` ничего не сообщает читателю отчёта, а вопрос задают именно про
        # неё. Машинная метка остаётся в деталях — для того, кто пойдёт править правило.
        _, _, reason, detail = _trace("weak correlation between")

        assert reason != detail["rule"]
        assert reason is not None and len(reason) > 30

    def test_a_phrase_the_rule_allows_still_reads_as_never_extracted(self) -> None:
        # Обратная сторона: сказать «снято правилом» о формулировке, которую правило пропускает,
        # значило бы заменить один бесполезный ответ другим, уже неверным.
        stage, outcome, reason, detail = _trace("high bandwidth memory")

        assert (stage, outcome, reason) == ("extracted", "absent", "never_extracted")
        assert detail == {}

    def test_a_phrase_nobody_wrote_reads_as_never_extracted(self) -> None:
        stage, outcome, reason, _ = _trace("совершенно несуществующая формулировка")

        assert (stage, outcome, reason) == ("extracted", "absent", "never_extracted")


class TestEveryRuleHasWordsForTheAnalyst:
    def test_every_label_the_filter_can_return_is_translated(self) -> None:
        # Метка без формулировки покажет аналитику `no_alphabetic_token` — то же самое, что и
        # раньше, только другими буквами.
        labels = {
            "empty",
            "too_short",
            "too_long",
            "numeric_only",
            "no_alphabetic_token",
            "generic_term",
            "generic_boundary",
            "clause_fragment",
            "trailing_preposition",
            "participle_tail",
        }

        assert labels <= set(_REJECTION_REASONS)

    def test_no_translation_outlives_the_label_it_explains(self) -> None:
        # Обратная сторона: формулировка для метки, которой правило больше не возвращает, — запись о
        # несуществующем, и следующий читатель примет её за действующую.
        import inspect

        from horizon_analytics.domain.extraction import blacklist

        text = inspect.getsource(blacklist.GenericTermFilter.rejects)

        for label in _REJECTION_REASONS:
            assert f'"{label}"' in text, label


class TestTheTracerStaysSilentWithoutAWatchList:
    def test_no_watch_means_no_records(self) -> None:
        # Трасса собирается только по явному списку наблюдения: иначе каждый прогон таскал бы
        # диагностику по тысячам кандидатов, которую никто не спрашивал.
        assert _Tracer(watch=frozenset()).finish(frozenset()) == ()
