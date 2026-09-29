"""Артефакты, поставляемые вместе с пакетом.

Модель движка ``signals`` лежит здесь: движок без артефакта не собирается, и свежая установка
без ``HORIZON_SIGNALS_MODEL`` отказывала бы в каждом анализе.
"""

from __future__ import annotations

from pathlib import Path
from typing import Final

__all__ = ["PACKAGED_SIGNALS_MODEL"]

#: Артефакт модели ``signals``, с которым собран пакет. Явный ``HORIZON_SIGNALS_MODEL`` важнее.
PACKAGED_SIGNALS_MODEL: Final[Path] = Path(__file__).resolve().parent / "signals-model.json"
