"""Направление, набранное по-русски, должно означать то же, что английское.

Дефект, ради которого этот модуль существует, измерен на эталонном корпусе (1244 документа):
для запросов «искусственный интеллект машинное обучение», «квантовые вычисления» и «компьютерная
безопасность криптография» в направление попадало **ноль** документов, и все три давали один и тот
же отчёт. Отчёт при этом выглядел целым: пятнадцать трендов, баллы, мотивация, источники.

Причина в том, что отнесение к направлению держится на предметных кодах источников (методология
§12), а они английские: ``cs.LG``, ``artificial intelligence``, ``quantum physics``. Стем
«интеллект» не совпадает со стемом «intelligence» ни при каком алгоритме стемминга.

Это худший из возможных видов поломки для нашего пользователя: аналитик российского банка набирает
направление по-русски, получает правдоподобный отчёт и не имеет ни одного признака, что отчёт не о
том, что он спросил.
"""

from __future__ import annotations

import pytest
from tests.conftest import make_document

from horizon_analytics.domain.direction_lexicon import (
    LexiconError,
    expand,
    label_matches,
    load_direction_lexicon,
    parse_direction_lexicon,
    suggest,
)
from horizon_analytics.domain.extraction.blacklist import stem_phrase
from horizon_analytics.domain.extraction.normalization import TermNormalizer
from horizon_analytics.domain.models import DocumentTopic
from horizon_analytics.domain.pipeline import AnalysisPipeline, documents_in_direction


def classified(document_id: str, *codes: str):
    """Документ, размеченный предметными кодами так, как это делает источник."""
    document = make_document(document_id, year=2023)
    return type(document)(
        **{
            **{
                field: getattr(document, field) for field in document.__slots__ if field != "topics"
            },
            "topics": tuple(DocumentTopic(code=code, label=code) for code in codes),
        }
    )


NORMALIZER = TermNormalizer.from_texts(["artificial intelligence machine learning"])


def stems_of(query: str) -> frozenset[str]:
    """Стемы запроса плюс метки, которые словарь объявил значением направления."""
    return AnalysisPipeline._query_stems(query, NORMALIZER) | AnalysisPipeline._direction_labels(
        query
    )


class TestLexiconFile:
    """Сам ресурс: он правится вручную, поэтому его формат обязан прощать оформление."""

    def test_the_packaged_lexicon_is_not_empty(self) -> None:
        assert load_direction_lexicon()

    def test_both_sides_are_stemmed_so_case_endings_do_not_matter(self) -> None:
        # Аналитик пишет «квантовых вычислений», в словаре — «квантовые вычисления». Требовать
        # совпадения словоформ значило бы завести словарь, работающий только на именительном падеже.
        lexicon = parse_direction_lexicon("квантовые вычисления = quantum computing")

        assert expand(stem_phrase("квантовых вычислений").split(" "), lexicon)

    def test_comments_and_blank_lines_are_ignored(self) -> None:
        lexicon = parse_direction_lexicon("# комментарий\n\nблокчейн = blockchain\n")

        assert set(lexicon) == {stem_phrase("блокчейн")}

    def test_a_line_without_a_separator_is_skipped_not_fatal(self) -> None:
        # Один криво написанный ряд не должен стоить остальных: файл правится руками.
        lexicon = parse_direction_lexicon("мусор без равенства\nблокчейн = blockchain\n")

        assert set(lexicon) == {stem_phrase("блокчейн")}

    def test_the_analyst_wording_is_kept_for_display(self) -> None:
        # Подсказку показывают человеку. «квантов вычислен» — не подсказка.
        lexicon = parse_direction_lexicon("Квантовые вычисления = quantum computing")

        assert lexicon[stem_phrase("квантовые вычисления")].surface == "Квантовые вычисления"

    def test_several_target_labels_are_all_kept(self) -> None:
        # Одному русскому направлению почти всегда отвечает несколько английских меток, и брать
        # только первую значило бы сузить направление молча.
        lexicon = parse_direction_lexicon("нейронные сети = neural network | deep learning")

        assert expand(stem_phrase("нейронные сети").split(" "), lexicon) == {
            stem_phrase("neural network"),
            stem_phrase("deep learning"),
        }

    def test_a_target_label_is_kept_whole_not_split_into_words(self) -> None:
        # Причина, по которой первая версия была хуже отсутствия словаря. Пока правая часть
        # рассыпалась на слова, «материаловедение» через слово `science` забирало 355 документов
        # из 1244 — всё, что размечено `Computer science`, — а «компьютерное зрение» через
        # `computer` забирало 216 документов и ни одного по компьютерному зрению.
        lexicon = parse_direction_lexicon("материаловедение = materials science")

        assert expand(stem_phrase("материаловедение").split(" "), lexicon) == {
            stem_phrase("materials science")
        }

    def test_an_entry_longer_than_the_search_window_is_refused_loudly(self) -> None:
        # Такая статья никогда не нашлась бы в запросе. Выключить её молча значит оставить в
        # ресурсе строку, которая выглядит работающей, — ровно тот класс дефекта, ради которого
        # весь этот файл и заведён.
        with pytest.raises(LexiconError):
            parse_direction_lexicon("один два три четыре пять = machine learning")

    def test_the_packaged_lexicon_is_immutable(self) -> None:
        # Словарь кешируется на процесс. Изменяемый кеш — прямой путь к отчёту, зависящему от
        # порядка вызовов, что запрещено ADR-0015.
        with pytest.raises(TypeError):
            load_direction_lexicon()["взлом"] = frozenset()  # type: ignore[index]


