"""HTTP-клиент `nlp-service`: семантический судья и русификация выдачи.

Граница здесь ровно одна и намеренно тонкая. Модели живут в отдельном процессе с собственными
зависимостями (ADR-0017), а движок остаётся детерминированным: **любой отказ этого клиента
означает «как было»** — судья никого не отсеивает, русского слоя в отчёте нет. Ни одно число
методологии от доступности моделей не зависит.
"""

from __future__ import annotations

import os
from collections.abc import Mapping, Sequence
from datetime import UTC, date, datetime, time

import httpx

from horizon_analytics.domain.models import Author, Document, Venue
from horizon_analytics.domain.pipeline import TechnologyVerdict
from horizon_analytics.observability import get_logger

__all__ = ["HttpNlpClient", "HttpTechnologyJudge", "HttpTechnologyProposer", "Localized"]

_LOGGER = get_logger(__name__)


class Localized:
    """Результат русификации одной строки: текст и модель, его произведшая."""

    __slots__ = ("model", "text")

    def __init__(self, text: str, model: str) -> None:
        """Сохранить текст и имя модели — второе обязано доходить до читателя."""
        self.text = text
        self.model = model


class HttpNlpClient:
    """Синхронный клиент сервиса моделей.

    Синхронный, потому что вызывается из чистого конвейера, который исполняется в отдельном
    потоке (``asyncio.to_thread``): протаскивать туда цикл событий значило бы делать домен
    асинхронным ради транспорта.
    """

    def __init__(self, base_url: str, *, timeout: float = 600.0) -> None:
        """Настроить клиент; соединение открывается лениво."""
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def _post(self, path: str, payload: Mapping[str, object]) -> Mapping[str, object] | None:
        try:
            with httpx.Client(base_url=self._base_url, timeout=self._timeout) as client:
                response = client.post(path, json=payload)
                response.raise_for_status()
                body = response.json()
                return body if isinstance(body, dict) else None
        except Exception as error:
            _LOGGER.warning("nlp.unavailable", path=path, error=str(error))
            return None

    def translate(
        self, texts: Sequence[str], *, source: str = "en", target: str = "ru"
    ) -> tuple[tuple[str, ...], str | None]:
        """Перевести строки; при отказе вернуть исходные и ``None`` вместо модели."""
        items = list(texts)
        if not items:
            return (), None
        body = self._post("/translate", {"texts": items, "source": source, "target": target})
        if body is None:
            return tuple(items), None
        translated = body.get("texts")
        model = body.get("model")
        if not isinstance(translated, list) or len(translated) != len(items):
            _LOGGER.warning("nlp.translate_shape_mismatch", expected=len(items))
            return tuple(items), None
        return tuple(str(value) for value in translated), (str(model) if model else None)

    def title_case_terms(self, terms: Sequence[str]) -> tuple[tuple[str, ...], str | None]:
        """Перевести названия технологий генеративной моделью.

        Отдельный метод, а не ``translate``: модель-переводчик на названиях ошибается заметно
        чаще («software bill of materials» → «чек материалов»), потому что название технологии —
        это термин, а не фраза. Замер на десяти названиях: переводчик даёт три приемлемых из
        десяти, генеративная модель — восемь.
        """
        items = [term for term in terms if term and term.strip()]
        if not items:
            return (), None
        body = self._post("/translate/terms", {"terms": items})
        if body is None:
            return tuple(items), None
        translated = body.get("terms")
        model = body.get("model")
        if not isinstance(translated, list) or len(translated) != len(items):
            return tuple(items), None
        return tuple(str(value) for value in translated), (str(model) if model else None)

    def trend_statements(
        self, items: Sequence[Mapping[str, object]]
    ) -> tuple[tuple[str | None, ...], str | None]:
        """Каждая тема — одним предложением-трендом; при отказе — ``None`` по всем темам."""
        rows = list(items)
        if not rows:
            return (), None
        body = self._post("/trend-statements", {"items": rows})
        if body is None:
            return tuple(None for _ in rows), None
        statements = body.get("statements")
        model = body.get("model")
        if not isinstance(statements, list) or len(statements) != len(rows):
            _LOGGER.warning("nlp.trends_shape_mismatch", expected=len(rows))
            return tuple(None for _ in rows), None
        return (
            tuple(str(value).strip() or None if value else None for value in statements),
            (str(model) if model else None),
        )

    def summarize(self, *, topic: str, fragments: Sequence[str]) -> tuple[str, str | None, bool]:
        """Русское резюме по фрагментам: текст, модель и признак опоры на фрагменты."""
        body = self._post("/summarize", {"topic": topic, "fragments": list(fragments)})
        if body is None:
            return "", None, False
        return (
            str(body.get("text") or ""),
            (str(body.get("model")) if body.get("model") else None),
            bool(body.get("grounded")),
        )

    def propose_technologies(self, direction: str, limit: int) -> tuple[str, ...]:
        """Имена технологий направления (без доказательств веб-корпуса)."""
        return self.propose_with_evidence(direction, limit)[0]

    def propose_with_evidence(
        self, direction: str, limit: int
    ) -> tuple[tuple[str, ...], dict[str, tuple[str, ...]]]:
        """Спросить у модели имена технологий направления на ранней стадии.

        Предложение не становится выдачей: движок публикует имя только после того, как внешние
        источники показали по нему измеренную активность и дали хотя бы одну настоящую работу
        (ТЗ §3.1). Здесь — только транспорт.
        """
        query = (direction or "").strip()
        if not query or limit <= 0:
            return (), {}
        body = self._post("/propose-technologies", {"query": query[:200], "limit": int(limit)})
        if body is None:
            return (), {}
        names = body.get("technologies")
        if not isinstance(names, list):
            _LOGGER.warning("nlp.propose_shape_mismatch")
            return (), {}
        out: list[str] = []
        for value in names:
            name = str(value).strip()
            if name and name not in out:
                out.append(name[:200])
        # Описания имён из корпуса и панели — определение карточки (разбор 110).
        raw_details = body.get("details")
        self.last_details = (
            {str(k).strip()[:200]: str(v)[:600] for k, v in raw_details.items() if v}
            if isinstance(raw_details, dict)
            else {}
        )
        # Имена, заранее размеченные «да» по критериям жюри (разбор 110).
        raw_approved = body.get("approved")
        self.last_approved = (
            frozenset(str(v).strip()[:200] for v in raw_approved if str(v).strip())
            if isinstance(raw_approved, list)
            else frozenset()
        )
        # Адреса страниц веб-корпуса, где сборщик нашёл имя (разбор 110), — у имён из корпуса.
        raw = body.get("evidence")
        evidence: dict[str, tuple[str, ...]] = {}
        if isinstance(raw, dict):
            for name, urls in raw.items():
                if isinstance(urls, list):
                    evidence[str(name).strip()[:200]] = tuple(str(u) for u in urls if u)
        return tuple(out), evidence

    def judge(
        self,
        candidates: Sequence[str],
        contexts: Mapping[str, Sequence[str]] | None = None,
    ) -> Mapping[str, TechnologyVerdict]:
        """Разметить строки на «название технологии» и «не название»."""
        items = [c for c in candidates if c and c.strip()]
        if not items:
            return {}
        body = self._post(
            "/judge/technology",
            {"candidates": items, "contexts": {name: list((contexts or {}).get(name, ())) for name in items}},
        )
        if body is None:
            return {}
        verdicts = body.get("verdicts")
        if not isinstance(verdicts, list):
            return {}
        out: dict[str, TechnologyVerdict] = {}
        for row in verdicts:
            if not isinstance(row, dict):
                continue
            candidate = row.get("candidate")
            if not isinstance(candidate, str):
                continue
            value = row.get("isTechnology")
            out[candidate] = TechnologyVerdict(
                is_technology=value if isinstance(value, bool) else None,
                reason=str(row.get("reason") or ""),
            )
        return out


