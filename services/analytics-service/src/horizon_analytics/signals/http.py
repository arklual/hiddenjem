"""Сетевой слой сбора: собственный темп на каждый источник и честные повторы.

Каждый источник ограничивает нас по-своему, и общего ограничителя мало: GitHub без токена даёт
десять поисковых запросов в минуту, arXiv просит три секунды между обращениями, OpenAlex пускает
в «вежливый пул» за контактный адрес. Поэтому темп задаётся на источник (:class:`Pace`), а потоки
сбора делятся по источникам — тогда медленный GitHub не держит быструю Википедию.

Повторы сделаны узко: 429, 5xx и «403 — превышен лимит» с ожиданием, всё остальное — сразу отказ.
Долбиться в 404 или в 400 бессмысленно. А вот 403 приходится разбирать отдельно: GitHub отвечает
на исчерпанную квоту именно 403, а не 429, и отличить его от «доступ запрещён» можно только по
заголовкам ``X-RateLimit-Remaining`` и ``Retry-After``. Пока этого разбора не было, любой сосед по
IP-адресу, выбравший квоту поиска, превращал GitHub в отказ на весь пакет.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from collections.abc import Mapping
from typing import Any

import httpx

__all__ = ["DEFAULT_CONTACT", "Fetcher", "Pace", "SourceUnavailableError", "user_agent"]

_LOG = logging.getLogger(__name__)

#: Контакт в User-Agent. Все пять источников просят его, OpenAlex за него пускает в вежливый пул.
#: Личный адрес в умолчании не хранится: он уходил бы в заголовке каждого исходящего запроса у
#: всякого, кто развернёт сборку. Адрес задаётся окружением на стенде (`HORIZON_CONTACT_EMAIL`
#: в `.env`), без него продукт работает, но OpenAlex отвечает по общей квоте, а не по вежливой.
DEFAULT_CONTACT = os.environ.get("HORIZON_CONTACT_EMAIL", "").strip()


def user_agent(contact: str = DEFAULT_CONTACT) -> str:
    """Строка User-Agent с контактом — по ней нас можно найти и попросить остановиться."""
    contact = (contact or "").strip()
    return (
        f"horizon-analytics-signals/1.0 (+{contact})"
        if contact
        else "horizon-analytics-signals/1.0"
    )


class SourceUnavailableError(RuntimeError):
    """Источник не ответил. Поднимается, ловится в сборщике, оседает в поле ``error``."""

    def __init__(self, message: str, *, status: int | None = None) -> None:
        """Preserve the response status for callers that can switch credentials."""
        super().__init__(message)
        self.status = status


class Pace:
    """Не чаще, чем раз в ``interval`` секунд, сколько бы потоков ни спрашивало.

    Ограничитель именно временной, а не «N запросов в окне»: окно позволяет выпустить всю квоту
    залпом, после чего источник отвечает 429 и мы всё равно ждём — только уже по его правилам и
    дольше. Ровный шаг проходит лимит, ни разу его не задев.
    """

    def __init__(self, interval: float) -> None:
        """Args: interval: минимальный промежуток между запросами, секунды."""
        self._interval = max(0.0, interval)
        self._lock = threading.Lock()
        self._next_at = 0.0

    def wait(self, deadline: float | None = None) -> bool:
        """Выдержать темп источника.

        Args:
            deadline: Момент (``time.monotonic``), позже которого ждать бессмысленно.

        Returns:
            ``False`` — очередь к источнику длиннее отпущенного времени, запрос делать не нужно.
            Ожидание темпа само по себе неограниченно: при десяти потоках и шаге в секунду
            последний в очереди спит десять секунд, а при исчерпанной квоте ``defer`` отодвигает
            очередь ещё на пять минут. Без этой проверки бюджет этапа перестаёт быть бюджетом.
        """
        with self._lock:
            now = time.monotonic()
            delay = self._next_at - now
            self._next_at = max(now, self._next_at) + self._interval
        if delay <= 0:
            return deadline is None or time.monotonic() < deadline
        if deadline is not None and time.monotonic() + delay >= deadline:
            return False
        time.sleep(delay)
        return True

    def defer(self, seconds: float) -> None:
        """Отодвигает следующий запрос — когда источник сам попросил подождать."""
        with self._lock:
            self._next_at = max(self._next_at, time.monotonic() + seconds)


class Fetcher:
    """HTTP-клиент одного источника: свой темп, свои заголовки, свои повторы."""

    def __init__(
        self,
        *,
        pace: Pace,
        headers: Mapping[str, str] | None = None,
        timeout: float = 45.0,
        attempts: int = 4,
        client: httpx.Client | None = None,
    ) -> None:
        """Готовит клиент источника.

        Args:
            pace: Ограничитель темпа этого источника.
            headers: Дополнительные заголовки (например, авторизация GitHub).
            timeout: Таймаут запроса.
            attempts: Сколько раз повторять на 429/5xx.
            client: Готовый клиент — подменяется в тестах.
        """
        self._pace = pace
        self._attempts = max(1, attempts)
        self._timeout = timeout
        self._deadline: float | None = None
        self._client = client or httpx.Client(
            headers={"User-Agent": user_agent(), **dict(headers or {})},
            timeout=timeout,
            follow_redirects=True,
        )

    @property
    def pace(self) -> Pace:
        """Темп источника — нужен тем, кто сам разбирает заголовки лимита."""
        return self._pace

    def set_deadline(self, deadline: float | None) -> None:
        """Ограничить клиент во времени: после ``deadline`` запросов больше не будет.

        Срок ставится снаружи и на клиент целиком, а не передаётся в каждый ``get``, потому что
        источник ходит в сеть изнутри своего разборщика: у Википедии два запроса, у OpenAlex три,
        и каждый из них обязан уместиться в бюджет термина, ничего об этом бюджете не зная.

        Клиент у каждого потока свой (:meth:`_SourceSpec.make_fetcher`), поэтому срок —
        обыкновенное поле, а не общее состояние.
        """
        self._deadline = deadline

    def _remaining(self) -> float | None:
        """Сколько времени осталось; ``None`` — срок не ставили."""
        if self._deadline is None:
            return None
        return self._deadline - time.monotonic()

    def close(self) -> None:
        """Закрывает соединения."""
        self._client.close()

    def get(
        self, url: str, *, params: Mapping[str, Any] | None = None,
        rotate_on_limit: bool = False,
        headers: Mapping[str, str] | None = None,
    ) -> httpx.Response:
        """Запрос с соблюдением темпа и повторами на 429/5xx.

        Raises:
            SourceUnavailable: если после всех попыток ответа нет либо код не 2xx.
        """
        last: str = "неизвестно"
        for attempt in range(1, self._attempts + 1):
            remaining = self._remaining()
            if remaining is not None and remaining <= 0.0:
                raise SourceUnavailableError(f"{url}: {last}, время источника вышло")
            if not self._pace.wait(self._deadline):
                raise SourceUnavailableError(f"{url}: очередь к источнику длиннее бюджета времени")
            remaining = self._remaining()
            if remaining is not None and remaining <= 0.0:
                raise SourceUnavailableError(f"{url}: {last}, время источника вышло")
            try:
                # Таймаут запроса не может быть больше остатка бюджета: иначе сорок пять секунд
                # чтения из молчащего сокета переживают любой потолок этапа.
                response = self._client.get(
                    url,
                    params=dict(params or {}),
                    headers=dict(headers or {}),
                    timeout=self._timeout if remaining is None else min(self._timeout, remaining),
                )
            # `InvalidURL`, `StreamError` и `CookieConflict` в httpx **не** наследуют `HTTPError`,
            # и до этой строки они улетали сквозь сборщик и уносили с собой поток источника —
            # вместе со всем пакетом, который ждал от этого потока ответа. Повторять их
            # бессмысленно: запрос в такой форме не выполнится никогда.
            except (httpx.InvalidURL, httpx.StreamError, httpx.CookieConflict) as exc:
                raise SourceUnavailableError(f"{url}: {type(exc).__name__}: {exc}") from exc
            except httpx.HTTPError as exc:
                last = f"{type(exc).__name__}: {exc}"
                _LOG.warning("%s попытка %d/%d: %s", url, attempt, self._attempts, last)
                self._pace.defer(2.0 * attempt)
                continue
            if response.is_success:
                return response
            last = f"HTTP {response.status_code}"
            if rotate_on_limit and (response.status_code == 429 or _is_throttled(response)):
                raise SourceUnavailableError(f"{url}: {last}", status=response.status_code)
            if response.status_code in (429, 500, 502, 503, 504) or _is_throttled(response):
                self._pace.defer(_retry_after(response, fallback=_backoff(attempt)))
                _LOG.warning("%s попытка %d/%d: %s", url, attempt, self._attempts, last)
                continue
            raise SourceUnavailableError(f"{url}: {last}")
        raise SourceUnavailableError(f"{url}: {last} после {self._attempts} попыток")


def _backoff(attempt: int) -> float:
    """Пауза перед следующей попыткой: 5, 10, 20, 40 секунд, но не больше минуты.

    Ровной паузы не хватает. Когда источник отвечает 429, дело обычно не в одном неудачном
    запросе, а в том, что его квота выбрана целиком — в том числе соседом по IP-адресу, про
    которого мы ничего не знаем. Пять секунд в такой ситуации приводят к трём одинаковым отказам
    подряд; удвоение даёт квоте время восстановиться.
    """
    return float(min(60.0, 5.0 * 2 ** (attempt - 1)))


def _is_throttled(response: httpx.Response) -> bool:
    """403 из-за исчерпанной квоты, а не из-за запрета доступа.

    Признак — обнулённый счётчик остатка либо прямая просьба подождать. Без этой проверки повтор
    после настоящего «доступ запрещён» был бы именно тем поведением, за которое источники банят.
    """
    if response.status_code != 403:
        return False
    return response.headers.get("x-ratelimit-remaining") == "0" or bool(
        response.headers.get("Retry-After")
    )


def _retry_after(response: httpx.Response, *, fallback: float) -> float:
    """Сколько ждать: столько, сколько попросил источник, но не абсурдно долго."""
    raw = response.headers.get("Retry-After")
    if raw:
        try:
            return min(300.0, max(0.0, float(raw)))
        except ValueError:
            pass
    reset = response.headers.get("x-ratelimit-reset")
    if reset:
        try:
            return min(300.0, max(0.0, float(reset) - time.time()) + 1.0)
        except ValueError:
            pass
    return fallback
