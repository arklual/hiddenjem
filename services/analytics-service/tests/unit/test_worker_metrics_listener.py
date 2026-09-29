"""Роль worker отдаёт свои метрики — иначе самый долгий этап конвейера невидим.

У роли `worker` нет HTTP-API, поэтому эндпойнта `/metrics` у неё нет. Prometheus скрёб
`analytics-worker:9100` по конфигурации, где рядом стоял комментарий «роль поднимает отдельный
слушатель prometheus_client», — а слушателя не существовало ни строкой. Длительности стадий, число
кандидатов и отсечения фильтров считались и не собирались никем: панели дашборда по ним оставались
пустыми, и пустой график читался как «ничего не происходило».
"""

from __future__ import annotations

import socket
import urllib.request

from horizon_analytics.observability import STAGE_DURATION, serve_metrics


def _free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def test_the_listener_exposes_the_pipeline_registry() -> None:
    port = _free_port()
    STAGE_DURATION.labels(stage="scoring").observe(0.25)

    serve_metrics(port)

    with urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics", timeout=5) as response:
        body = response.read().decode("utf-8")

    # Реестр именно тот, в который пишет конвейер: слушатель с чужим реестром отдавал бы
    # стандартные метрики процесса и выглядел бы работающим.
    assert "horizon_analytics_stage_duration_seconds" in body


def test_an_occupied_port_does_not_stop_the_worker() -> None:
    """Отказ порта не должен ронять анализ: он важнее наблюдаемости за собой."""
    port = _free_port()
    with socket.socket() as taken:
        taken.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        taken.bind(("127.0.0.1", port))
        taken.listen(1)

        serve_metrics(port)  # не бросает


def test_the_worker_starts_the_listener_before_consuming() -> None:
    """Слушатель поднимается на входе в цикл потребления.

    Проверка структурная — по исходнику, а не по поведению: запустить `run_worker` целиком нельзя
    без Kafka и Postgres, а мокать их значило бы проверять подмену. Structural здесь достаточно:
    вопрос ровно один — вызывается ли `serve_metrics`, и раньше ответ был «нигде».
    """
    import inspect

    from horizon_analytics.worker.main import run_worker

    source = inspect.getsource(run_worker)
    assert "serve_metrics(resolved.worker_metrics_port)" in source


def test_the_log_carries_the_trace_id_not_the_whole_header() -> None:
    """`traceparent` — это заголовок, а в лог нужен идентификатор трассы.

    Grafana связывает строку лога с трассой в Tempo по голому идентификатору. Пока в поле
    `trace_id` клался весь заголовок `00-<trace-id>-<span-id>-<flags>`, связь не работала бы:
    значение не совпадает ни с чем, что знает Tempo, — и выглядело при этом правдоподобно.
    """
    from horizon_analytics.observability import trace_id_of

    assert (
        trace_id_of("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        == "4bf92f3577b34da6a3ce929d0e0e4736"
    )
    assert trace_id_of(None) is None
    # Сообщения, отправленные до перехода на W3C, несут голый идентификатор: он возвращается как
    # есть — потеря корреляции дороже, чем совместимость со старым форматом.
    assert trace_id_of("4bf92f3577b34da6a3ce929d0e0e4736") == "4bf92f3577b34da6a3ce929d0e0e4736"
