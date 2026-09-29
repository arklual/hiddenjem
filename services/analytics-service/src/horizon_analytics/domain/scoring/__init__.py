"""Scoring subsystem — the literal implementation of the methodology document.

Module map (mirrors ``docs/03-methodology/01-emergence-methodology.md``):

===========================  =============================================
Module                       Methodology section
===========================  =============================================
:mod:`profile`               §2 (parameters), §4 (weights)
:mod:`indicators`            §3.1 – §3.6
:mod:`confidence`            §3.7
:mod:`aggregators`           §4
:mod:`burst`                 §5
:mod:`lifecycle`             §6
:mod:`yoon`                  §3.2 (b) — DoV / DoD
:mod:`engine`                §3 – §6 orchestration
===========================  =============================================
"""

from __future__ import annotations
