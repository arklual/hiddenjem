"""Имя технологии — именная группа, а не обрывок предложения.

Найдено чтением настоящего отчёта. Комитету под номером 14 предлагался тренд под названием
«arrangement enables cheap knowledge»: существительное слева, существительное справа, личная форма
глагола посередине. Правило границы такое не достаёт — оно проверяет только первое и последнее
слово, — а n-граммное окно вырезает подобные куски из каждого предложения с «enables».

Критерий структурный и потому не зависит от корпуса: внутри именной группы личной формы глагола
быть не может. Это принципиально отличается от списка неудачных существительных («stale documents»,
«retrieved passages»), который пришлось бы подбирать под конкретную выдачу — то есть подгонять
продукт под фикстуру.

Опасность правила — обратная: слишком широкий список вычеркнет настоящие технологии. Ключевую роль
играет стемминг: если основа глагола совпадёт с основой слова, законно стоящего внутри имени,
правило молча вырежет целое семейство. Поэтому список курирован по прогону стеммера, а не на глаз,
и `TestTheListCannotSwallowRealNames` делает это правило отбора исполняемым.
"""

from __future__ import annotations

import pytest

from horizon_analytics.domain.extraction import blacklist
from horizon_analytics.domain.extraction.blacklist import (
    GenericTermFilter,
    load_boundary_terms,
    load_clause_verbs,
    stem_phrase,
)
from horizon_analytics.domain.extraction.normalization import normalize_tokens
from horizon_analytics.domain.extraction.tokenizer import tokenize


def verdict(phrase: str) -> str | None:
    """Приговор фильтра по умолчанию — по тем же ключам, что строит конвейер."""
    return GenericTermFilter.default().rejects(
        stem_phrase(phrase), [token.normal for token in tokenize(phrase)]
    )


class TestAClauseIsNotAName:
    def test_a_verb_in_the_middle_rejects_the_candidate(self) -> None:
        # Тот самый обрывок из отчёта.
        assert verdict("arrangement enables cheap knowledge") == "clause_fragment"

    @pytest.mark.parametrize(
        "fragment",
        [
            "system enables hardware-friendly inference",
            "method achieves constant memory decoding",
            "compiler provides higher throughput",
            "scheduler requires fewer tokens",
        ],
    )
    def test_the_whole_family_goes_at_once(self, fragment: str) -> None:
        # Обрывков по одному на каждое сочетание «глагол × технология», поэтому перечислять фразы
        # в чёрном списке бесполезно: список пришлось бы вести по декартову произведению.
        assert verdict(fragment) == "clause_fragment"

    @pytest.mark.parametrize(
        "fragment",
        [
            "applies error mitigation",
            "violates independent-error",
            "break existing protocol",
            "fabricating quantum key distribution",
            "applies techno-economic modelling",
        ],
    )
    def test_the_verbs_found_by_reading_the_output_are_rejected(self, fragment: str) -> None:
        # Пять имён, занимавших места в ТОП-15 по трём направлениям на эталонном корпусе. Список
        # клаузальных глаголов пополняется не догадкой, а чтением выдачи; цена пополнения измерена
        # приёмкой — она не изменилась.
        #
        # Проверяется отвержение, а не имя правила. Часть этих обрывков перехватывает правило
        # границы, часть — клаузальное, и требовать конкретного ярлыка значило бы закрепить, каким
        # именно путём продукт приходит к верному ответу. Ярлык — диагностика, а не поведение.
        assert verdict(fragment) is not None

    @pytest.mark.parametrize(
        "name",
        [
            "cutting-edge accelerator",
            "benchmark suite",
            "cut-through routing",
            "applied cryptography",
        ],
    )
    def test_the_new_verbs_do_not_take_legitimate_names_with_them(self, name: str) -> None:
        # Обратная сторона пополнения, и она дороже. `cut` и `benchmark` отвергнуты именно здесь:
        # основа `cut` совпадает с основой `cutting`, а `benchmark` — законное существительное
        # имени технологии. Отвергнуты замером основ, а не осторожностью.
        assert verdict(name) != "clause_fragment"

    def test_the_third_person_form_of_a_y_verb_is_generated_correctly(self) -> None:
        # `apply` порождал `applys` — формы, которой в языке нет. Она не ловит ничего и создаёт
        # видимость покрытия: запись в списке есть, обрывок проходит мимо. Здесь его перехватывало
        # соседнее правило границы, поэтому дефект был не виден по выдаче — только по формам.
        from horizon_analytics.domain.extraction.blacklist import load_clause_verb_forms

        forms = load_clause_verb_forms()

        assert "applies" in forms
        assert "applys" not in forms

    def test_a_verb_at_the_boundary_is_still_rejected(self) -> None:
        # Перенос глаголов в отдельный файл не должен ослабить прежнее правило: `boundary_terms`
        # объединяется с ними при загрузке. Забыть объединение — значит починить середину и сломать
        # край, ничего при этом не заметив.
        assert verdict("enables linear-time inference") is not None

    def test_the_two_lists_are_joined_at_load(self) -> None:
        assert load_clause_verbs() <= load_boundary_terms()