class HttpTechnologyProposer:
    """Реализация порта :class:`~horizon_analytics.domain.ports.TechnologyProposer`.

    Отказ сервиса — пустой список, а не исключение: движок обязан посчитать отчёт по корпусу, как
    считал до появления второго источника кандидатов. Недоступная модель делает выдачу уже, но не
    делает её недостоверной.
    """

    def __init__(self, client: HttpNlpClient, model_id: str) -> None:
        """Связать порт с клиентом и раскрытым именем модели."""
        self._client = client
        self._model_id = model_id

    @property
    def model_id(self) -> str:
        """Имя модели — раскрытие по ТЗ §3.1."""
        return self._model_id

    def propose(self, direction: str, limit: int) -> Sequence[str]:
        """Имена технологий направления на ранней стадии; пустой список — модель не ответила."""
        return self._client.propose_technologies(direction, limit)

    def propose_with_evidence(
        self, direction: str, limit: int
    ) -> tuple[Sequence[str], Mapping[str, Sequence[str]]]:
        """Имена и адреса страниц веб-корпуса, подтверждающих имена из корпуса."""
        return self._client.propose_with_evidence(direction, limit)

    @property
    def details(self) -> Mapping[str, str]:
        """Описания имён последнего предложения — определение карточки предложенной темы."""
        return getattr(self._client, "last_details", {}) or {}

    @property
    def approved(self) -> frozenset[str]:
        """Имена последнего предложения, заранее размеченные «да» по критериям жюри."""
        return getattr(self._client, "last_approved", frozenset()) or frozenset()

    def evidence_documents(self, urls: Sequence[str]) -> Sequence[Document]:
        """Страницы веб-корпуса по адресам — те, что не попали в собранный корпус анализа."""
        if not urls:
            return ()
        body = self._client._post("/webcorpus/documents", {"urls": list(urls)[:500]})
        if not isinstance(body, dict) or body.get("status") != "ok":
            return ()
        out = []
        for node in body.get("documents") or []:
            if isinstance(node, dict):
                document = _corpus_document(node)
                if document is not None:
                    out.append(document)
        return tuple(out)


