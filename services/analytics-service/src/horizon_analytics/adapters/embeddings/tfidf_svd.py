"""Default embedding provider: TF-IDF (word + char n-grams) → truncated SVD (ADR-0009).

Deterministic by construction:

* the vocabulary is built from a sorted document list and scikit-learn sorts feature names;
* ``TruncatedSVD`` работает единственным решателем — ``randomized`` с явным ``random_state``:
  выбор решателя по размеру матрицы дал бы два разных пространства для одного корпуса;
* the sign ambiguity of SVD components — the classic source of "same input, mirrored
  vectors" — is removed by forcing each component's largest-magnitude entry to be positive;
* the output dimensionality is always ``dimension``, zero-padded when the corpus is too
  small to support that many components, so the vector space never silently changes shape.

An LSA space is weaker than a transformer at rare-synonym matching. That is a conscious
trade (ADR-0009): it costs ~2.5 GB of image and all reproducibility to do better, and the
acronym dictionary plus the normal-form merge recover most of the loss.
"""

from __future__ import annotations

import hashlib
from collections.abc import Sequence

import numpy as np
from sklearn.decomposition import TruncatedSVD
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import FeatureUnion

from horizon_analytics.domain.vectors import Matrix, l2_normalize

__all__ = ["TfidfSvdEmbeddingProvider"]


#: Степенные итерации рандомизированного SVD (см. ``fit``).
SVD_POWER_ITERATIONS = 4


class TfidfSvdEmbeddingProvider:
    """TF-IDF + LSA embeddings, deterministic for a fixed seed."""

    def __init__(self, *, dimension: int = 384, seed: int = 20260805) -> None:
        """Configure the provider.

        Args:
            dimension: target vector width; also part of the model id.
            seed: ``random_state`` of the randomized SVD solver.
        """
        self._dimension = dimension
        self._seed = seed
        self._vectorizer: FeatureUnion | None = None
        self._svd: TruncatedSVD | None = None
        self._components = 0
        self._corpus_key: str | None = None

    @property
    def model_id(self) -> str:
        """Identifier stored with every embedding and echoed in ``DomainAnalyzed``."""
        return f"tfidf-svd-{self._dimension}-v1"

    @property
    def dimension(self) -> int:
        """Width of the produced vectors."""
        return self._dimension

    def fit(self, corpus: Sequence[str]) -> None:
        """Подогнать словарь и SVD под корпус — если он не тот же, что в прошлый раз.

        Пространство зависит только от корпуса, а корпус неизменяем: у него есть
        ``corpus_snapshot_id``, и два запроса по одному снапшоту получают одни и те же тексты.
        До сих пор каждый запрос подгонял пространство заново.

        Замер на 1785 работах OpenAlex: прогон 160 с, из них ``fit`` — 84 с, а внутри него
        ``TruncatedSVD(n_components=384, n_iter=7)`` — 77 с. Три направления по одному корпусу
        стоили 480 с, из которых 168 с — двукратная подгонка того же самого пространства.

        Результат не меняется ни на бит: пропускается вычисление, дающее то же состояние. Именно
        поэтому опознание идёт по содержимому текстов, а не по идентификатору снапшота — снапшот
        приходит извне и может солгать, тексты лгать не могут.

        Памяти это не добавляет: подогнанное состояние провайдер держал и раньше, между запросами
        оно просто перезаписывалось. Провайдер живёт всё время процесса (``build_container``), и
        одновременные анализы делили бы его и до этой правки — здесь ничего не изменилось.
        """
        texts = list(corpus)
        if not texts:
            self._vectorizer = None
            self._svd = None
            self._components = 0
            # Отпечаток здесь не сбрасывается намеренно: страж ниже требует непустого словаря, а
            # он только что обнулён, поэтому следующий тот же корпус всё равно будет подогнан.
            return

        key = self._corpus_digest(texts)
        if key == self._corpus_key and self._vectorizer is not None:
            return
        self._corpus_key = key

        self._vectorizer = FeatureUnion(
            [
                (
                    "word",
                    TfidfVectorizer(
                        analyzer="word",
                        ngram_range=(1, 2),
                        lowercase=True,
                        sublinear_tf=True,
                        min_df=1,
                        dtype=np.float64,
                    ),
                ),
                (
                    "char",
                    TfidfVectorizer(
                        analyzer="char_wb",
                        ngram_range=(3, 5),
                        lowercase=True,
                        sublinear_tf=True,
                        min_df=2,
                        dtype=np.float64,
                    ),
                ),
            ]
        )
        matrix = self._vectorizer.fit_transform(texts)
        features = int(matrix.shape[1])
        samples = int(matrix.shape[0])
        self._components = max(1, min(self._dimension, features - 1, samples))
        if self._components < 1 or features <= 1:
            self._svd = None
            return
        # Четыре степенные итерации, а не семь (умолчание scikit-learn). Замер на 5000 работах
        # корпуса Edge: подгонка 116 → 76 с, у 93% документов те же десять ближайших соседей
        # (разбор 106). Эмбеддинги — больше половины времени анализа, и на стенде ТЗ даёт весь
        # анализ за 20 минут. Меньше компонент (256) дают те же секунды, но 78% соседей — не взято.
        self._svd = TruncatedSVD(
            n_components=self._components,
            algorithm="randomized",
            n_iter=SVD_POWER_ITERATIONS,
            random_state=self._seed,
        )
        self._svd.fit(matrix)
        self._stabilize_signs()

    @staticmethod
    def _corpus_digest(texts: Sequence[str]) -> str:
        """Отпечаток корпуса: порядок значим, потому что от него зависит и словарь, и знаки."""
        digest = hashlib.sha256()
        for text in texts:
            digest.update(text.encode("utf-8"))
            digest.update(b"\x00")
        return digest.hexdigest()

    def embed(self, texts: Sequence[str]) -> Matrix:
        """Return the ``(len(texts), dimension)`` embedding matrix."""
        items = list(texts)
        if not items:
            return np.zeros((0, self._dimension), dtype=np.float64)
        if self._vectorizer is None:
            return np.zeros((len(items), self._dimension), dtype=np.float64)

        sparse = self._vectorizer.transform(items)
        if self._svd is None:
            dense = np.asarray(sparse.todense(), dtype=np.float64)
            reduced = dense[:, : self._dimension]
        else:
            reduced = np.asarray(self._svd.transform(sparse), dtype=np.float64)
        return self._pad(l2_normalize(reduced))

    def _pad(self, matrix: Matrix) -> Matrix:
        """Zero-pad (or trim) to exactly ``dimension`` columns."""
        rows, columns = matrix.shape
        if columns == self._dimension:
            return matrix
        if columns > self._dimension:
            return np.asarray(matrix[:, : self._dimension], dtype=np.float64)
        padded = np.zeros((rows, self._dimension), dtype=np.float64)
        padded[:, :columns] = matrix
        return padded

    def _stabilize_signs(self) -> None:
        """Force a canonical sign per SVD component.

        Truncated SVD is only defined up to a per-component sign; LAPACK/ARPACK may return
        either. Flipping so that the largest-magnitude loading is positive makes the space
        identical across runs, machines and BLAS builds — without this, cosine *distances*
        stay correct but the stored vectors differ, and the golden snapshots become noise.
        """
        if self._svd is None:
            return
        components = np.asarray(self._svd.components_, dtype=np.float64)
        pivots = np.argmax(np.abs(components), axis=1)
        signs = np.sign(components[np.arange(components.shape[0]), pivots])
        signs[signs == 0.0] = 1.0
        self._svd.components_ = components * signs[:, np.newaxis]