class TestExpansion:
    """Разбор запроса: какие статьи словаря считаются найденными."""

    def test_a_longer_entry_does_not_swallow_its_parts(self) -> None:
        # «квантовая криптография» и «криптография» — разные записи, и запрос, содержащий первую,
        # законно относится к обеим. Поглощение дало бы направление уже, чем спросил аналитик.
        lexicon = parse_direction_lexicon(
            "квантовая криптография = quantum cryptography\nкриптография = cryptography"
        )

        found = expand(stem_phrase("квантовая криптография").split(" "), lexicon)

        assert found == {stem_phrase("quantum cryptography"), stem_phrase("cryptography")}

    def test_a_gap_in_the_query_breaks_the_phrase_rather_than_closing_it(self) -> None:
        # Если слово из середины запроса отфильтровали, его соседи соседями не становятся. Иначе
        # запрос без «квантовой» совпал бы со статьёй «квантовая криптография».
        lexicon = parse_direction_lexicon("квантовая криптография = quantum cryptography")

        assert expand(["квантов", "", "криптограф"], lexicon) == frozenset()

    def test_an_unknown_direction_expands_to_nothing_rather_than_guessing(self) -> None:
        # Отсутствие статьи — честный ответ «не знаю». Догадка здесь была бы хуже: она дала бы
        # направление, о котором никто не просил, и никакого признака этого в отчёте.
        lexicon = parse_direction_lexicon("блокчейн = blockchain")

        assert expand(stem_phrase("сельское хозяйство").split(" "), lexicon) == frozenset()


class TestLabelMatching:
    """Сопоставление целевой метки с меткой документа — целиком и по границам слов."""

    def test_a_shared_word_is_not_a_match(self) -> None:
        # Ровно то место, где «материаловедение» встречалось со всей информатикой.
        assert not label_matches(stem_phrase("computer science"), stem_phrase("materials science"))

    def test_the_whole_label_matches(self) -> None:
        assert label_matches(stem_phrase("computer vision"), stem_phrase("computer vision"))

    def test_a_target_inside_a_longer_label_matches(self) -> None:
        # Метка источника бывает длиннее цели: `Applied quantum computing` — по-прежнему квантовые
        # вычисления.
        assert label_matches(
            stem_phrase("applied quantum computing"), stem_phrase("quantum computing")
        )

    def test_a_target_is_not_matched_across_a_word_boundary(self) -> None:
        assert not label_matches(stem_phrase("quantum computing"), stem_phrase("computing quantum"))