def _corpus_document(node: Mapping[str, object]) -> Document | None:
    """Страница веб-корпуса как документ доказательной базы (разбор 110)."""
    url = str(node.get("url") or "").strip()
    title = str(node.get("title") or "").strip()
    try:
        published_on = date.fromisoformat(str(node.get("publishedOn") or ""))
    except ValueError:
        return None
    if not url or not title:
        return None
    host = str(node.get("host") or "").strip()
    try:
        fetched_at = datetime.fromisoformat(str(node.get("fetchedAt") or ""))
    except ValueError:
        fetched_at = datetime.combine(published_on, time(), tzinfo=UTC)
    venue = str(node.get("venue") or host)
    return Document(
        document_id=f"webcorpus:{node.get('sha256') or url}",
        source_id="webcorpus",
        source_class="NEWS",
        external_id=url,
        title=title,
        published_on=published_on,
        url=url,
        fetched_at=fetched_at,
        abstract_text=str(node.get("excerpt") or "") or None,
        language=str(node.get("language") or "en")[:2],
        venue=Venue(name=venue) if venue else None,
        authors=(Author(full_name=venue or host, organization_name=host or None),),
        relevance=1.0,
    )


class HttpTechnologyJudge:
    """Реализация порта :class:`~horizon_analytics.domain.ports.TechnologyJudge`."""

    def __init__(self, client: HttpNlpClient, model_id: str) -> None:
        """Связать порт с клиентом и раскрытым именем модели."""
        self._client = client
        self._model_id = model_id

    @property
    def model_id(self) -> str:
        """Имя модели, которое уходит в отчёт и в лог."""
        return self._model_id

    def jury(self, query: str, items: Sequence[Mapping[str, object]]) -> list[dict[str, object]]:
        """Экспертная стадия (разбор 110): голоса трёх экспертов по каждой позиции верхушки.

        Отказ сервиса — пустой список, и движок оставляет порядок. Выключается
        ``HORIZON_JURY_ENABLED=false``.
        """
        if os.environ.get("HORIZON_JURY_ENABLED", "true").lower() in {"0", "false", "no"}:
            return []
        body = self._client._post("/jury", {"query": query[:200], "items": list(items)})
        if not isinstance(body, dict):
            return []
        verdicts = body.get("verdicts")
        return [v for v in verdicts if isinstance(v, dict)] if isinstance(verdicts, list) else []

    def rerank(
        self, query: str, titles: Sequence[str], contexts: Sequence[str]
    ) -> tuple[list[int], dict[int, str]]:
        """Переранжирование верхушки моделью (разбор 110): порядок номеров и снимаемые с причиной.

        Отказ сервиса — пустой ответ, и движок оставляет порядок балла. Выключается
        ``HORIZON_RERANK_ENABLED=false``.
        """
        if os.environ.get("HORIZON_RERANK_ENABLED", "true").lower() in {"0", "false", "no"}:
            return [], {}
        body = self._client._post(
            "/rerank-trends", {"query": query[:200], "titles": list(titles), "contexts": list(contexts)}
        )
        if not isinstance(body, dict):
            return [], {}
        order = [int(n) for n in body.get("order") or [] if isinstance(n, int)]
        remove = {int(k): str(v) for k, v in (body.get("remove") or {}).items() if str(k).isdigit()}
        return order, remove

    def judge(self, candidates: Sequence[str]) -> Mapping[str, TechnologyVerdict]:
        """Вернуть вердикты; пустой ответ читается конвейером как «оставить всех»."""
        return self._client.judge(candidates)

    def judge_with_evidence(
        self, candidates: Sequence[str], contexts: Mapping[str, Sequence[str]]
    ) -> Mapping[str, TechnologyVerdict]:
        """Проверить точность названия с фрагментами документов, давших кандидата."""
        return self._client.judge(candidates, contexts)
