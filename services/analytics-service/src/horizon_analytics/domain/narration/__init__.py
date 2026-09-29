"""Trend narration — ADR-0010.

Default is :class:`~horizon_analytics.domain.narration.extractive.ExtractiveNarrator`:
sentences are *quoted* from the abstracts, never generated, so every word on the card
physically exists in a primary source and the pipeline stays byte-reproducible.
"""

from __future__ import annotations