class TestDirectionMembership:
    """Сквозное свойство: русское направление отбирает те же документы, что английское."""

    CORPUS = (
        classified("ai-1", "Artificial intelligence", "Machine learning"),
        classified("ai-2", "Machine learning"),
        classified("quantum-1", "Quantum computing", "Quantum physics"),
        classified("security-1", "Cryptography"),
        classified("unclassified-1"),
    )

    def test_a_russian_direction_selects_the_same_documents_as_its_english_twin(self) -> None:
        # «Двойник» — это то, что статья словаря объявляет значением направления, а не буквальный
        # перевод. Проверять против голого «artificial intelligence» было бы неверно: словарь
        # намеренно объявляет это направление как «artificial intelligence | machine learning».
        russian = documents_in_direction(self.CORPUS, "искусственный интеллект", NORMALIZER)
        english = documents_in_direction(
            self.CORPUS, "artificial intelligence machine learning", NORMALIZER
        )

        assert {document.document_id for document in russian} == {"ai-1", "ai-2"}
        assert {document.document_id for document in russian} == {
            document.document_id for document in english
        }

    def test_an_entry_is_as_broad_as_it_declares_itself_to_be(self) -> None:
        # Осознанное свойство, а не погрешность. Направление шире буквальной формулировки ровно
        # настолько, насколько его расписала статья словаря: «искусственный интеллект» захватывает
        # и документы, размеченные только как machine learning. Аналитик, спрашивая про ИИ, имеет в
        # виду именно это, а сузить направление до одной метки значило бы отвечать на вопрос
        # точнее, чем он был задан.
        #
        # Раньше свойство показывалось на `artificial intelligence`: словарь был русским, и
        # английская формулировка сопоставлялась с метками отдельными словами. Это было не
        # свойством словаря, а его пробелом — тем самым, из-за которого `cybersecurity` не
        # разрешалась ни в одну метку. После пополнения словаря английскими ключами пример
        # перестал быть примером, а свойство осталось: его показывает формулировка, статьи для
        # которой в словаре нет.
        russian = documents_in_direction(self.CORPUS, "искусственный интеллект", NORMALIZER)
        literal = documents_in_direction(self.CORPUS, "artificial", NORMALIZER)

        assert {document.document_id for document in literal} == {"ai-1"}
        assert {document.document_id for document in literal} < {
            document.document_id for document in russian
        }

    def test_different_russian_directions_select_different_documents(self) -> None:
        # Ключевая проверка, и та, что провалилась бы до словаря: раньше все три направления
        # давали пустое множество, а значит один и тот же отчёт. Одинаковый отчёт на разные
        # вопросы — не погрешность ранжирования, а отсутствие зависимости от вопроса.
        quantum = documents_in_direction(self.CORPUS, "квантовые вычисления", NORMALIZER)
        security = documents_in_direction(self.CORPUS, "криптография", NORMALIZER)

        assert {document.document_id for document in quantum} == {"quantum-1"}
        assert {document.document_id for document in security} == {"security-1"}

    def test_an_english_direction_still_works_without_the_lexicon(self) -> None:
        # Словарь добавляет путь, а не подменяет существующий: английский запрос обязан работать
        # ровно как раньше, даже если словарь пуст.
        selected = documents_in_direction(self.CORPUS, "quantum computing", NORMALIZER, lexicon={})

        assert {document.document_id for document in selected} == {"quantum-1"}

    def test_a_russian_direction_absent_from_the_lexicon_selects_nothing(self) -> None:
        # Честный ноль. Он и должен быть виден в диагностике как «направление не определено» —
        # см. `drops["direction_resolved"]` в конвейере.
        selected = documents_in_direction(self.CORPUS, "селекция пшеницы", NORMALIZER)

        assert selected == ()

    def test_a_document_without_subject_codes_never_belongs_to_a_direction(self) -> None:
        # Неразмеченный документ нельзя отнести никуда — ни к запрошенному направлению, ни к
        # другому. Считать его «своим» значило бы наполнить отчёт тем, о чём источник промолчал.
        selected = documents_in_direction(self.CORPUS, "искусственный интеллект", NORMALIZER)

        assert "unclassified-1" not in {document.document_id for document in selected}


class TestQueryStems:
    """Расширение запроса: словарь добавляет слова, а не заменяет их."""

    def test_the_original_words_survive_expansion(self) -> None:
        stems = stems_of("квантовые вычисления")

        assert stem_phrase("квантовые") in stems
        assert stem_phrase("quantum computing") in stems

    def test_a_mixed_query_expands_the_russian_part_only(self) -> None:
        # Аналитик пишет и так: «квантовые вычисления NISQ». Латинская часть должна дойти как есть.
        stems = stems_of("квантовые вычисления nisq")

        assert {stem_phrase("nisq"), stem_phrase("quantum computing")} <= stems


