"""Generic-term filter and stopword list — methodology §7 step 3.

Both lists are *data*, not code: they live in ``domain/resources/*.txt`` so that a domain
expert can extend them in a reviewable diff without touching Python. The filter object
itself is pure and takes its sets by argument, which keeps it trivially unit-testable with
an injected list.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from functools import lru_cache
from importlib.resources import files
from typing import Final

from horizon_analytics.domain.extraction.normalization import normalize_tokens
from horizon_analytics.domain.extraction.tokenizer import is_year_like, tokenize

__all__ = [
    "GenericTermFilter",
    "load_attributive_terms",
    "load_boundary_terms",
    "load_clause_verb_forms",
    "load_clause_verbs",
    "load_generic_terms",
    "load_process_nouns",
    "load_stopwords",
    "parse_resource_lines",
]

_RESOURCE_PACKAGE = "horizon_analytics.domain.resources"


def parse_resource_lines(content: str) -> frozenset[str]:
    """Parse a resource file: one entry per line, ``#`` comments and blank lines dropped."""
    entries: set[str] = set()
    for raw in content.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entries.add(line.lower())
    return frozenset(entries)


@lru_cache(maxsize=1)
def load_stopwords() -> frozenset[str]:
    """Stopwords used as hard n-gram boundaries (RU + EN, one packaged file)."""
    content = files(_RESOURCE_PACKAGE).joinpath("stopwords.txt").read_text(encoding="utf-8")
    return parse_resource_lines(content)


def _regular_inflections(word: str) -> tuple[str, ...]:
    """Базовая форма и её правильные словоформы.

    Файл ресурсов обещает, что перечислять словоформы не нужно — стемминг сведёт их к одной основе.
    Обещание неверно, и это измерено: у 17 записей из 27 формы на «-ing» и «-ed» дают основу,
    отличную от основы базовой формы. ``reduce`` даёт ``reduce``, а ``reducing`` — ``reduc``, и
    запись не срабатывает. Правило существовало, выглядело работающим и половину форм пропускало:
    обрывок «reducing mean time» занимал восемнадцатое место в отчёте по безопасности.

    Это тот же класс ошибки, о котором предупреждает шапка `boundary_terms.txt` — «написанная
    руками основа, разошедшаяся со стеммером, молча отключает запись», — только зашедший с другой
    стороны: руками написана не основа, а базовая форма.

    Словоформы порождаются здесь, а не в файле: список остаётся читаемым доменным экспертом, а
    расхождение становится невозможным по построению.

    Формы на «-ed» намеренно не порождаются, и это тоже измерено. Причастие — прилагательное, и оно
    законно открывает имя технологии: ``reduced instruction set computer`` (RISC), ``combined heat
    and power``, ``combined cycle gas turbine``. Запрет на «-ed» вычеркнул бы их вместе с обрывками.
    Различение уже проведено в другом месте и здесь только соблюдается: причастие в **конце** имени
    — оборванная фраза (правило ``participle_tail``), в начале и середине — обычное определение.
    """
    stem = word[:-1] if word.endswith("e") else word
    if len(word) > 1 and word.endswith("y") and word[-2] not in "aeiou":
        # `apply` → `applies`, а не `applys`. Порождённая форма, которой в языке нет, не ловит
        # ничего и создаёт видимость покрытия: запись в списке есть, а обрывок «applies error
        # mitigation» проходит мимо. Здесь его перехватывало правило границы — то есть дефект
        # прятался за соседним правилом, и заметен стал только при чтении порождённых форм.
        third_person = word[:-1] + "ies"
    elif word.endswith(("s", "x", "z", "ch", "sh")):
        third_person = word + "es"
    else:
        third_person = word + "s"
    return (word, third_person, stem + "ing")