class TestRealNamesSurvive:
    @pytest.mark.parametrize(
        "name",
        [
            "kernel-level observability",
            "provider network",
            "requirement traceability",
            "retrieval augmented generation",
            "linear-time models",
            "speculative decoding",
            "mechanistic interpretability",
            "sparse routing",
        ],
    )
    def test_a_noun_phrase_passes(self, name: str) -> None:
        # Ложное срабатывание здесь дороже пропуска: продукт существует, чтобы находить технологии,
        # и вычеркнутая настоящая тема не оставляет следа нигде.
        assert verdict(name) != "clause_fragment"


class TestTheListCannotSwallowRealNames:
    """Правило отбора самого списка, сделанное исполняемым.

    Список курирован так: глагол попадает в него, только если его основа не совпадает с основой
    слова, законно встречающегося внутри имени технологии. «support» осталось за бортом из-за
    support vector machine, «address» — из-за address translation, «build», «report», «yield»,
    «study», «leverage», «meet» — по тому же признаку.

    Без этой проверки правило отбора живёт только в комментарии к файлу ресурсов, а комментарий не
    мешает дописать туда строку. Одно слово — и целое семейство настоящих технологий исчезает из
    выдачи молча: отброшенная тема не оставляет следа нигде.
    """

    #: Слова, которые обязаны сохранять право стоять внутри имени технологии.
    LEGITIMATE = (
        "support",
        "address",
        "build",
        "report",
        "yield",
        "study",
        "leverage",
        "meet",
        "observability",
        "provider",
        "requirement",
        "extension",
        "reduction",
        "improvement",
    )

    @pytest.mark.parametrize("word", LEGITIMATE)
    def test_no_clause_verb_shares_a_stem_with_a_legitimate_word(self, word: str) -> None:
        assert stem_phrase(word) not in load_clause_verbs()

    def test_the_list_is_not_empty(self) -> None:
        # Пустой файл ресурсов обезвредил бы правило, и все проверки выше остались бы зелёными —
        # кроме этой: они спрашивают «отвергается ли обрывок», а не «существует ли список».
        assert len(load_clause_verbs()) >= 20


class TestAParticipleTailIsNotAName:
    """Причастие последним словом означает, что окно оборвало фразу на определении.

    «memory footprint of retrieval-augmented» — это начало «…of retrieval-augmented generation»,
    и в отчёте оно занимало первое место, то есть первое, что видит комитет. Причастие
    характеризует, а название называет; имя технологии причастием не заканчивается.

    Опасность правила — существительные, оканчивающиеся на те же две буквы: speed, feed, threshold,
    yield, field, method, period, grid. Отличаются они не длиной и не списком исключений, а самим
    стеммером: у причастия основа меняется, у существительного нет. Проверяется исходная словоформа,
    потому что в ключе окончание уже срезано — первая версия проверки смотрела на ключ и не ловила
    ничего, оставаясь при этом зелёной.
    """

    @pytest.mark.parametrize(
        "fragment",
        [
            "memory footprint of retrieval-augmented",
            "scheduler latency batched",
            "cache eviction fully automated",
        ],
    )
    def test_a_trailing_participle_rejects_the_candidate(self, fragment: str) -> None:
        assert verdict(fragment) == "participle_tail"

    @pytest.mark.parametrize(
        "name",
        [
            "retrieval augmented generation",
            "distributed ledger technology",
            "trusted execution environment",
            "supervised fine tuning",
        ],
    )
    def test_a_participle_inside_the_name_is_normal(self, name: str) -> None:
        # Внутри имени причастие — обычное определение. Правило про конец, а не про наличие.
        assert verdict(name) is None

    @pytest.mark.parametrize(
        "word", ["speed", "feed", "seed", "threshold", "yield", "field", "method", "period", "grid"]
    )
    def test_a_noun_that_merely_ends_in_those_letters_is_kept(self, word: str) -> None:
        # Список исключений здесь был бы вечно неполным. Признак берётся у стеммера: он и решает,
        # что «augmented» это словоформа, а «speed» — слово.
        assert stem_phrase(word) == word
        assert verdict(f"adaptive {word}") != "participle_tail"


