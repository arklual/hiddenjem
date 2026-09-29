"""Отчёт не зависит от затравки хеша процесса.

Обещание продукта — «один вопрос даёт один отчёт». Проверка воспроизводимости, которая уже есть,
сравнивает два прогона **в одном процессе**: у них общая затравка хеша, общий порядок обхода
множеств и словарей. Именно поэтому она не может увидеть тот класс недетерминизма, ради которого в
методологии заведены явные тай-брейки — порядок, зависящий от расположения объектов в хеш-таблице.

Установлено пробой (`tools/probe.sh`): снятие тай-брейка из ключа ранжирования, из отбора однословных
терминов и из отбора документов не роняло ни одной проверки во всём наборе. Тай-брейки существовали
и не держались ничем.

Здесь конвейер запускается в двух отдельных процессах с разными значениями ``PYTHONHASHSEED`` и
сравнивается байт в байт то, что уходит наружу. Это единственный способ поймать зависимость от
порядка обхода: в пределах одного процесса она невидима, потому что порядок там стабилен.

Медленно намеренно: два полных прогона конвейера. Дешевле, чем отчёт, который у аналитика и у
проверяющего различается, а объяснить это нечем.

Чего эта проверка **не** делает, и это измерено, а не предположено. Снятие тай-брейка из ключа
ранжирования она не ловит: проба показала, что отчёт при этом не меняется. Причина в том, что
``sorted`` устойчив, а список, который сортируется, собирается обходом списков, а не множеств, — то
есть порядок при равных баллах задаётся входным порядком, и тот от затравки хеша не зависит.

Отсюда точное утверждение о тай-брейках: сегодня они защитные, а не несущие. Они станут несущими в
тот день, когда на пути к сортировке появится обход множества или словаря, — и вот тогда эта
проверка и покраснеет. Записывать её в «проверку тай-брейков» было бы приписыванием заслуги: она
проверяет свойство отчёта, а не наличие строки в коде.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

CORPUS = Path(__file__).resolve().parents[4] / "fixtures" / "corpus" / "documents.jsonl"

#: Прогон конвейера в отдельном процессе: печатает контракт отчёта одной строкой.
RUNNER = """
import json, sys
from datetime import date
from pathlib import Path

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.application.dto import build_domain_analyzed
from horizon_analytics.domain.models import AnalysisParams
from horizon_analytics.domain.pipeline import AnalysisPipeline, PipelineRequest
from horizon_analytics.domain.scoring.profile import MethodologyProfile

profile = MethodologyProfile.default()
result = AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider()).run(
    PipelineRequest(
        normalized_query=sys.argv[1],
        documents=load_documents(Path(sys.argv[2])),
        params=AnalysisParams(top_n=15, years_window=8),
        profile=profile,
        window_from=date(2018, 1, 1),
        window_to=date(2025, 12, 31),
        today=date(2026, 1, 1),
    )
)
payload = build_domain_analyzed(
    result,
    research_request_id="00000000-0000-4000-8000-000000000000",
    attempt=1,
    snapshot_id="snapshot-golden",
    profile=profile,
)
payload.pop("stageTimingsMs", None)
sys.stdout.write(json.dumps(payload, ensure_ascii=False, sort_keys=False))
"""


def contract_with_seed(query: str, seed: str) -> str:
    environment = dict(os.environ, PYTHONHASHSEED=seed, PYTHONDONTWRITEBYTECODE="1")
    completed = subprocess.run(
        [sys.executable, "-c", RUNNER, query, str(CORPUS)],
        env=environment,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr[-2000:]
    return completed.stdout


@pytest.mark.golden
def test_two_hash_seeds_give_the_same_report() -> None:
    # Падение, а не пропуск. Корпус лежит в репозитории и отслеживается git — его отсутствие
    # означает сломанный путь, а не отсутствующее окружение. Пропущенная проверка выглядит в
    # отчёте пройденной: в этом проекте так уже терялись целые файлы проверок, когда путь
    # разъезжался на один каталог.
    assert CORPUS.is_file(), f"эталонный корпус не найден: {CORPUS}"

    first = contract_with_seed("искусственный интеллект машинное обучение", "0")
    second = contract_with_seed("искусственный интеллект машинное обучение", "12345")

    # Сравнение по байтам контракта, а не по списку тем: разойтись может порядок внутри темы,
    # набор источников, порядок индикаторов — всё то, что аналитик увидит, а список ключей скроет.
    assert first == second


@pytest.mark.golden
def test_the_ranking_itself_is_stable_across_seeds() -> None:
    """Отдельно — сам порядок тем, чтобы отчёт о поломке называл её сразу.

    Сравнение целых контрактов отвечает «различаются», а этот случай — «различается ранжирование»,
    то есть ровно то, за что отвечают тай-брейки.
    """
    # Падение, а не пропуск. Корпус лежит в репозитории и отслеживается git — его отсутствие
    # означает сломанный путь, а не отсутствующее окружение. Пропущенная проверка выглядит в
    # отчёте пройденной: в этом проекте так уже терялись целые файлы проверок, когда путь
    # разъезжался на один каталог.
    assert CORPUS.is_file(), f"эталонный корпус не найден: {CORPUS}"

    ranks = [
        [
            trend["trendKey"]
            for trend in json.loads(contract_with_seed("квантовые вычисления", seed))["trends"]
        ]
        for seed in ("0", "12345")
    ]

    assert ranks[0] == ranks[1]