@lru_cache(maxsize=1)
def load_established_names() -> frozenset[str]:
    """Устоявшиеся имена, которым правило границы не указ, — по ключам после стемминга.

    Сравнение по стемминговому ключу, а не по исходной строке: кандидат приходит в фильтр уже
    нормализованным, и «large language models» обязано совпасть с записью «large language model».
    Сравнение по исходной форме потребовало бы вести в файле все числа и падежи — то есть ту самую
    ручную работу, от которой уводит стемминг.
    """
    content = files(_RESOURCE_PACKAGE).joinpath("established_names.txt").read_text(encoding="utf-8")
    return frozenset(
        stemmed for line in parse_resource_lines(content) if (stemmed := stem_phrase(line))
    )


@lru_cache(maxsize=1)
def load_clause_verb_forms() -> frozenset[str]:
    """Словоформы глаголов из ``clause_verbs.txt`` — как они пишутся, без стемминга.

    Проверка по основам здесь невыразима, и это установлено замером, а не рассуждением. Стеммер
    сводит ``combining`` и ``combined`` к одной основе ``combin``, ``reducing`` и ``reduced`` — к
    ``reduc``. Запретить герундий, разрешив причастие, по основам невозможно в принципе — а
    различать их нужно: причастие законно открывает имя технологии (``reduced instruction set
    computer``, ``combined heat and power``, ``combined cycle gas turbine``), герундий в клаузальной
    позиции — признак обрывка (``reducing mean time``, ``providing higher throughput``).

    Первая попытка чинила разрыв основ порождением словоформ и стеммингом их всех. Она поймала
    обрывки — и вычеркнула RISC вместе с когенерацией: стеммер их не различает. Поэтому сравнение
    ведётся по исходным словоформам, которые фильтру и так передаются.

    Отдельно от ``load_clause_verbs``: там основы, и они по-прежнему объединяются с границами.
    """
    content = files(_RESOURCE_PACKAGE).joinpath("clause_verbs.txt").read_text(encoding="utf-8")
    return frozenset(
        form.lower()
        for line in parse_resource_lines(content)
        for form in _regular_inflections(line)
    )


@lru_cache(maxsize=1)
def load_clause_verbs() -> frozenset[str]:
    """Основы глаголов, недопустимых в любом месте имени технологии (``clause_verbs.txt``).

    Имя технологии — именная группа, и личная форма глагола внутри неё стоять не может. Значит
    кандидат с таким словом это обрывок предложения, вырезанный n-граммным окном, а не имя.
    """
    content = files(_RESOURCE_PACKAGE).joinpath("clause_verbs.txt").read_text(encoding="utf-8")
    return frozenset(
        stemmed for line in parse_resource_lines(content) if (stemmed := stem_phrase(line))
    )


@lru_cache(maxsize=1)
def load_attributive_terms() -> frozenset[str]:
    """Stems that may not *close* a candidate term (``attributive_terms.txt``).

    Отдельно от `boundary_terms.txt`, где запрет действует на обеих границах: «quantum» в том
    списке убил бы «quantum error correction» и «quantum key distribution». Здесь слова, которые
    в английском стоят перед вершиной именной группы и сами вершиной не бывают, — кандидат,
    кончающийся таким словом, есть обрывок с отрезанной вершиной.
    """
    content = files(_RESOURCE_PACKAGE).joinpath("attributive_terms.txt").read_text(encoding="utf-8")
    return frozenset(
        stemmed for line in parse_resource_lines(content) if (stemmed := stem_phrase(line))
    )


@lru_cache(maxsize=1)
def load_process_nouns() -> frozenset[str]:
    """Основы слов, называющих ход исследования, а не его предмет (``process_nouns.txt``).

    Отдельно от `attributive_terms.txt`: там слова, которые вершиной не бывают вообще, а здесь —
    те, что вершиной бывают, но вершиной *не технологии*. «research interest» грамматически
    безупречно и именно поэтому доходит до отчёта.
    """
    content = files(_RESOURCE_PACKAGE).joinpath("process_nouns.txt").read_text(encoding="utf-8")
    return frozenset(
        stemmed for line in parse_resource_lines(content) if (stemmed := stem_phrase(line))
    )