class TestGerundAndParticipleAreNotTheSameThing:
    """Стеммер их не различает, а различать необходимо.

    Найдено чтением настоящего отчёта по безопасности: обрывок «reducing mean time» стоял в выдаче,
    хотя `reduce` перечислен в списке запрещённых везде. Причина — расхождение основ: `stem("reduce")`
    даёт «reduce», а `stem("reducing")` — «reduc», и запись не срабатывала. Так было у 17 записей из
    27: правило существовало, выглядело работающим и половину форм пропускало.

    Очевидная починка — порождать словоформы и стеммировать их — поймала обрывки и вычеркнула
    настоящие технологии: `stem("combining")` и `stem("combined")` — одна основа «combin», поэтому
    запрет герундия автоматически запрещал причастие, а с ним `reduced instruction set computer`
    (RISC), `combined heat and power`, `combined cycle gas turbine`. **По основам это различение
    невыразимо в принципе**, поэтому сравнение ведётся по исходным словоформам.

    Само различение уже проведено в другом месте и здесь только соблюдается: причастие в конце имени
    — оборванная фраза, в начале и середине — обычное определение.
    """

    @pytest.mark.parametrize(
        "fragment",
        ["reducing mean time", "providing higher throughput", "enabling cheap updates"],
    )
    def test_a_gerund_of_a_listed_verb_is_rejected(self, fragment: str) -> None:
        assert verdict(fragment) == "clause_fragment"

    @pytest.mark.parametrize(
        "name",
        [
            "reduced instruction set computer",
            "combined heat and power",
            "combined cycle gas turbine",
        ],
    )
    def test_a_participle_of_the_same_verb_opens_a_real_name(self, name: str) -> None:
        # Ровно те названия, которые потеряла попытка чинить разрыв основ стеммингом словоформ.
        assert verdict(name) is None

    def test_the_generated_forms_stop_at_the_gerund(self) -> None:
        from horizon_analytics.domain.extraction.blacklist import _regular_inflections

        forms = set(_regular_inflections("reduce"))

        assert {"reduce", "reduces", "reducing"} <= forms
        assert "reduced" not in forms

    def test_the_forms_are_kept_unstemmed(self) -> None:
        from horizon_analytics.domain.extraction.blacklist import load_clause_verb_forms

        forms = load_clause_verb_forms()

        # Если сюда попадут основы, различение исчезнет тем же способом, каким исчезало раньше.
        assert "reducing" in forms
        assert "reduced" not in forms


class TestASentencePrepositionIsNotPartOfAName:
    """Связка, соединяющая предложение, — след того, что окно вырезало кусок фразы.

    Найдено в отчёте по безопасности: «grown quickly across retail» стояло на шестом месте с баллом
    41.00 — выше `eBPF runtime security` и `confidential computing`. Имя технологии таких связок не
    содержит: они соединяют предложение, а не именуют вещь.

    «of» исключён намеренно и это измерено: на нём держатся `software bill of materials`,
    `mixture of experts`, `internet of things`, `denial of service`, `quality of service`,
    `proof of stake`. Запрет всех предлогов разом стоил бы шести настоящих названий из пятидесяти
    восьми; список без «of» — ни одного.

    Замер после правки: обрывок ушёл, `wallet compatibility across bundler` сократилось до
    `wallet compatibility`, а `software bill of materials` поднялась с семнадцатого места на
    шестнадцатое. Провал приёмки security 1/2 этим не закрыт — выше остаются грамматически
    правильные именные группы, отличающиеся от имени технологии только смыслом, и на них списки слов
    не действуют по установленной ранее причине.
    """

    @pytest.mark.parametrize(
        "fragment",
        [
            "grown quickly across retail",
            "wallet compatibility across bundler",
            "inference under load",
            "throughput with batching",
            "latency when streaming",
        ],
    )
    def test_a_sentence_preposition_rejects_the_candidate(self, fragment: str) -> None:
        assert verdict(fragment) == "clause_fragment"

    @pytest.mark.parametrize(
        "name",
        [
            "software bill of materials",
            "mixture of experts",
            "internet of things",
            "denial of service",
            "quality of service",
            "proof of stake",
        ],
    )
    def test_names_built_on_of_survive(self, name: str) -> None:
        # Ровно те шесть, которые потерял бы запрет всех предлогов разом.
        assert verdict(name) is None


