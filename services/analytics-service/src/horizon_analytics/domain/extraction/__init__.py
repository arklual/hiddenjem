"""Term extraction — methodology §7 steps 3 and 4.

No external NLP model is used anywhere in this package. Tokenisation, stemming, acronym
mining and termhood are implemented in plain Python: that is what keeps the service inside
its dependency budget and, more importantly, what makes the output bit-for-bit reproducible
across machines (ADR-0015). ``nltk``/``spacy``-style downloads would break both.
"""

from __future__ import annotations