def load_boundary_terms() -> frozenset[str]:
    """Stems that may not open or close a candidate term (``boundary_terms.txt``).

    Объединение с ``clause_verbs.txt`` намеренно: слово живёт ровно в одном файле, а запрет на
    границе остаётся в силе и для тех глаголов, что запрещены везде. Дублировать их в двух списках
    значило бы завести расхождение, которое ничем не проявится.
    """
    content = files(_RESOURCE_PACKAGE).joinpath("boundary_terms.txt").read_text(encoding="utf-8")
    return (
        frozenset(
            stemmed for line in parse_resource_lines(content) if (stemmed := stem_phrase(line))
        )
        | load_clause_verbs()
    )


def stem_phrase(phrase: str) -> str:
    """Normalise a blacklist entry with the exact pipeline used for candidate keys.

    Entries are written in plain language in the resource file and stemmed here. Storing
    them pre-stemmed was the obvious alternative and is a trap: a single hand-written stem
    that disagrees with the stemmer ("magnitud" vs "magnitude") silently disables the entry,
    and nothing fails loudly enough to notice.
    """
    return normalize_tokens(token.normal for token in tokenize(phrase))


@lru_cache(maxsize=1)
def load_generic_terms() -> frozenset[str]:
    """Normalised keys of phrases that are about research rather than a technology."""
    content = files(_RESOURCE_PACKAGE).joinpath("generic_terms.txt").read_text(encoding="utf-8")
    return frozenset(
        stemmed for line in parse_resource_lines(content) if (stemmed := stem_phrase(line))
    )


#: Предлоги и союзы, которых не бывает в имени технологии.
#:
#: Класс закрытый и потому живёт в коде, а не в файле ресурсов: доменному эксперту здесь нечего
#: править — английские предлоги не зависят от предметной области, в отличие от списка глаголов.
#:
#: «of» отсутствует намеренно и это измерено: на нём держатся `software bill of materials`,
#: `mixture of experts`, `internet of things`, `denial of service`, `quality of service`,
#: `proof of stake`. Запрет всех предлогов разом стоил бы шести настоящих названий из пятидесяти
#: восьми, а этот список — ни одного.
#:
#: Что он ловит: `grown quickly across retail`, `wallet compatibility across bundler` — куски
#: предложений, которые n-граммное окно вырезало через предлог. Имя технологии таких связок не
#: содержит: они соединяют предложение, а не именуют вещь.
_SENTENCE_PREPOSITIONS: Final = frozenset(
    {
        "across",
        "under",
        "into",
        "from",
        "with",
        "without",
        "when",
        "if",
        "during",
        "through",
        "over",
        "between",
        "among",
        "against",
        "before",
        "after",
        "upon",
        "onto",
        "within",
        "toward",
        "towards",
        "than",
        "while",
        "whether",
        "because",
        "since",
        "unless",
        "per",
        "via",
        "despite",
        "besides",
        "throughout",
        "regarding",
        "concerning",
    }
)

#: Предлоги, которые не могут стоять **последним** словом имени, но законны внутри него.
#:
#: Список `_SENTENCE_PREPOSITIONS` намеренно не содержит `of`, `for`, `to`, `by`: на них держатся
#: настоящие названия — «software bill of materials», «system on chip», «point to point». Запретить
#: их в любом месте значило бы вычеркнуть эти названия, и это измерено.
#:
#: Но ни одно имя технологии не **заканчивается** предлогом: «energy density gap versus» — это
#: начало фразы «…versus lithium-ion», оборванной окном. Хвостовая позиция решается отдельно от
#: любой другой, потому что цена ошибки в ней противоположна: внутри имени предлог обычен, в конце
#: — признак обрыва.
_TAIL_PREPOSITIONS: Final = _SENTENCE_PREPOSITIONS | frozenset(
    {"of", "for", "to", "by", "in", "on", "at", "as", "versus", "vs"}
)


