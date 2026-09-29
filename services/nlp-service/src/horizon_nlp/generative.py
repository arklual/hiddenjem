"""Генеративная модель: семантические суждения и русское резюме по найденному тексту.

Что здесь можно и чего нельзя — прямое следствие ТЗ. «Не допускается формирование итоговой
выдачи слабых сигналов исключительно на основании знаний языковой модели без подтверждённого
поиска и анализа открытых источников». Поэтому модель здесь **никогда не производит
кандидатов и никогда не считает баллы**. Она делает ровно две вещи:

* отвечает «является ли эта строка названием технологии» — то есть **отсекает** мусор,
  который уже пришёл из поиска; добавить она ничего не может;
* пересказывает по-русски **переданные ей фрагменты источников** — и ответ проверяется на
  то, что он опирается на переданное.

Отказ модели — не отказ продукта. Каждая функция возвращает «не знаю», и вызывающая сторона
обязана трактовать это как «оставить как было»: выключенная модель даёт ровно ту выдачу,
которая была до неё.
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import ClassVar

import httpx
import structlog

from horizon_nlp.config import settings

__all__ = [
    "GenerativeClient",
    "Summary",
    "TechnologyVerdict",
    "generative",
]

log = structlog.get_logger(__name__)

_VERDICT_LINE = re.compile(
    r"^\s*(\d+)\s*[.:)]\s*(ДА|НЕТ|YES|NO)\b[\s—–-]*(.*)$", re.IGNORECASE
)


@dataclass(frozen=True, slots=True)
class TechnologyVerdict:
    """Суждение об одной строке-кандидате."""

    candidate: str
    #: ``True`` — название технологии, ``False`` — не название, ``None`` — модель не ответила.
    is_technology: bool | None
    reason: str


@dataclass(frozen=True, slots=True)
class Summary:
    """Русское резюме по переданным фрагментам."""

    text: str
    model: str
    grounded: bool


class GenerativeClient:
    """Клиент локальной генеративной модели (ollama, OpenAI-совместимый формат).

    Режим рассуждения выключен явно: на процессоре он стоит сотни токенов, то есть минуты, а
    для суждения «да/нет» не даёт ничего. Температура нулевая и seed фиксирован — два прогона
    одного отчёта дают один ответ.
    """

    _GATE_SYSTEM = (
        "Ты помогаешь аналитику технологических трендов отделять названия технологий от "
        "случайных словосочетаний, вырезанных из текста статей. "
        "ДА — конкретный технический метод, механизм, протокол, архитектура, материал или "
        "устройство, который можно отличить от соседних решений по принципу работы. "
        "НЕТ — область, рынок, цель, сценарий применения или широкий класс продуктов без "
        "указания конкретного технического решения; также свойство, оценка, кусок фразы, "
        "название организации, страны или географии и канцелярит научного текста. "
        "Например, digital financial ecosystem, financial infrastructure, AI solutions, "
        "autonomous agents и tokenized assets — НЕТ; confidential cloud infrastructure, "
        "distributed ledger storage и zero-knowledge payment proofs — ДА, если именно "
        "такой механизм назван в источнике. Фрагменты документов — свидетельства: проверь, "
        "что само имя обозначает этот механизм, а не широкую рубрику статьи. При отсутствии "
        "фрагментов суди только о конкретности имени. Не делай вывод о новизне по одному имени. "
        "Отвечай строго по одной строке на кандидата в формате: "
        "«НОМЕР: ДА — причина» или «НОМЕР: НЕТ — причина». "
        "Причина — не больше семи слов, по-русски. Ничего кроме этих строк не пиши."
    )

    _SUMMARY_SYSTEM = (
        "Ты помогаешь аналитику технологических трендов. Напиши по-русски краткое резюме "
        "(одно-два предложения) строго по приведённым фрагментам источников. "
        "Не добавляй ничего, чего нет во фрагментах. Не оценивай и не рекламируй. "
        "Не пиши вступлений вроде «в этом тексте»."
    )

    def __init__(self) -> None:
        """Держать один пул соединений: модель обслуживает запросы по очереди."""
        self._openai = settings.generative_backend == "openai"
        if self._openai:
            if not settings.openai_base_url or not settings.openai_api_key:
                raise ValueError(
                    "HORIZON_NLP_GENERATIVE_BACKEND=openai требует HORIZON_NLP_OPENAI_BASE_URL "
                    "и HORIZON_NLP_OPENAI_API_KEY"
                )
            self._client = httpx.Client(
                base_url=settings.openai_base_url.rstrip("/"),
                timeout=settings.generative_timeout_seconds,
                headers={"Authorization": f"Bearer {settings.openai_api_key}"},
            )
        else:
            self._client = httpx.Client(
                base_url=settings.generative_base_url.rstrip("/"),
                timeout=settings.generative_timeout_seconds,
            )

    @property
    def model(self) -> str:
        """Имя модели, которое уходит в ответ и в лог (раскрытие по ТЗ §3.1)."""
        return settings.generative_model

    _RERANK_SYSTEM = (
        "You are the lead analyst of a weak technology signals radar for a bank. You get a user "
        "query and numbered candidate trends with evidence snippets. Rank the candidates from the "
        "best weak signal for THIS query to the worst: a specific technology or application at an "
        "early market stage (pilots, first products, seed/Series A, first standards), clearly "
        "within the query's field and gaining traction. Put last: off-topic items, generic or "
        "long-established concepts (e.g. 'control plane', 'latent space', 'data augmentation', "
        "'graph neural networks'), company events (acquisitions, consolidation, funding news "
        "itself), a single product or model name (e.g. 'XCENA MX1', 'GPT-5'), hobbyist hacks, and "
        "fragments that are not a technology or application. "
        "Also list the numbers that should be REMOVED: clearly off-topic, generic/mainstream "
        "concepts, company events or non-technologies. "
        'Answer JSON only: {"order": [numbers, best first], "remove": {"<number>": "reason, <=7 words"}}'
    )

    def rerank_trends(
        self, query: str, titles: Sequence[str], contexts: Sequence[str]
    ) -> tuple[list[int], dict[int, str], str]:
        """Порядок кандидатов верхушки и снимаемые с причиной; отказ модели — пустой ответ."""
        if not titles:
            return [], {}, self.model
        listing = "\n".join(
            f"{i}. {title}" + (f"\n   Evidence: {context[:300]}" if context else "")
            for i, (title, context) in enumerate(zip(titles, contexts, strict=False), 1)
        )
        try:
            answer = self._complete(
                self._RERANK_SYSTEM, f"Query: {query}\n\nCandidates:\n{listing}",
                num_predict=min(40 * len(titles) + 400, 4000),
            )
            data = json.loads(answer[answer.find("{") : answer.rfind("}") + 1])
        except Exception as error:
            log.warning("generative.rerank_failed", error=str(error))
            return [], {}, self.model
        order = [int(n) for n in data.get("order") or [] if str(n).isdigit() and 0 < int(n) <= len(titles)]
        remove = {
            int(k): str(v)[:80]
            for k, v in (data.get("remove") or {}).items()
            if str(k).isdigit() and 0 < int(k) <= len(titles)
        }
        log.info("generative.reranked", items=len(titles), ordered=len(order), removed=len(remove))
        return list(dict.fromkeys(order)), remove, self.model

    def complete_with(self, model: str, system: str, user: str, max_tokens: int) -> str:
        """Вызов названной модели того же облачного API (экспертная стадия, разбор 110)."""
        if not self._openai or not model:
            return self._complete(system, user, num_predict=max_tokens)
        return self._complete_openai(system, user, num_predict=max_tokens, model=model)

    def complete_text(self, system: str, user: str, max_tokens: int) -> str:
        """Один вызов модели: системная и пользовательская реплики, ответ текстом.

        Для графа панели экспертов (разбор 110): он сам разбирает JSON и сам решает, что делать
        при отказе.
        """
        return self._complete(system, user, num_predict=max_tokens)

    def _complete(self, system: str, user: str, *, num_predict: int) -> str:
        if self._openai:
            return self._complete_openai(system, user, num_predict=num_predict)
        started = time.perf_counter()
        payload = {
            "model": settings.generative_model,
            "prompt": user,
            "system": system,
            "stream": False,
            # Рассуждение выключено: см. докстринг класса.
            "think": False,
            "options": {
                "temperature": 0,
                "top_p": 1,
                "seed": 20260917,
                "num_predict": num_predict,
                "num_thread": settings.generative_num_thread,
            },
        }
        response = self._client.post("/api/generate", json=payload)
        if response.status_code >= 500:
            # Один повтор, и только на ошибку сервера. Процесс модели ollama бывает убит по памяти
            # посреди прогона (замер стенда 2026-09-18: llama-server дорос до 8,3 ГБ за восемь
            # часов работы и получил OOM в 10:09), а ollama поднимает его заново на следующем
            # запросе. Без повтора весь русский слой отчёта терялся из-за одного вызова.
            log.warning("generative.retry_after_server_error", status=response.status_code)
            time.sleep(2.0)
            response = self._client.post("/api/generate", json=payload)
        response.raise_for_status()
        body = response.json()
        text = (body.get("response") or "").strip()
        log.info(
            "generative.call",
            model=settings.generative_model,
            prompt_tokens=body.get("prompt_eval_count"),
            output_tokens=body.get("eval_count"),
            elapsed_ms=round((time.perf_counter() - started) * 1000.0),
        )
        return text

    def _post_completion(self, payload: dict[str, object], *, timeout: float | None = None) -> httpx.Response:
        """``/chat/completions`` с двумя повторами при всплеске (429, 5xx) и обрыве связи.

        Облачный API отвечает 429 и 5xx при всплесках и изредка рвёт соединение
        (``Connection reset by peer``): одним таким обрывом глубокое исследование «юридических
        технологий» остановилось на второй минуте из пяти с половиной, прочитав три страницы
        (стенд, 29.09). Терять из-за одного вызова проверку, русский слой отчёта или исследование
        незачем.
        """
        kwargs: dict[str, object] = {"json": payload}
        if timeout is not None:
            kwargs["timeout"] = timeout
        for attempt in range(3):
            last = attempt == 2
            try:
                response = self._client.post("/chat/completions", **kwargs)  # type: ignore[arg-type]
            except httpx.TransportError as error:
                if last:
                    raise
                log.warning("generative.retry_after_transport_error", error=type(error).__name__)
            else:
                if last or not (response.status_code == 429 or response.status_code >= 500):
                    return response
                log.warning("generative.retry_after_server_error", status=response.status_code)
            time.sleep(3.0 * (attempt + 1))
        raise AssertionError("unreachable")  # pragma: no cover

    def _complete_openai(
        self, system: str, user: str, *, num_predict: int, model: str | None = None
    ) -> str:
        """Тот же вызов через OpenAI-совместимый ``/chat/completions``.

        Потолок ответа шире локального: модели семейства gpt-5 тратят часть бюджета на
        рассуждение (замер: около сорока токенов на короткий запрос), и тот же ``num_predict``
        обрезал бы видимый ответ — а обрезанный список судьи читается как «модель промолчала».
        """
        started = time.perf_counter()
        payload = {
            "model": model or settings.generative_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "max_completion_tokens": num_predict * 2 + 1024,
            "reasoning_effort": settings.openai_reasoning_effort,
        }
        response = self._post_completion(payload)
        response.raise_for_status()
        body = response.json()
        choices = body.get("choices") or [{}]
        text = str((choices[0].get("message") or {}).get("content") or "").strip()
        usage = body.get("usage") or {}
        log.info(
            "generative.call",
            model=settings.generative_model,
            backend="openai",
            prompt_tokens=usage.get("prompt_tokens"),
            output_tokens=usage.get("completion_tokens"),
            elapsed_ms=round((time.perf_counter() - started) * 1000.0),
        )
        return text

    @property
    def supports_tools(self) -> bool:
        """Умеет ли бэкенд вызывать инструменты. Глубокое исследование — только облачный API."""
        return self._openai

    def chat_with_tools(
        self,
        messages: Sequence[dict[str, object]],
        tools: Sequence[dict[str, object]],
        *,
        timeout: float,
        max_completion_tokens: int = 4096,
        reasoning_effort: str | None = None,
    ) -> tuple[dict[str, object], dict[str, int]]:
        """Один ход диалога с инструментами: сообщение модели и расход токенов.

        Нужен агенту глубокого исследования (``horizon_nlp.research``) и только ему: остальные
        задачи сервиса — один вопрос и один ответ. Повтор тот же, что у ``_complete_openai``: один,
        на 429 и 5xx. Исключение — отказ модели; что с ним делать, решает цикл агента.
        """
        if not self._openai:
            raise RuntimeError("инструменты доступны только бэкенду openai")
        started = time.perf_counter()
        payload = {
            "model": settings.generative_model,
            "messages": list(messages),
            "tools": list(tools),
            # Несколько вызовов за ход: поиск по трём источникам и чтение пяти страниц — один ход
            # модели, а не восемь. Исполняет их агент, параллельно по разным хостам.
            "parallel_tool_calls": True,
            "max_completion_tokens": max_completion_tokens,
            "reasoning_effort": reasoning_effort or settings.openai_reasoning_effort,
        }
        response = self._post_completion(payload, timeout=timeout)
        response.raise_for_status()
        body = response.json()
        choices = body.get("choices") or [{}]
        message = dict(choices[0].get("message") or {})
        usage_raw = body.get("usage") or {}
        usage = {
            "prompt_tokens": int(usage_raw.get("prompt_tokens") or 0),
            "completion_tokens": int(usage_raw.get("completion_tokens") or 0),
        }
        log.info(
            "generative.call",
            model=settings.generative_model,
            backend="openai",
            tools=len(tools),
            prompt_tokens=usage["prompt_tokens"],
            output_tokens=usage["completion_tokens"],
            elapsed_ms=round((time.perf_counter() - started) * 1000.0),
        )
        return message, usage

    def judge_technologies(
        self, candidates: Sequence[str], contexts: Mapping[str, Sequence[str]] | None = None
    ) -> tuple[TechnologyVerdict, ...]:
        """Разметить строки на «название технологии» и «не название».

        Одним запросом на весь список, а не по одному на кандидата: модель на процессоре
        отвечает медленно, и тридцать отдельных запросов стоят тридцать прогревов контекста.

        Кандидат, о котором модель не сказала ничего внятного, получает ``None`` — и это
        означает «оставить», а не «убрать»: молчание модели не должно удалять тему.
        """
        items = [c.strip() for c in candidates if c and c.strip()]
        if not items:
            return ()
        listing = "\n".join(
            "\n".join(
                [f"{index}. {item}"]
                + [f"   Источник {number}: {excerpt[:500]}" for number, excerpt in enumerate(
                    (contexts or {}).get(item, ())[:2], 1
                )]
            )
            for index, item in enumerate(items, 1)
        )
        try:
            # Потолок берётся от числа кандидатов, а не от общей настройки: одна строка ответа
            # стоит около двенадцати токенов, и при сорока пяти кандидатах общий лимит в 512
            # обрывал ответ на середине. Оборванный ответ не ошибка — он читается как «модель не
            # высказалась», то есть молча оставляет хвост верхушки непроверенным.
            answer = self._complete(
                self._GATE_SYSTEM,
                f"Кандидаты:\n{listing}",
                num_predict=min(24 * len(items) + 96, 2048),
            )
        except Exception as error:
            log.warning("generative.judge_failed", error=str(error))
            return tuple(
                TechnologyVerdict(
                    candidate=item, is_technology=None, reason="модель недоступна"
                )
                for item in items
            )
        verdicts: dict[int, tuple[bool, str]] = {}
        for line in answer.splitlines():
            match = _VERDICT_LINE.match(line)
            if not match:
                continue
            index = int(match.group(1))
            positive = match.group(2).upper() in {"ДА", "YES"}
            verdicts[index] = (positive, match.group(3).strip()[:120])
        result: list[TechnologyVerdict] = []
        for index, item in enumerate(items, 1):
            hit = verdicts.get(index)
            if hit is None:
                result.append(
                    TechnologyVerdict(
                        candidate=item,
                        is_technology=None,
                        reason="модель не ответила по этой строке",
                    )
                )
            else:
                result.append(
                    TechnologyVerdict(
                        candidate=item, is_technology=hit[0], reason=hit[1]
                    )
                )
        return tuple(result)

    _TERMS_SYSTEM = (
        "Ты переводишь названия технологий на русский язык для аналитического отчёта. "
        "Отвечай строго по одной строке на вход в формате «НОМЕР: перевод». "
        "Переводи как принято в русской технической литературе; устоявшиеся англицизмы "
        "оставляй как есть. Ничего кроме этих строк не пиши."
    )

    def translate_terms(self, terms: Sequence[str]) -> tuple[tuple[str, ...], str]:
        """Перевести названия технологий генеративной моделью, одним запросом на список.

        Почему не моделью-переводчиком. Название технологии — термин, а не фраза, и Marian на
        нём ошибается заметно чаще: замер на десяти названиях дал «software bill of materials» →
        «чек материалов» и «selective state space model» → «Модель избирательного состояния»,
        три приемлемых перевода из десяти против восьми у генеративной модели.

        Строка, о которой модель не сказала ничего, возвращается как есть: непереведённое
        название читается хуже переведённого, но лучше подставленного наугад.
        """
        items = [term.strip() for term in terms if term and term.strip()]
        if not items:
            return (), self.model
        listing = "\n".join(f"{index}. {item}" for index, item in enumerate(items, 1))
        try:
            answer = self._complete(
                self._TERMS_SYSTEM, listing, num_predict=min(24 * len(items) + 64, 768)
            )
        except Exception as error:
            log.warning("generative.terms_failed", error=str(error))
            return tuple(items), self.model
        translated: dict[int, str] = {}
        for line in answer.splitlines():
            match = re.match(r"^\s*(\d+)\s*[.:)]\s*(.+)$", line)
            if match:
                translated[int(match.group(1))] = match.group(2).strip()[:200]
        return (
            tuple(translated.get(index, item) for index, item in enumerate(items, 1)),
            self.model,
        )

    _EXPAND_SYSTEM = (
        "Ты помогаешь аналитику искать слабые технологические сигналы — технологии на ранней "
        "стадии, о которых пишут мало. Дано направление в формулировке аналитика и его опорные "
        "термины. Предложи поисковые запросы на английском для научных и технических баз "
        "(arXiv, OpenAlex, Semantic Scholar, GitHub). Каждый запрос — название конкретной "
        "технологии, механизма, архитектуры или применения из двух-пяти слов, так, как его пишут "
        "в статьях и документации, а не общая тема. Распредели запросы поровну на три группы: "
        "core — конкретные технологии внутри направления; emerging — узкие и появившиеся за "
        "последние один-три года; edge — стыки направления с соседними областями, где новое "
        "появляется раньше всего. Не повторяй опорные термины и не давай общих слов вроде "
        "«machine learning», «security», «optimization». Ответь только JSON-массивом объектов "
        "{\"query\": строка, \"group\": \"core\"|\"emerging\"|\"edge\"} без пояснений."
    )

    #: Дополнение промпта, когда нужны русские и китайские формулировки. Не перевод, а термин, каким
    #: его пишут в русскоязычной и китайской литературе: дословный перевод («федеральное обучение»)
    #: в каталоге не находит ничего, а устойчивый термин («федеративное обучение», «联邦学习»)
    #: находит работы, которых нет в англоязычном поиске.
    _EXPAND_LANGUAGES: ClassVar[dict[str, str]] = {
        "ru": "\"ru\": один-три варианта устоявшегося русского термина этой подтемы через точку "
        "с запятой, так, как его пишут в заголовках русскоязычных статей (например «туманные "
        "вычисления; туманная архитектура»)",
        "zh": "\"zh\": один-три варианта устоявшегося китайского термина этой подтемы через точку "
        "с запятой, так, как его пишут в заголовках китайских статей, 简体 (например «计算卸载; "
        "计算任务卸载»)",
    }

    def expand_query(
        self,
        query: str,
        targets: Sequence[str],
        limit: int,
        languages: Sequence[str] = ("en",),
    ) -> tuple[tuple[tuple[str, str, str], ...], str]:
        """Разложить направление на узкие поисковые запросы: (запрос, группа, язык) и имя модели.

        ``languages`` — на каких языках спрашивать источники. Английский есть всегда; русский и
        китайский приходят той же подтемой в форме, принятой в литературе на этом языке, и идут
        сразу за английской: так при нехватке бюджета подтема теряет язык, а не язык — подтемы.

        Зачем. Сбор спрашивает источники словами направления и получает его центр: самые
        релевантные по «edge computing» работы — про edge computing вообще. Слабый сигнал лежит на
        краю поля и в первые тысячи ответов по общим словам не попадает — сравнение с размеченным
        датасетом (разбор 90) нашло в собранном корпусе хотя бы слова лишь у сорока технологий из
        ста. Узкие запросы по подтемам и стыкам направления достают до краёв.

        Модель только предлагает, **что спросить**; находят источники. Выдача по-прежнему не
        формируется «исключительно на основании знаний языковой модели» (ТЗ §3.1). Отказ модели —
        пустой набор, и сбор идёт как раньше, одним запросом направления.
        """
        items = [query.strip()] if query and query.strip() else []
        if not items or limit <= 0:
            return (), self.model
        anchor = ", ".join(t for t in targets if t and "." not in t) or "—"
        extra = [lang for lang in dict.fromkeys(languages) if lang in self._EXPAND_LANGUAGES]
        system = self._EXPAND_SYSTEM
        if extra:
            system += (
                " Добавь в каждый объект поля: "
                + "; ".join(self._EXPAND_LANGUAGES[lang] for lang in extra)
                + ". Если устоявшегося термина на этом языке нет, оставь поле пустым."
            )
        try:
            answer = self._complete(
                system,
                f"Направление: {query.strip()}\nОпорные термины: {anchor}\n"
                f"Сколько запросов: {limit}",
                num_predict=min((40 + 40 * len(extra)) * limit + 128, 4096),
            )
        except Exception as error:
            log.warning("generative.expand_failed", error=str(error))
            return (), self.model
        start, end = answer.find("["), answer.rfind("]")
        if start < 0 or end <= start:
            log.warning("generative.expand_unparsed", answer=answer[:200])
            return (), self.model
        try:
            raw = json.loads(answer[start : end + 1])
        except ValueError:
            log.warning("generative.expand_unparsed", answer=answer[:200])
            return (), self.model
        seen: set[str] = {t.strip().lower() for t in targets}
        result: list[tuple[str, str, str]] = []
        subtopics = 0
        for entry in raw if isinstance(raw, list) else []:
            if not isinstance(entry, dict):
                continue
            phrase = _clean_query(str(entry.get("query", "")))
            group = str(entry.get("group", "core")).lower()
            group = group if group in {"core", "emerging", "edge"} else "core"
            if not phrase or phrase.lower() in seen:
                continue
            seen.add(phrase.lower())
            result.append((phrase, group, "en"))
            for lang in extra:
                variant = _clean_query(str(entry.get(lang) or ""), lang)
                if variant and variant.lower() not in seen:
                    seen.add(variant.lower())
                    result.append((variant, group, lang))
            subtopics += 1
            if subtopics >= limit:
                break
        log.info(
            "generative.expanded",
            query=query,
            model=self.model,
            queries=[f"{lang}:{q}" for q, _, lang in result],
        )
        return tuple(result), self.model

    _PROPOSE_SYSTEM = (
        "Ты помогаешь аналитику искать слабые технологические сигналы. Дано направление в "
        "формулировке аналитика. Назови конкретные технологии этого направления, находящиеся на "
        "ранней стадии: о них уже есть работы и репозитории, но массового внедрения и "
        "сформированного рынка ещё нет. Каждое имя — так, как технологию называют в английских "
        "статьях и документации: два-пять слов, название конкретного метода, механизма, "
        "архитектуры, протокола, материала или устройства. Не давай названий областей, рынков, "
        "целей и широких классов без технического механизма («digital financial ecosystem», "
        "«financial infrastructure», «AI solutions», «autonomous agents», «tokenized assets»), "
        "не давай общих слов («machine learning», «security»), "
        "названий компаний, продуктов одной фирмы и уже массовых технологий. Ответь только "
        "JSON-массивом строк без пояснений."
    )

    def propose_technologies(
        self, direction: str, limit: int
    ) -> tuple[tuple[str, ...], str]:
        """Назвать технологии направления на ранней стадии: имена и раскрытие модели.

        Зачем это существует. Извлечение достаёт кандидатов только из собранного корпуса, и замер
        (`docs/01-analysis/94-checking-my-own-explanations.md`, Э1) показал потолок этого пути: у
        82 технологий размеченного эталона из ста фраза не встречается в корпусе ни разу, а
        шестнадцать из них OpenAlex знает сотнями работ. Мы их просто не спросили — и никакой
        скоринг корпусных кандидатов этого не исправит.

        Чего этот метод **не** делает: он не производит выдачу. Имя, о котором открытые источники
        молчат, отбрасывается вызывающей стороной — проверка измеренных свидетельств живёт в
        аналитическом движке, а не здесь. Поэтому выдача по-прежнему не формируется «исключительно
        на основании знаний языковой модели» (ТЗ §3.1): модель говорит, **о чём спросить**,
        отвечают источники.

        Отказ модели — пустой набор: движок работает по корпусу, как работал.
        """
        query = (direction or "").strip()
        if not query or limit <= 0:
            return (), self.model
        try:
            answer = self._complete(
                self._PROPOSE_SYSTEM,
                f"Направление: {query}\nСколько технологий: {limit}",
                num_predict=min(16 * limit + 128, 4096),
            )
        except Exception as error:
            log.warning("generative.propose_failed", error=str(error))
            return (), self.model
        start, end = answer.find("["), answer.rfind("]")
        if start < 0 or end <= start:
            log.warning("generative.propose_unparsed", answer=answer[:200])
            return (), self.model
        try:
            raw = json.loads(answer[start : end + 1])
        except ValueError:
            log.warning("generative.propose_unparsed", answer=answer[:200])
            return (), self.model
        names: list[str] = []
        seen: set[str] = set()
        for entry in raw if isinstance(raw, list) else []:
            name = _clean_query(str(entry if isinstance(entry, str) else entry.get("name", "")))
            if not name or name.lower() in seen:
                continue
            seen.add(name.lower())
            names.append(name)
            if len(names) >= limit:
                break
        log.info("generative.proposed", query=query, model=self.model, count=len(names))
        return tuple(names), self.model

    _TRANSLATE_DOCS_SYSTEM = (
        "You translate metadata of scientific papers and technical documents into English for "
        "term extraction. Keep every technical term in its standard English form as it is written "
        "in English-language papers (e.g. «федеративное обучение» → «federated learning», "
        "«联邦学习» → «federated learning»), keep acronyms, product names, numbers and formulas "
        "as they are. Translate faithfully, do not summarise, do not add anything. Input is a "
        "JSON array of objects {\"id\", \"title\", \"abstract\"}. Answer only with a JSON array "
        "of objects {\"id\", \"title\", \"abstract\"} in the same order; an empty abstract stays "
        "empty."
    )

    #: Сколько знаков аннотации уходит в перевод. Для извлечения терминов важны первые фразы —
    #: постановка и метод; хвост аннотации удваивал бы время и цену без новых терминов.
    TRANSLATE_ABSTRACT_CHARS = 900

    def translate_documents(
        self, items: Sequence[tuple[str, str, str]]
    ) -> tuple[tuple[tuple[str, str, str], ...], str]:
        """Перевести заголовки и аннотации на английский: (id, заголовок, аннотация) и модель.

        Зачем не Marian. Замер 2026-09-19 на аннотациях КиберЛенинки: opus-mt-ru-en переводит
        «федеративное обучение» как «federal training» в шести заголовках из шести. Для отчёта это
        хуже, чем без перевода: документ уходит в тему, которой не существует, и не усиливает ту,
        о которой он написан. Генеративная модель держит термины. Её недетерминированность
        гасится на стороне сбора: перевод делается один раз и хранится вместе с документом.

        Документы, которых нет в ответе, не возвращаются: вызывающий оставит их без перевода.
        """
        payload = [
            {
                "id": str(identifier),
                "title": (title or "").strip(),
                "abstract": (abstract or "").strip()[: self.TRANSLATE_ABSTRACT_CHARS],
            }
            for identifier, title, abstract in items
            if identifier and ((title or "").strip() or (abstract or "").strip())
        ]
        if not payload:
            return (), self.model
        size = sum(len(entry["title"]) + len(entry["abstract"]) for entry in payload)
        answer = self._complete(
            self._TRANSLATE_DOCS_SYSTEM,
            json.dumps(payload, ensure_ascii=False),
            num_predict=min(size // 2 + 64 * len(payload) + 256, 16000),
        )
        start, end = answer.find("["), answer.rfind("]")
        if start < 0 or end <= start:
            log.warning("generative.translate_documents_unparsed", answer=answer[:200])
            return (), self.model
        try:
            raw = json.loads(answer[start : end + 1])
        except ValueError:
            log.warning("generative.translate_documents_unparsed", answer=answer[:200])
            return (), self.model
        wanted = {entry["id"] for entry in payload}
        result: list[tuple[str, str, str]] = []
        for entry in raw if isinstance(raw, list) else []:
            if not isinstance(entry, dict):
                continue
            identifier = str(entry.get("id", ""))
            title = " ".join(str(entry.get("title") or "").split())
            abstract = " ".join(str(entry.get("abstract") or "").split())
            # Перевод, в котором остались кириллица или иероглифы, — не перевод: такой документ
            # лучше оставить как есть и перевести в следующий раз.
            if identifier in wanted and title and not _NON_LATIN.search(title):
                wanted.discard(identifier)
                result.append((identifier, title, abstract))
        log.info(
            "generative.translated_documents",
            model=self.model,
            asked=len(payload),
            translated=len(result),
        )
        return tuple(result), self.model

    _SEARCH_SYSTEM = (
        "Ты помогаешь аналитику искать технологии в англоязычных научных и технических "
        "источниках. На вход — развёрнутая формулировка технологии по-русски. Верни КОРОТКУЮ "
        "английскую поисковую фразу ИЗ ДВУХ-ЧЕТЫРЁХ СЛОВ, называющую саму технологию так, как её "
        "называют в статьях и документации. Не описывай применение и не перечисляй области — "
        "только имя технологии. Без кавычек, без пояснений, без предлогов в конце. "
        "Отвечай строго по одной строке на вход в формате «НОМЕР: фраза». "
        "Ничего кроме этих строк не пиши."
    )

    def search_phrases(
        self, formulations: Sequence[str]
    ) -> tuple[tuple[str, ...], str]:
        """Превратить развёрнутые русские формулировки в короткие английские поисковые фразы.

        Шаг существует потому, что иначе замер измеряет не то. Название в датасете методолога —
        предложение («Квантово-инспирированное сжатие моделей для запуска LLM на edge-железе»), а
        в источниках технология называется фразой из трёх слов. Вырезание латиницы из названия
        даёт «edge» и «ERP» — компонент вместо технологии, и поиск по нему отвечает о другом.

        Строка, о которой модель не сказала ничего, возвращается пустой: пусть замер честно
        покажет, что формулировки для неё не нашлось, чем подставит наугад.
        """
        items = [item.strip() for item in formulations if item and item.strip()]
        if not items:
            return (), self.model
        listing = "\n".join(f"{index}. {item}" for index, item in enumerate(items, 1))
        try:
            answer = self._complete(
                self._SEARCH_SYSTEM,
                listing,
                num_predict=min(20 * len(items) + 64, 1024),
            )
        except Exception as error:
            log.warning("generative.search_phrases_failed", error=str(error))
            return tuple("" for _ in items), self.model
        phrases: dict[int, str] = {}
        for line in answer.splitlines():
            match = re.match(r"^\s*(\d+)\s*[.:)]\s*(.+)$", line)
            if match:
                phrases[int(match.group(1))] = match.group(2).strip().strip('"«»')[:120]
        return (
            tuple(phrases.get(index, "") for index in range(1, len(items) + 1)),
            self.model,
        )

    _RU_SYSTEM = (
        "Ты переводишь на русский язык фрагменты научно-технических текстов для аналитического "
        "отчёта: определения тем, предложения из аннотаций и названия статей. Переводи точно и "
        "грамотно, без пересказа и без добавлений. Технические термины передавай так, как их "
        "пишут в русской технической литературе; устоявшиеся англицизмы, аббревиатуры, названия "
        "моделей, продуктов, наборов данных и компаний оставляй как есть (LLM, GPT-4, RAG, "
        "ChatGPT). Числа и формулы не меняй. Вход — JSON-массив объектов {\"id\", \"text\"}. "
        "Ответ — только JSON-массив объектов {\"id\", \"text\"} в том же порядке, без пояснений."
    )
    #: Строк в одном вызове: пятнадцать тем отчёта — это сотни строк, и одним вызовом они не
    #: помещаются в ответ модели. Порции идут параллельно.
    RU_BATCH: ClassVar[int] = 30
    RU_PARALLEL: ClassVar[int] = 4

    def translate_russian(self, texts: Sequence[str]) -> tuple[tuple[str, ...], str]:
        """Перевести строки на русский генеративной моделью; порядок и число строк сохраняются.

        Замена машинного переводчика для русского слоя отчёта. Marian на научном тексте выдавал
        «Cachemir, Cache Acece acecred H momomomomophoric» вместо предложения из аннотации и
        «побег из тюрем» вместо «джейлбрейка»: переводчик слово в слово не знает терминов.

        Пустая строка, уже русская строка и строка, о которой модель промолчала, возвращаются как
        есть: непереведённый оригинал лучше перевода наугад.
        """
        items = list(texts)
        todo = [
            (index, text.strip())
            for index, text in enumerate(items)
            if text and text.strip() and not re.search(r"[а-яё]", text, re.IGNORECASE)
        ]
        result = list(items)
        if not todo:
            return tuple(result), self.model
        batches = [todo[start : start + self.RU_BATCH] for start in range(0, len(todo), self.RU_BATCH)]

        def run(batch: list[tuple[int, str]]) -> dict[int, str]:
            payload = [{"id": index, "text": text[:1500]} for index, text in batch]
            size = sum(len(entry["text"]) for entry in payload)
            try:
                answer = self._complete(
                    self._RU_SYSTEM,
                    json.dumps(payload, ensure_ascii=False),
                    num_predict=min(size + 48 * len(payload) + 256, 16000),
                )
            except Exception as error:
                log.warning("generative.translate_ru_failed", error=str(error))
                return {}
            start, end = answer.find("["), answer.rfind("]")
            try:
                raw = json.loads(answer[start : end + 1]) if start >= 0 and end > start else []
            except ValueError:
                log.warning("generative.translate_ru_unparsed", answer=answer[:200])
                return {}
            wanted = {index for index, _ in batch}
            done: dict[int, str] = {}
            for entry in raw if isinstance(raw, list) else []:
                if not isinstance(entry, dict):
                    continue
                try:
                    index = int(entry.get("id"))
                except (TypeError, ValueError):
                    continue
                text = " ".join(str(entry.get("text") or "").split())
                # Перевод без кириллицы — не перевод (модель вернула оригинал или пустоту).
                if index in wanted and text and re.search(r"[а-яё]", text, re.IGNORECASE):
                    done[index] = text
            return done

        with ThreadPoolExecutor(max_workers=self.RU_PARALLEL) as pool:
            for done in pool.map(run, batches):
                for index, text in done.items():
                    result[index] = text
        log.info(
            "generative.translated_russian",
            model=self.model,
            asked=len(todo),
            translated=sum(1 for index, _ in todo if result[index] != items[index]),
        )
        return tuple(result), self.model

    _TREND_SYSTEM = (
        "Ты пишешь аналитический отчёт о зарождающихся технологических трендах для людей без "
        "технической подготовки. Для каждой темы даны: направление, термин так, как его пишут в "
        "литературе, русское название, стадия, год первого достоверного упоминания, число "
        "документов по годам и фрагменты источников. Сформулируй тренд ОДНИМ полным предложением "
        "по-русски, чтобы любой понял, что именно меняется: кто или что начинает делать новое, "
        "к чему переходит и ради чего или вместо чего. Образец формы: «Разработчики защитных "
        "систем начинают ставить в инфраструктуру ловушки-приманки, которые выдают взломщика при "
        "первом обращении, вместо того чтобы только закрывать уязвимости». "
        "Правила: опирайся только на фрагменты и числа темы, не придумывай компаний, продуктов, "
        "чисел и фактов, которых там нет; можно привести одно число из ряда по годам, если оно "
        "показывает рост; без оценочных слов («революционный», «бурный», «прорывной»); не начинай "
        "со слов «Тема», «Тренд», «Технология»; 15–35 слов; термин называй по-русски, оригинал "
        "можно дать в скобках. Ответ — строго по строке на тему в формате «НОМЕР: предложение», "
        "больше ничего."
    )

    def formulate_trends(
        self, items: Sequence[Mapping[str, object]]
    ) -> tuple[tuple[str | None, ...], str]:
        """Сформулировать каждую тему отчёта как тренд — одним русским предложением.

        Термин из двух-четырёх слов («canary tokens») отвечает на вопрос «о чём», но не «что
        происходит»: читатель без подготовки видит словосочетание, а не тренд. Предложение
        говорит, что меняется и в какую сторону, — и собирается только из того, что уже есть в
        отчёте: фрагментов источников темы и измеренного ряда по годам. Новых фактов модель
        не добавляет; добавить она может только слова, связывающие найденное.

        Проверка ответа — та же граница ТЗ §3.1, что и у резюме, но строже по числам: число, которого
        нет ни во входном ряду, ни во фрагментах, означает выдумку, и такое предложение
        отбрасывается целиком. Отброшенное или непришедшее — ``None``: карточка покажет термин, как
        раньше, а не подставленное наугад.
        """
        rows = [dict(item) for item in items]
        if not rows:
            return (), self.model
        blocks = [_trend_block(index, row) for index, row in enumerate(rows, 1)]
        try:
            answer = self._complete(
                self._TREND_SYSTEM,
                "\n\n".join(blocks),
                num_predict=min(110 * len(rows) + 64, 2400),
            )
        except Exception as error:
            log.warning("generative.trends_failed", error=str(error))
            return tuple(None for _ in rows), self.model
        written: dict[int, str] = {}
        for line in answer.splitlines():
            match = re.match(r"^\s*(\d+)\s*[.:)]\s*(.+)$", line)
            if match:
                written[int(match.group(1))] = match.group(2).strip()
        result: list[str | None] = []
        rejected = 0
        for index, row in enumerate(rows, 1):
            sentence = _accept_trend(written.get(index, ""), row)
            if sentence is None and index in written:
                rejected += 1
            result.append(sentence)
        log.info("generative.trends", items=len(rows), written=len(written), rejected=rejected)
        return tuple(result), self.model

    def summarize_russian(self, *, topic: str, fragments: Sequence[str]) -> Summary:
        """Резюме по фрагментам — по-русски, без добавлений от себя.

        ``grounded`` говорит, опирается ли ответ на переданное: доля значимых слов ответа,
        встречающихся во фрагментах. Это не доказательство отсутствия выдумки, но оно ловит
        главный наблюдаемый отказ — ответ «из общих знаний», когда фрагменты бесполезны.
        """
        useful = [f.strip() for f in fragments if f and f.strip()]
        if not useful:
            return Summary(text="", model=self.model, grounded=False)
        body = "\n".join(f"[{index}] {text}" for index, text in enumerate(useful, 1))
        try:
            text = self._complete(
                self._SUMMARY_SYSTEM,
                f"Тема: {topic}\nФрагменты источников:\n{body}",
                num_predict=220,
            )
        except Exception as error:
            log.warning("generative.summary_failed", error=str(error))
            return Summary(text="", model=self.model, grounded=False)
        return Summary(text=text, model=self.model, grounded=_grounded(text, useful))



_DIGITS = re.compile(r"\d+(?:[.,]\d+)?")
_FORBIDDEN_OPENINGS = ("тема", "тренд", "технология")


def _trend_block(index: int, row: Mapping[str, object]) -> str:
    """Одна тема во входе модели: факты строками, фрагменты — пронумерованным списком."""
    lines = [f"### {index}"]
    for key, label in (
        ("direction", "Направление"),
        ("term", "Термин"),
        ("title", "Русское название"),
        ("stage", "Стадия"),
        ("firstYear", "Первое достоверное упоминание"),
        ("series", "Документов по годам"),
    ):
        value = row.get(key)
        if value not in (None, "", [], ()):
            lines.append(f"{label}: {value}")
    fragments = [str(f).strip() for f in row.get("fragments") or () if str(f).strip()]  # type: ignore[union-attr]
    for number, fragment in enumerate(fragments[:5], 1):
        lines.append(f"[{number}] {fragment[:360]}")
    return "\n".join(lines)


def _accept_trend(sentence: str, row: Mapping[str, object]) -> str | None:
    """Предложение, прошедшее проверку, или ``None``.

    Отбрасывается: не по-русски; слишком короткое или длинное для одного предложения; начатое
    словом-заглушкой («Тема …» — это снова шаблон, а не тренд); с числом, которого нет во входе.
    """
    text = sentence.strip().strip("«»\"'").strip()
    if not text or not re.search(r"[а-яё]", text, re.IGNORECASE):
        return None
    words = text.split()
    if len(words) < 8 or len(words) > 45:
        return None
    if words[0].lower().strip(".,:;") in _FORBIDDEN_OPENINGS:
        return None
    source = " ".join(
        str(row.get(key) or "") for key in ("term", "title", "firstYear", "series", "direction")
    ) + " " + " ".join(str(f) for f in row.get("fragments") or ())  # type: ignore[union-attr]
    allowed = {number.replace(",", ".") for number in _DIGITS.findall(source)}
    for number in _DIGITS.findall(text):
        if number.replace(",", ".") not in allowed:
            return None
    if not text.endswith((".", "!", "?")):
        text += "."
    return text[0].upper() + text[1:]

def _grounded(answer: str, fragments: Sequence[str]) -> bool:
    """Доля слов ответа длиннее четырёх букв, встречающихся во фрагментах, ≥ 0.2."""
    haystack = " ".join(fragments).lower()
    words = re.findall(r"[\w-]{5,}", answer.lower())
    if not words:
        return False
    hits = sum(1 for word in words if word[:6] in haystack)
    return hits / len(words) >= 0.2


generative = GenerativeClient()


#: Поисковый запрос: латиница, цифры, дефис и пробелы, от двух до шести слов.
_QUERY_ALLOWED = re.compile(r"[^A-Za-z0-9\- ]+")
_QUERY_ALLOWED_RU = re.compile(r"[^A-Za-zА-Яа-яЁё0-9\- ]+")
_QUERY_ALLOWED_ZH = re.compile(r"[^A-Za-z0-9\u4e00-\u9fff\- ]+")
_CJK = re.compile(r"[\u4e00-\u9fff]")
_CYRILLIC = re.compile(r"[А-Яа-яЁё]")
_NON_LATIN = re.compile(r"[А-Яа-яЁё\u4e00-\u9fff]")


def _clean_query(text: str, language: str = "en") -> str:
    """Привести предложенный моделью запрос к форме, которую примет любой каталог.

    Английский запрос — фраза из двух-шести слов. Русский и китайский — один-три устоявшихся
    варианта термина, каждый в кавычках, через ИЛИ: ``"计算卸载" OR "计算任务卸载"``. Без кавычек каталоги режут такой запрос на
    отдельные слова и иероглифы и отвечают чем угодно — замер 2026-09-19 по «периферийным
    вычислениям»: МРТ коленного сустава, осадки на Тибете, доклады генсекретаря ООН. Длинная фраза в
    кавычках, наоборот, не находит ничего: «边缘数字孪生» — ноль работ.

    Русский термин обязан быть русским, китайский — китайским: английское слово в поле ``ru``
    повторило бы английский запрос, а не нашло русскую литературу.
    """
    if language in {"ru", "zh"}:
        terms: list[str] = []
        for part in re.split(r"[;；,，/]", text):
            term = _clean_term(part, language)
            if term and term not in terms:
                terms.append(term)
        # Через ИЛИ: модель даёт варианты одного понятия («выгрузка вычислений; делегирование
        # вычислений»), и через И — так OpenAlex соединяет термины по умолчанию — каждый запрос
        # стенда 2026-09-19 нашёл ноль работ: документ обязан был содержать оба синонима сразу.
        return " OR ".join(f'"{term}"' for term in terms[:3])
    cleaned = " ".join(_QUERY_ALLOWED.sub(" ", text).split())
    words = cleaned.split()
    if not 2 <= len(words) <= 6:
        return ""
    return cleaned


def _clean_term(text: str, language: str) -> str:
    """Один термин на русском или китайском; пустая строка, если он не на своём языке."""
    if language == "zh":
        cleaned = " ".join(_QUERY_ALLOWED_ZH.sub(" ", text).split())
        hanzi = len(_CJK.findall(cleaned))
        return cleaned if hanzi >= 2 and len(cleaned.replace(" ", "")) <= 12 else ""
    cleaned = " ".join(_QUERY_ALLOWED_RU.sub(" ", text).split())
    if not _CYRILLIC.search(cleaned):
        return ""
    return cleaned if 1 <= len(cleaned.split()) <= 4 else ""
