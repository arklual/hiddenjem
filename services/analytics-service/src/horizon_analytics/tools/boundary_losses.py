"""Печатает таблицу потерь правила границы на списке заведомо настоящих названий.

Инструмент существует против конкретной ошибки, и она уже случилась дважды. Числа потерь набирались
руками и расходились с кодом: «20 из 58» осталось в README, в сценарии демонстрации и в записке о
границах после того, как правило изменилось, — это записано в докстроке самой проверки. Второй раз
расхождение нашлось при чтении README целиком: в соседних абзацах стояли «58» и «66» как размер
одного и того же списка.

Тот же приём уже применён к приёмке (`make accept`) и к устойчивости (`make stability`): числа
печатает машина, документ на них ссылается, и разойтись им негде.

Список имён живёт здесь, а не в правиле, и это не деталь размещения. Перечень составлен независимо от
фильтра, чтобы измерять потери на именах, которых фильтр не видел; перенеся его в список исключений,
мы получили бы ноль потерь и ни одного измерения.

    python -m horizon_analytics.tools.boundary_losses
    python -m horizon_analytics.tools.boundary_losses --markdown

Детерминизм: правило чистое, список фиксирован, поэтому два запуска дают побайтово одинаковую
таблицу.
"""

from __future__ import annotations

import argparse
import sys

from horizon_analytics.domain.extraction.blacklist import GenericTermFilter, stem_phrase
from horizon_analytics.domain.extraction.tokenizer import tokenize

__all__ = ["KNOWN_REAL_NAMES", "losses", "render_markdown"]

#: Настоящие названия технологий, которые правило границы отбрасывало на замере 2026-08-05.
#:
#: Это **не весь** список, на котором мерили: тот содержал 66 общеизвестных имён и в репозитории не
#: живёт. Здесь — те, что терялись, потому что измерять нужно потери, а не совпадения.
KNOWN_REAL_NAMES: tuple[str, ...] = (
    "large language model",
    "deep packet inspection",
    "long short term memory",
    "high performance computing",
    "novel view synthesis",
    "low latency inference",
    "wide area network",
    "new radio",
    "real time gross settlement",
    "open banking api",
    "open source intelligence",
    "complete mediation",
    "low rank adaptation",
    "support vector machine",
    "address translation",
    "build system",
    "report generator",
    "yield farming",
    "study design",
    "meet in the middle attack",
)


def _rejection(filter_: GenericTermFilter, name: str) -> str | None:
    """Метка отказа правила для имени; ``None``, если имя проходит."""
    return filter_.rejects(stem_phrase(name), [token.normal for token in tokenize(name)])


def losses() -> tuple[tuple[str, str | None], ...]:
    """Имя → метка отказа (или ``None``), в порядке списка."""
    filter_ = GenericTermFilter.default()
    return tuple((name, _rejection(filter_, name)) for name in KNOWN_REAL_NAMES)


def render_markdown() -> str:
    """Таблица для документа: какие имена правило теряет и по какой причине."""
    rows = losses()
    lost = [(name, label) for name, label in rows if label is not None]
    lines = [
        "<!-- Сгенерировано `make losses`. Руками не править: правка разойдётся с замером -->",
        "Замер правила границы на списке заведомо настоящих названий технологий.",
        "",
        f"Проверено имён: {len(rows)}. **Теряется: {len(lost)}.**",
        "",
        "Список составлен независимо от фильтра — из общеизвестных терминов, а не из эталонного",
        "корпуса, иначе замер подгонялся бы под фикстуру. Перечислены имена, которые правило",
        "отбрасывало на замере 2026-08-05; полный список из 66 общеизвестных имён в репозитории не",
        "хранится, потому что измерять нужно потери, а не совпадения.",
        "",
        "| имя | правило | теряется |",
        "| --- | --- | --- |",
    ]
    for name, label in rows:
        lines.append(f"| `{name}` | {label or '—'} | {'да' if label else 'нет'} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    """Точка входа: печатает таблицу или короткую сводку."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--markdown", action="store_true", help="печатать таблицу для документа")
    args = parser.parse_args(argv)

    if args.markdown:
        sys.stdout.write(render_markdown())
        return 0

    rows = losses()
    lost = [name for name, label in rows if label is not None]
    sys.stdout.write(f"проверено {len(rows)}, теряется {len(lost)}\n")
    for name in lost:
        sys.stdout.write(f"  {name}\n")
    return 0


if __name__ == "__main__":  # pragma: no cover - тонкая обёртка над main
    raise SystemExit(main())