class TestATrailingPrepositionIsNotAName:
    """Предлог последним словом — оборванная фраза, а не имя.

    «energy density gap versus» занимало второе место в отчёте по накопителям энергии: это начало
    фразы «…versus lithium-ion», отрезанное окном извлечения. Читатель видит сравнение, у которого
    нет второй половины.

    Правило устроено отдельно от «предлог в любом месте» и по более широкому списку — и это главное
    в нём. `of`, `for`, `to`, `by` законны внутри имени: на них держатся «software bill of
    materials», «system on chip», «point to point». В хвостовой позиции те же слова невозможны, и
    цена ошибки противоположна: запретив их везде, продукт потерял бы настоящие названия.
    """

    @pytest.mark.parametrize(
        "fragment",
        [
            "energy density gap versus",
            "memory footprint of",
            "throughput improvement for",
            "latency reduction by",
            "training pipeline in",
        ],
    )
    def test_a_name_never_ends_with_a_preposition(self, fragment: str) -> None:
        assert verdict(fragment) == "trailing_preposition"

    @pytest.mark.parametrize(
        "name",
        [
            "software bill of materials",
            "system on chip",
            "point to point",
            "internet of things",
            "quality of service",
        ],
    )
    def test_the_same_prepositions_stay_legitimate_inside_a_name(self, name: str) -> None:
        # Обратная сторона правила, и она дороже: эти названия настоящие, и на слишком широком
        # запрете предлогов этот проект уже терял три из них.
        assert verdict(name) is None


class TestAnEvaluativeAdjectiveDoesNotOpenAName:
    """`available cycle life`, `minus twenty` — обрывки, а не имена.

    Оба занимали места в ТОП-15: первое — по квантовым вычислениям и накопителям энергии, второе
    стояло первым в отчёте по энергии. Это те же оценочные прилагательные, что уже перечислены в
    `boundary_terms.txt` (`existing`, `current`, `prior`), просто не дописанные туда.

    `concrete` рассмотрен и отвергнут: это не только оценка, но и материал («concrete 3D
    printing»), и запрет сузил бы выдачу за пределами корпуса, на котором мерили.
    """

    @pytest.mark.parametrize(
        "fragment", ["available cycle life", "available readout error", "minus twenty"]
    )
    def test_the_fragment_is_rejected(self, fragment: str) -> None:
        assert verdict(fragment) == "generic_boundary"

    @pytest.mark.parametrize("name", ["cycle life", "readout error", "concrete 3d printing"])
    def test_the_name_underneath_survives(self, name: str) -> None:
        # Обратная сторона: запрет стоит на границе, а не на слове. «cycle life» — настоящее имя, и
        # оно обязано пережить удаление приставшего к нему прилагательного.
        assert verdict(name) is None


class TestAnEstablishedNamePassesTheBoundary:
    """«large language model» — имя, «large transaction batches» — обрывок, устроены одинаково.

    Различает их только внешнее знание, и оно вынесено в короткий ведомый вручную список. Размер
    списка измерен, а не предположен: среди фраз эталонного корпуса, начинающихся со степенного
    прилагательного и встречающихся не реже трёх раз, настоящих имён оказалось два из тридцати трёх.

    README называет «large language model» среди двадцати настоящих имён, которые старое правило
    границы теряет. Одно из двадцати возвращено — списком, а не подкруткой порога.
    """

    @pytest.mark.parametrize(
        "name",
        [
            "large language model",
            "large language models",
            "small modular reactor",
            "high bandwidth memory",
            "high availability cluster",
        ],
    )
    def test_an_established_name_survives(self, name: str) -> None:
        assert verdict(name) is None

    @pytest.mark.parametrize(
        "fragment",
        [
            "large transaction batches",
            "strong transformer baselines",
            "higher doses",
            "low temperature",
        ],
    )
    def test_a_fragment_of_the_same_shape_still_goes(self, fragment: str) -> None:
        # Обратная сторона, и она дороже списка: исключение не должно разрешать всё, что начинается
        # со степенного прилагательного. Иначе правило границы отменено, а не уточнено.
        assert verdict(fragment) == "generic_boundary"

    def test_the_list_is_not_empty(self) -> None:
        # Пустой файл превратил бы исключение в ничто, и проверки выше остались бы зелёными не
        # потому, что правило работает.
        from horizon_analytics.domain.extraction.blacklist import load_established_names

        assert len(load_established_names()) >= 5


