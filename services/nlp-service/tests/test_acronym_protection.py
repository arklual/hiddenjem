"""Аббревиатуры источника не должны превращаться в выдуманные русские.

Модель-переводчик обучена переводить слова, и аббревиатуру она переводит тоже — по буквам.
Замер 2026-09-18 на opus-mt-en-ru с настоящими определениями тем из отчёта по кибербезопасности:
`LLM` становился «ЛММ» в одном месте и «УЗМ» в другом, `MCP` — «МКП». Ни одной из этих
аббревиатур в русском нет, и главное — по ним нельзя вернуться к источнику, а вся ценность
выдачи в проверяемости.

Защита — кавычки вокруг самой аббревиатуры, и это тоже результат замера: подстановка `ZZQ0`
держится в середине фразы и транслитерируется в начале («ЗЦКВ0 трубопроводы»), `__0__`
разрушается полностью, а кавычки держатся везде.
"""

from __future__ import annotations

from horizon_nlp.translation import _restore, protect_acronyms


def test_an_acronym_is_protected_from_the_model() -> None:
    protected_text, tokens = protect_acronyms("Agents built on LLM use MCP tools")

    assert '"LLM"' in protected_text
    assert '"MCP"' in protected_text
    assert sorted(tokens) == ["LLM", "MCP"]


def test_every_occurrence_is_protected() -> None:
    """Защита нужна каждому вхождению: незакавыченное модель переведёт по буквам."""
    protected_text, tokens = protect_acronyms("LLM agents evaluate LLM outputs")

    assert protected_text.count('"LLM"') == 2
    assert tokens == ("LLM",)


def test_an_already_quoted_acronym_is_not_quoted_twice() -> None:
    """Вложенные кавычки модель воспроизводит как попало, и вычистить их потом нечем."""
    protected_text, tokens = protect_acronyms('The "LLM" boundary')

    assert protected_text == 'The "LLM" boundary'
    assert tokens == ("LLM",)


def test_an_acronym_with_a_russian_form_is_protected_too() -> None:
    """У «AI» есть «ИИ», но подставляется он после перевода, а не доверяется модели.

    Замер 2026-09-18: на одной фразе модель даёт «безопасности ИИ», на другой — «безопасности
    АИ», и зависит это от остатка предложения. Русский вид надёжнее подставить самому.
    """
    protected_text, tokens = protect_acronyms("AI and LLM safety")

    assert protected_text == '"AI" and "LLM" safety'
    assert sorted(tokens) == ["AI", "LLM"]


def test_a_canonical_acronym_becomes_russian_after_translation() -> None:
    """«AI» в переводе становится «ИИ» — детерминированно, а не по настроению модели."""
    restored, lost = _restore('контрольные показатели безопасности AI', ("AI",))

    assert restored == "контрольные показатели безопасности ИИ"
    assert lost == 0


def test_an_invented_abbreviation_is_swapped_back() -> None:
    """Придуманную русскую аббревиатуру заменяет исходная — при однозначном соответствии."""
    restored, lost = _restore("агентов на основе системы «ЛУЗР»", ("LLM",))

    assert "LLM" in restored
    assert "ЛУЗР" not in restored
    assert lost == 0


def test_two_lost_acronyms_are_not_guessed() -> None:
    """Догадка в выдаче, которая обещает проверяемость, хуже честного пропуска."""
    restored, lost = _restore("системы ЛУЗР и МКП", ("LLM", "MCP"))

    assert restored == "системы ЛУЗР и МКП"
    assert lost == 2


def test_a_real_russian_abbreviation_is_left_alone() -> None:
    """«ИИ» — настоящая русская аббревиатура, и восстановление её не трогает."""
    restored, lost = _restore("безопасность ИИ и системы ЛУЗР", ("LLM",))

    assert "LLM" in restored
    assert "ИИ" in restored
    assert lost == 0


def test_ordinary_words_are_not_touched() -> None:
    """Обычный текст проходит без изменений: защищается форма, а не всё подряд."""
    text = "Tool-using agents create a new supply-chain surface"

    protected_text, tokens = protect_acronyms(text)

    assert protected_text == text
    assert tokens == ()


def test_a_hyphenated_compound_keeps_its_acronym() -> None:
    """`LLM-based` — самая частая форма в аннотациях, и защита обязана её выдерживать."""
    protected_text, tokens = protect_acronyms("LLM-based autonomous agents")

    assert protected_text.startswith('"LLM"-based')
    assert tokens == ("LLM",)
