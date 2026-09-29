"""Однословная цель словаря забирает всё, где это слово стоит.

Правило записано в шапке ``direction_lexicon.txt``: однословную цель писать можно, только если
слово называет предмет независимо от соседей. `cryptography` называет — и обязано забирать
`Quantum cryptography`. `security`, `biology`, `chemistry`, `energy` не называют: их предмет задаёт
определение слева, и по строению фразы `Quantum cryptography` от `Food security` не отличить.

Каждый случай здесь — метка, реально встреченная в корпусе из 1785 работ OpenAlex, которую
словарь забирал до правки. Числа в скобках — сколько документов корпуса несут эту метку.

Разбор: ``docs/01-analysis/65-six-words-that-named-someone-elses-field.md``.
"""

from __future__ import annotations

import pytest

from horizon_analytics.domain.direction_lexicon import label_matches, load_direction_lexicon
from horizon_analytics.domain.extraction.blacklist import stem_phrase
from horizon_analytics.domain.extraction.normalization import normalize_tokens, tokenize

#: направление → метка чужого предмета, которую оно забирало, и корпусный вес метки
FOREIGN = [
    ("информационная безопасность", "Food security", 17),
    ("информационная безопасность", "Energy security", 11),
    ("информационная безопасность", "Security analysis", 3),
    ("биотехнологии", "Biology", 972),
    ("биотехнологии", "Function (biology)", 13),
    ("биотехнологии", "Evolutionary biology", 13),
    ("материаловедение", "Chemistry", 512),
    ("материаловедение", "Environmental chemistry", 11),
    ("возобновляемая энергетика", "Energy (signal processing)", 60),
    ("возобновляемая энергетика", "Energy consumption", 47),
    ("финтех", "Environmental economics", 188),
    ("финтех", "Agricultural economics", 11),
    # Вся финансовая экономика, а не финансовые технологии: на стенде этой меткой в отчёт по
    # финтеху проходили «digital inclusive finance» и «digital financial inclusion» (разбор 103).
    # Число здесь — не из корпуса разбора 65, а из эталонного `fixtures/corpus`: 16 работ Crossref
    # и 7 OpenAlex с этой меткой.
    ("финтех", "Finance", 23),
]

#: направление → метка своего предмета, которую оно обязано забирать и после сужения
OWN = [
    ("информационная безопасность", "Computer security"),
    ("информационная безопасность", "Network security"),
    ("криптография", "Quantum cryptography"),
    ("биотехнологии", "Biotechnology"),
    ("биотехнологии", "Bioinformatics"),
    ("материаловедение", "Materials science"),
    ("возобновляемая энергетика", "Renewable energy"),
    # Рубрики arXiv, под которыми лежат работы живого финтех-корпуса (разбор 103).
    ("финтех", "q-fin.GN"),
    ("финтех", "cs.CR"),
    ("финтех", "Financial technology"),
]


def captures(direction: str, label: str) -> bool:
    entry = load_direction_lexicon()[
        " ".join(s for w in direction.split() if (s := stem_phrase(w)))
    ]
    key = normalize_tokens(token.normal for token in tokenize(label))
    return any(label_matches(key, target) for target in entry.labels)


@pytest.mark.parametrize(("direction", "label", "documents"), FOREIGN)
def test_a_direction_does_not_capture_someone_elses_field(
    direction: str, label: str, documents: int
) -> None:
    assert not captures(direction, label), (
        f"«{direction}» забирает метку «{label}» ({documents} документов корпуса) — "
        "это чужой предмет, а не уточнение своего"
    )


@pytest.mark.parametrize(("direction", "label"), OWN)
def test_narrowing_did_not_cost_the_direction_its_own_labels(direction: str, label: str) -> None:
    assert captures(direction, label), f"«{direction}» перестало забирать свою метку «{label}»"