class TestAttributiveTail:
    """Атрибутивное слово не может закрывать имя технологии.

    Найдено прогоном на настоящих данных OpenAlex, а не на эталонном корпусе: в направлении
    «квантовые вычисления» шесть тем из одиннадцати оказались обрывками с отрезанной вершиной —
    «field of quantum», «various quantum», «robust quantum», «global quantum», «great promise of
    quantum». В ИИ так пришло «performance of deep neural».

    Список отдельный от `boundary_terms.txt` именно потому, что там запрет действует на обеих
    границах: «quantum» в том списке убил бы «quantum error correction» — то есть настоящие
    названия, ради которых продукт существует.
    """

    @pytest.mark.parametrize(
        "phrase",
        [
            "field of quantum",
            "various quantum",
            "robust quantum",
            "performance of deep neural",
        ],
    )
    def test_a_candidate_ending_in_an_attributive_word_is_a_fragment(self, phrase: str) -> None:
        filter_ = GenericTermFilter.default()
        assert (
            filter_.rejects(normalize_tokens(phrase.split()), phrase.split()) == "attributive_tail"
        )

    @pytest.mark.parametrize(
        "phrase",
        [
            "quantum error correction",
            "quantum key distribution",
            "quantum machine learning",
            "neural architecture search",
            "superconducting transmon qubits",
        ],
    )
    def test_the_same_word_at_the_start_or_inside_is_legitimate(self, phrase: str) -> None:
        # Ровно та причина, по которой список отдельный: запрет на обеих границах уничтожил бы эти
        # названия, и проверка существует, чтобы такая правка не прошла молча.
        filter_ = GenericTermFilter.default()
        assert filter_.rejects(normalize_tokens(phrase.split()), phrase.split()) is None


# ───────── у каждой метки отказа есть объяснение ─────────
#
# Метки машинные, а вопрос «куда делось это имя» аналитик задаёт именно про них. Правило
# `attributive_tail` прожило без объяснения от своего появления до разбора 73: сам факт правила
# виден в коде, а из отчёта на такую тему приходило «никогда не извлекалось» без причины.


def test_every_rejection_label_has_an_explanation() -> None:
    """Метки берутся из исходника правила, а не из списка рядом: список и был бы тем же пробелом."""
    import re
    from pathlib import Path

    from horizon_analytics.domain.pipeline import _REJECTION_REASONS

    source = Path(blacklist.__file__).read_text(encoding="utf-8")
    body = source[source.index("    def rejects(") :]
    labels = set(re.findall(r'return "([a-z_]+)"', body))
    assert labels, "правила отказа не найдены — проверка смотрит не туда"
    missing = sorted(labels - set(_REJECTION_REASONS))
    assert not missing, f"метки без объяснения аналитику: {missing}"
    # Обратная сторона: объяснение, для которого нет правила. Оно не сломает ничего и потому
    # переживёт удаление своего правила незамеченным — аналитик будет читать про запрет, которого
    # нет. Сверять надо оба направления, иначе список и код разойдутся в ту сторону, куда не смотрят.
    orphaned = sorted(set(_REJECTION_REASONS) - labels)
    assert not orphaned, f"объяснение без правила: {orphaned}"


# ───────── вершина называет ход исследования, а не предмет ─────────
#
# Замер на 1785 работах OpenAlex: правило вычёркивает 130 кандидатов из 15 073, переживших прочие
# фильтры, из них 118 многословных — и все 118 просмотрены. Разбор:
# docs/01-analysis/73-the-course-of-research-is-not-its-subject.md


@pytest.mark.parametrize(
    "phrase",
    [
        "research interest",
        "pivotal role",
        "systematic literature",
        "valuable insights",
        "comprehensive understanding",
        "special focus",
        "utmost importance",
        "clinical significance",
        "special emphasis",
        "scientific literature",
        "critical role",
    ],
)
def test_a_head_naming_the_course_of_research_is_rejected(phrase: str) -> None:
    filter_ = GenericTermFilter.default(min_chars=3, max_chars=64)
    assert filter_.rejects(normalize_tokens(phrase.split()), phrase.split()) == "process_noun"


@pytest.mark.parametrize(
    "phrase",
    [
        "channel attention",  # SENet/CBAM — механизм внимания, а не оборот речи
        "focus group",  # то же слово не вершиной
        "metabolic pathways",
        "intrusion detection",
        "redox flow batteries",
    ],
)
def test_the_rule_does_not_touch_real_names(phrase: str) -> None:
    filter_ = GenericTermFilter.default(min_chars=3, max_chars=64)
    assert filter_.rejects(normalize_tokens(phrase.split()), phrase.split()) != "process_noun"
