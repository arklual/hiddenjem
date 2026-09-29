"""Кэш собранных признаков на диске.

Смысл кэша тут не в скорости, а в воспроизводимости. Сбор идёт часами и упирается в лимит GitHub
(десять запросов в минуту без токена); без кэша любая правка CLI означала бы новый полный проход и
новые числа — сравнить две версии выборки было бы не с чем. С кэшем повторный запуск той же фразы
к тому же источнику вообще не ходит в сеть, и обучающая таблица пересобирается из одних и тех же
наблюдений.

Отказы **не кэшируются**. Иначе один сетевой сбой застрял бы в выборке навсегда и выглядел бы как
свойство технологии, а не как свойство того дня. Неответивший источник просто попробуют снова.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from pathlib import Path

from horizon_analytics.signals.models import SourceResult

__all__ = ["DEFAULT_CACHE_DIR", "SignalCache"]

_LOG = logging.getLogger(__name__)

#: Куда складывать кэш, если каталог не задан явно (``HORIZON_SIGNALS_CACHE_DIR``). Каталог во
#: временной файловой системе: кэш ускоряет повторные анализы, но не обязан переживать перезапуск.
DEFAULT_CACHE_DIR = Path("/tmp/horizon-signals-cache")

_UNSAFE = re.compile(r"[^a-z0-9]+")


class SignalCache:
    """Файловый кэш с ключом «источник + фраза».

    Имя файла — читаемый огрызок фразы плюс хэш от неё целиком: огрызок нужен человеку, который
    полезет смотреть кэш глазами, хэш — чтобы две разные длинные фразы не легли в один файл.
    """

    def __init__(self, root: Path) -> None:
        """Args: root: корневой каталог кэша; создаётся при первой записи."""
        self._root = Path(root)

    @property
    def root(self) -> Path:
        """Корень кэша."""
        return self._root

    def path_for(self, source: str, term: str) -> Path:
        """Файл, в котором лежит (или будет лежать) ответ источника про термин."""
        digest = hashlib.sha1(term.strip().lower().encode("utf-8")).hexdigest()[:12]
        slug = _UNSAFE.sub("-", term.strip().lower()).strip("-")[:48] or "term"
        return self._root / source / f"{slug}-{digest}.json"

    def get(self, source: str, term: str, version: int) -> SourceResult | None:
        """Читает кэш. ``None`` — если записи нет, она битая или собрана другим разбором."""
        path = self.path_for(source, term)
        if not path.is_file():
            return None
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            _LOG.warning("кэш %s повреждён (%s), перечитываю источник", path, exc)
            return None
        if payload.get("term", "").strip().lower() != term.strip().lower():
            # Коллизия хэша либо чужой файл: молча подсунуть чужие числа нельзя.
            _LOG.warning("кэш %s про другой термин, перечитываю источник", path)
            return None
        try:
            result = SourceResult.from_json(source, payload)
        except (KeyError, ValueError) as exc:
            _LOG.warning("кэш %s не разбирается (%s), перечитываю источник", path, exc)
            return None
        if result.version != version:
            _LOG.info("кэш %s собран разбором v%d, нужен v%d", path, result.version, version)
            return None
        return result

    def put(self, term: str, result: SourceResult) -> None:
        """Складывает удачный ответ. Отказы пропускает — см. докстроку модуля."""
        if not result.ok:
            return
        path = self.path_for(result.source, term)
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"term": term, "source": result.source, **result.to_json()}
        # Запись через временный файл: прерванный сбор не должен оставить обрезанный JSON,
        # который в следующий раз молча прочитается как «данных нет».
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(path)