@dataclass(frozen=True, slots=True)
class GenericTermFilter:
    """Rejects candidates that cannot be a technology name.

    Applies, in order: the generic-term list, the length bounds, the "at least one
    alphabetic token" rule and the "not a bare number or year" rule of §7 step 3.
    """

    generic_terms: frozenset[str]
    #: Stems that cannot begin or end a technology name — loaded from `boundary_terms.txt`.
    #: Deliberately *not* derived from `generic_terms`: that list contains legitimate head nouns
    #: ("learning", "architecture", "memory"), and using it here would reject "federated learning"
    #: and "transformer architecture" along with the noise.
    boundary_terms: frozenset[str] = frozenset()
    #: Stems that cannot end a technology name — loaded from `attributive_terms.txt`.
    attributive_terms: frozenset[str] = frozenset()
    #: Stems naming the course of research rather than its subject — `process_nouns.txt`.
    process_nouns: frozenset[str] = frozenset()
    #: Основы глаголов, недопустимых в любом месте имени — из `clause_verbs.txt`. Правило границы
    #: снимает «enables linear-time inference», но пропускает «arrangement enables cheap knowledge»:
    #: существительное слева, существительное справа, глагол внутри.
    clause_verbs: frozenset[str] = frozenset()
    #: Те же глаголы, но словоформами: проверка по основам не различает `combining` и `combined`,
    #: а различать необходимо — причастие законно открывает имя, герундий в клаузальной позиции нет.
    clause_verb_forms: frozenset[str] = frozenset()
    min_chars: int = 3
    max_chars: int = 60

    @classmethod
    def default(cls, *, min_chars: int = 3, max_chars: int = 60) -> GenericTermFilter:
        """Build the filter from the packaged generic-term list."""
        return cls(
            generic_terms=load_generic_terms(),
            boundary_terms=load_boundary_terms(),
            attributive_terms=load_attributive_terms(),
            process_nouns=load_process_nouns(),
            clause_verbs=load_clause_verbs(),
            clause_verb_forms=load_clause_verb_forms(),
            min_chars=min_chars,
            max_chars=max_chars,
        )

    @classmethod
    def from_terms(
        cls,
        terms: Iterable[str],
        *,
        boundary_terms: Iterable[str] = (),
        attributive_terms: Iterable[str] = (),
        process_nouns: Iterable[str] = (),
        clause_verbs: Iterable[str] = (),
        min_chars: int = 3,
        max_chars: int = 60,
    ) -> GenericTermFilter:
        """Build the filter from explicit lists of plain phrases — used by unit tests."""
        return cls(
            generic_terms=frozenset(stemmed for term in terms if (stemmed := stem_phrase(term))),
            boundary_terms=frozenset(
                stemmed for term in boundary_terms if (stemmed := stem_phrase(term))
            ),
            process_nouns=frozenset(
                stemmed for term in process_nouns if (stemmed := stem_phrase(term))
            ),
            attributive_terms=frozenset(
                stemmed for term in attributive_terms if (stemmed := stem_phrase(term))
            ),
            clause_verbs=frozenset(
                stemmed for term in clause_verbs if (stemmed := stem_phrase(term))
            ),
            min_chars=min_chars,
            max_chars=max_chars,
        )

    def rejects(self, key: str, surface_tokens: Iterable[str]) -> str | None:
        """Return the name of the rule rejecting the candidate, or ``None`` if it passes.

        Returning the rule name rather than a bool is what makes the drop-off metrics of
        the extraction stage meaningful.
        """
        stripped = key.strip()
        if not stripped:
            return "empty"
        if len(stripped) < self.min_chars:
            return "too_short"
        if len(stripped) > self.max_chars:
            return "too_long"
        tokens = list(surface_tokens)
        if not tokens:
            return "empty"
        # Order matters: a purely numeric candidate ("2024", "123 456") is also devoid of
        # letters, and reporting the vaguer `no_alphabetic_token` would hide the far more
        # actionable fact that the extractor is picking up years and figures.
        if all(token.isdigit() or is_year_like(token) for token in tokens):
            return "numeric_only"
        if not any(any(character.isalpha() for character in token) for token in tokens):
            return "no_alphabetic_token"
        if stripped in self.generic_terms:
            return "generic_term"
        # A technology name never *begins* with a cue verb or *ends* with a filler adjective:
        # "enables linear-time inference" and "haloformer novel" are sentence fragments the n-gram
        # window happened to cut out, not names of things. Whole-phrase blacklisting cannot reach
        # them — there is one such fragment per cue word per technology, so the list would have to
        # enumerate the cross-product. Checking the boundary token instead costs one lookup and
        # removes the entire family.
        if self.boundary_terms and " " in stripped:
            parts = stripped.split(" ")
            # Устоявшееся имя проходит границу целиком: «large language model» устроено так же, как
            # обрывок «large transaction batches», и различает их только внешнее знание. Список
            # короткий и измеренный (`established_names.txt`), а не подобранный порог.
            if stripped in load_established_names():
                return None
            if parts[0] in self.boundary_terms or parts[-1] in self.boundary_terms:
                return "generic_boundary"
            # Атрибутивное слово в конце — обрывок с отрезанной вершиной. Найдено прогоном на
            # настоящих данных: «field of quantum», «various quantum», «robust quantum»,
            # «performance of deep neural». В начале и в середине те же слова законны, поэтому
            # проверяется только последняя позиция.
            if parts[-1] in self.attributive_terms:
                return "attributive_tail"
        # Вершина называет ход исследования, а не его предмет: «research interest», «pivotal role»,
        # «systematic literature». Грамматически это безупречные именные группы — тем и опасны:
        # прочие правила ищут обрывки и глаголы, а здесь целая фраза не о том. Проверяется только
        # последняя позиция: «focus group» законно, «special focus» — нет.
        # Вершина берётся заново, а не из ветки выше: та живёт только при непустом списке границ и
        # только для многословных, а «focus» и «role» приходят в отчёт и поодиночке — в замере таких
        # двенадцать из ста тридцати.
        if stripped.split(" ")[-1] in self.process_nouns:
            return "process_noun"
        # Правило границы не достаёт до глагола, оказавшегося в середине: «arrangement enables cheap
        # knowledge» открывается и закрывается существительными, а комитету предлагается как имя
        # технологии. Критерий структурный — именная группа не содержит личной формы глагола, —
        # поэтому не зависит от корпуса, в отличие от списка неудачных существительных.
        if self.clause_verbs and any(part in self.clause_verbs for part in stripped.split(" ")):
            return "clause_fragment"
        # Сравнение по словоформам, а не по основам: `combining` и `combined` дают одну основу, и
        # запрет по ней вычеркнул бы `reduced instruction set computer` вместе с `reducing mean
        # time`. Проверка по основам выше остаётся — она ловит формы, совпадающие с базовой.
        if self.clause_verb_forms and any(
            token.lower() in self.clause_verb_forms for token in tokens
        ):
            return "clause_fragment"
        # Предлог, соединяющий предложение, — след того, что окно вырезало кусок фразы через связку.
        # «of» исключён: на нём держатся настоящие названия, и замер назвал их поимённо.
        if any(token.lower() in _SENTENCE_PREPOSITIONS for token in tokens):
            return "clause_fragment"
        # Предлог последним словом — оборванная фраза, а не имя. Проверяется отдельно от правила
        # выше и по более широкому списку: `of` и `for` законны внутри имени и невозможны в конце.
        if tokens and tokens[-1].lower() in _TAIL_PREPOSITIONS:
            return "trailing_preposition"
        # Причастие последним словом означает, что окно оборвало фразу на определении, не дойдя до
        # определяемого: «memory footprint of retrieval-augmented» — это начало «…of
        # retrieval-augmented generation». Имя технологии причастием не заканчивается: причастие
        # что-то характеризует, а название называет.
        #
        # Причастие отличается от существительного на «-ed» самим стеммером, а не длиной слова и не
        # списком исключений: у «augmented» основа меняется, у «speed», «feed», «threshold», «yield»
        # — нет. Проверяется исходная словоформа, потому что в ключе окончание уже срезано.
        if tokens:
            last = tokens[-1].lower()
            if last.endswith("ed") and stem_phrase(last) != last:
                return "participle_tail"
        return None