class TestSuggestions:
    """Подсказка там, где иначе тупик."""

    LEXICON = parse_direction_lexicon(
        "квантовые вычисления = quantum computing\n"
        "квантовый компьютер = quantum computing\n"
        "нейронные сети = neural network\n"
        "информационная безопасность = computer security\n"
    )

    def test_a_near_miss_is_offered_the_wording_the_lexicon_knows(self) -> None:
        # Разница между работающим продуктом и бесполезным — одно слово, которое аналитику неоткуда
        # узнать. Без подсказки красная плашка «направление не распознано» — это тупик.
        assert "нейронные сети" in suggest(stem_phrase("нейронная сеть").split(" "), self.LEXICON)

    def test_nothing_in_common_means_no_suggestion(self) -> None:
        # Подсказка наугад хуже её отсутствия: она выглядит как знание.
        assert suggest(stem_phrase("селекция озимой пшеницы").split(" "), self.LEXICON) == ()

    def test_an_empty_query_suggests_nothing(self) -> None:
        assert suggest([], self.LEXICON) == ()

    def test_the_order_does_not_depend_on_dictionary_traversal(self) -> None:
        # ADR-0015: при равной близости решает сама формулировка, а не порядок обхода словаря.
        # Иначе один и тот же запрос давал бы разные подсказки от запуска к запуску.
        words = stem_phrase("квантовая связь").split(" ")
        reversed_lexicon = parse_direction_lexicon(
            "квантовый компьютер = quantum computing\nквантовые вычисления = quantum computing\n"
        )

        assert suggest(words, self.LEXICON)[:2] == suggest(words, reversed_lexicon)[:2]

    def test_the_number_of_suggestions_is_capped(self) -> None:
        # Список подсказок длиной со словарь — не подсказка, а ещё одна задача для аналитика.
        assert (
            len(suggest(stem_phrase("квантовая безопасность сети").split(" "), self.LEXICON)) <= 3
        )


class TestDemoDirection:
    """Формулировка, которую увидит жюри, обязана распознаваться.

    Сценарий показа (`docs/04-operations/05-demo-scenario.md`) вводит «технологии в искусственном
    интеллекте». Словарь знает «искусственный интеллект» — на три слова короче. Совпадение находится
    поиском n-грамм внутри запроса, и до этой проверки держалось на нём одном: ничто не требовало,
    чтобы демонстрация вообще работала.

    Цена ошибки здесь несоизмерима с её вероятностью. Нераспознанное направление даёт не пустой
    экран, а красную плашку «мы не поняли вопроса» — на первой же минуте показа и на том самом
    запросе, который в сценарии набран заранее.
    """

    #: Ровно та строка, что стоит в сценарии показа и в сквозных сценариях (`DEMO_DOMAIN`).
    DEMO_QUERY = "технологии в искусственном интеллекте"

    def test_the_demo_direction_is_recognised(self) -> None:
        labels = AnalysisPipeline._direction_labels(self.DEMO_QUERY)

        assert labels, "направление показа не распознано — демонстрация откроется красной плашкой"

    def test_the_demo_direction_resolves_to_artificial_intelligence(self) -> None:
        # Не просто «распозналось»: распознаться оно могло бы и во что-то постороннее, а показ
        # обещает жюри отчёт по искусственному интеллекту.
        labels = AnalysisPipeline._direction_labels(self.DEMO_QUERY)

        assert stem_phrase("artificial intelligence") in labels
        assert stem_phrase("machine learning") in labels

    def test_a_longer_wording_finds_the_entry_inside_it(self) -> None:
        # Свойство, на котором держится предыдущая проверка, названо явно: аналитик пишет фразой, а
        # словарь хранит термин, и совпадение ищется внутри фразы. Сломать это значит сломать не
        # одну статью, а весь способ пользоваться словарём.
        short = AnalysisPipeline._direction_labels("искусственный интеллект")
        inside_a_phrase = AnalysisPipeline._direction_labels(
            "перспективные технологии в искусственном интеллекте для банка"
        )

        assert short
        assert short <= inside_a_phrase


def test_a_label_only_target_selects_topics_but_is_not_searched_for() -> None:
    """`~код` размечает направление, но в сбор не уходит.

    Поиск arXiv по коду отдаёт рубрику целиком: `q-fin.GN` — три тысячи работ общей финансовой
    экономики, и шестьсот самых свежих из них вытеснили бы из корпуса финтеха всё, что собрано
    фразами (разбор 103). Как метка работы тот же код точен.
    """
    lexicon = parse_direction_lexicon("финтех = fintech | ~q-fin.GN | ~cs.CR")
    entry = lexicon[stem_phrase("финтех")]

    assert entry.raw_labels == frozenset({"fintech"})
    assert {"fintech", stem_phrase("q-fin.GN"), stem_phrase("cs.CR")} <= entry.labels


def test_the_fintech_entry_never_sends_a_category_code_to_the_sources() -> None:
    entry = load_direction_lexicon()[stem_phrase("финтех")]

    assert entry.raw_labels, "статья финтеха не отдаёт сбору ни одной фразы"
    assert not any("." in target for target in entry.raw_labels), sorted(entry.raw_labels)
    assert "finance" not in entry.raw_labels
