"""Выбор поставщика эмбеддингов и его отказ.

Эта функция решает каждый балл в каждом отчёте: смена модели меняет близость, кластеризацию,
связность — то есть всё ранжирование. При этом она не проверялась ничем: поставщиков три, ветвей
отказа две, и ни одной проверки. Тот же образец «построено, но не проверено», что уже находился в
ArchUnit, `hypothesis`, `import-linter` и учёте пропусков в гейте.

Главное здесь не выбор, а **честность отказа**. Оператор просит `onnx`, файла модели нет — служба не
падает и продолжает работать на модели по умолчанию. Это осознанное решение (ADR-0009: неверно
настроенный необязательный поставщик не должен ронять службу), и оно допустимо ровно потому, что
подмена не молчалива: отчёт несёт `model_id` фактически использованного поставщика, а не
запрошенного. Иначе аналитик получил бы другие баллы под именем той модели, которую не запускали, —
и сравнение отчётов между собой потеряло бы смысл.

Проверяется поэтому не «какой класс вернулся», а это свойство: **отчёт не может назвать модель,
которой не пользовался**.
"""

from __future__ import annotations

import pytest

from horizon_analytics.config import Settings
from horizon_analytics.container import build_embedding_provider

DEFAULT_MODEL_ID = "tfidf-svd-384-v1"


def settings(**overrides: object) -> Settings:
    """Настройки по псевдонимам — так их читает служба из окружения."""
    return Settings.model_validate({"HORIZON_FIXTURE_CORPUS_PATH": "/dev/null", **overrides})


class TestTheDefaultIsTheDeterministicOne:
    def test_without_configuration_the_deterministic_provider_is_used(self) -> None:
        # Детерминированная модель по умолчанию — основание ADR-0015: тот же корпус даёт тот же
        # отчёт. Поставщик, ходящий по сети, этого не обещает.
        assert build_embedding_provider(settings()).model_id == DEFAULT_MODEL_ID

    def test_the_dimension_reaches_the_model_id(self) -> None:
        # Размерность входит в идентификатор, потому что меняет результат: отчёты, посчитанные на
        # разной ширине вектора, сравнивать между собой нельзя, и это должно быть видно.
        provider = build_embedding_provider(settings(HORIZON_EMBEDDING_DIM=128))

        assert provider.model_id == "tfidf-svd-128-v1"


class TestAFailedProviderCannotBorrowTheNameOfAnother:
    """Отчёт не может назвать модель, которой не пользовался."""

    def test_onnx_without_a_model_file_falls_back_and_says_so(self) -> None:
        # Оператор просил onnx, файла нет. Служба не падает — но и не выдаёт чужие числа за
        # обещанные: `model_id` называет ту модель, которая считала.
        provider = build_embedding_provider(
            settings(HORIZON_EMBEDDING_PROVIDER="onnx", HORIZON_EMBEDDING_MODEL_PATH="/нет/такого")
        )

        assert provider.model_id == DEFAULT_MODEL_ID
        assert "onnx" not in provider.model_id

    def test_http_without_a_url_falls_back_and_says_so(self) -> None:
        # Незаданный адрес — опечатка в манифесте развёртывания, а не чьё-то решение. Молчание тут
        # было бы худшим видом деградации: все баллы поменялись бы, а имя модели осталось прежним.
        provider = build_embedding_provider(settings(HORIZON_EMBEDDING_PROVIDER="http"))

        assert provider.model_id == DEFAULT_MODEL_ID

    def test_http_with_a_url_is_used_and_names_itself(self) -> None:
        provider = build_embedding_provider(
            settings(
                HORIZON_EMBEDDING_PROVIDER="http",
                HORIZON_EMBEDDING_URL="http://embeddings.internal/v1",
            )
        )

        assert provider.model_id != DEFAULT_MODEL_ID

    @pytest.mark.parametrize(
        "configured",
        [
            {"HORIZON_EMBEDDING_PROVIDER": "onnx", "HORIZON_EMBEDDING_MODEL_PATH": "/нет/такого"},
            {"HORIZON_EMBEDDING_PROVIDER": "http"},
            {},
        ],
    )
    def test_a_provider_is_always_returned_and_always_named(
        self, configured: dict[str, object]
    ) -> None:
        # Свойство, а не пример: чем бы ни кончился выбор, служба получает работающий поставщик с
        # непустым именем. Пустое имя в отчёте читалось бы как «модель неизвестна», а неизвестной
        # она не бывает — бывает не той, о которой просили.
        provider = build_embedding_provider(settings(**configured))

        assert provider.model_id
        assert provider.dimension > 0


class TestTheOnnxBranchIsReachable:
    """Ветка `onnx` выбирается, когда её просят.

    Проверяется достижимость выбора, а не сам ONNX: без `onnxruntime` и файла модели успешный путь
    в этом окружении не запустить, и делать вид, что он покрыт, значит записать себе несуществующую
    проверку. Но без этой проверки ветку можно снять целиком — подмена `if False` оставалась
    незамеченной, потому что при отсутствующем файле модели поведение совпадает с деградацией.

    Подставляется класс поставщика, а не сам движок: проверяется проводка выбора, и она либо ведёт
    к запрошенному классу, либо нет.
    """

    def test_the_requested_provider_is_constructed(self, monkeypatch: pytest.MonkeyPatch) -> None:
        import horizon_analytics.adapters.embeddings.onnx as onnx_module

        class FakeOnnx:
            def __init__(self, path: str, *, dimension: int) -> None:
                self.path = path
                self._dimension = dimension

            @property
            def model_id(self) -> str:
                return "onnx-fake-v1"

            @property
            def dimension(self) -> int:
                return self._dimension

        monkeypatch.setattr(onnx_module, "OnnxEmbeddingProvider", FakeOnnx)

        provider = build_embedding_provider(
            settings(
                HORIZON_EMBEDDING_PROVIDER="onnx",
                HORIZON_EMBEDDING_MODEL_PATH="/модель.onnx",
            )
        )

        assert provider.model_id == "onnx-fake-v1"
