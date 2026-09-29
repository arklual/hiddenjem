"""Packaged read-only data files.

These are static assets shipped with the wheel (``stopwords.txt``, ``generic_terms.txt``),
not configuration and not state. Reading them is a package resource lookup, which is why it
is acceptable inside the otherwise I/O-free domain — the content is fixed at build time and
participates in the determinism guarantee exactly like a constant would.
"""

from __future__ import annotations
