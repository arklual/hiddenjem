#!/usr/bin/env python3
"""Переменная окружения, выставленная деплоем, кем-то читается.

Найдено чтением: `docker-compose.yml` задавал шлюзу адрес upstream одним именем, а шлюз читал
другое. Совпадений — ноль, поэтому все маршруты падали на умолчание `localhost`, где
внутри контейнера не слушает никто. Так же расходились реквизиты базы у analytics-service и все
переключатели коннекторов у ingestion-service: `HORIZON_SOURCE_FIXTURE_ENABLED: "false"` в
production не выключал ничего, и продовый стенд поднялся бы с эталонным корпусом как источником.

Ошибка тихая в самом опасном смысле: контейнер стартует, health-check зелёный, переменная стоит в
файле — и не делает ничего. Ни компилятор, ни тесты, ни линтер конфигурации этого не видят, потому
что обе стороны по отдельности корректны.

Проверка идёт по обеим сторонам:

* **прямая** — каждая переменная, выставленная сервису, кем-то читается;
* **обратная** — каждая переменная, которую сервис читает, кем-то выставляется (или объявлена как
  та, у которой умолчание и есть рабочее значение).

Вторая нужна не меньше: настройка, о существовании которой знает только код, не настраивается.

    python3 tools/check-env-wiring.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover — гейт пропускает проверку явно, а не молча
    print("нет PyYAML — проверка связности переменных не выполнена")
    sys.exit(0)

ROOT = Path(__file__).resolve().parent.parent

#: Контейнер деплоя → каталог сервиса. Две роли analytics-service запускаются из одного образа
#: (ADR-0014), поэтому читают одну и ту же конфигурацию.
SERVICES = {
    "gateway": "gateway",
    "trends-service": "trends-service",
    "ingestion-service": "ingestion-service",
    "analytics-api": "analytics-service",
    "analytics-worker": "analytics-service",
}

#: Префиксы, которые потребляет не наш код, а среда исполнения. Не «мы решили не проверять», а
#: «читатель существует и находится вне репозитория».
FRAMEWORK_PREFIXES = (
    "SPRING_",  # relaxed binding Spring Boot: SPRING_DATASOURCE_URL → spring.datasource.url
    "OTEL_",  # javaagent OpenTelemetry и SDK
    "JAVA_",  # JAVA_TOOL_OPTIONS и родственные
    "JDK_",
    "MANAGEMENT_",  # актуаторы, тоже relaxed binding
    "LOGGING_",
    "SERVER_",  # server.port, server.shutdown — тоже свойства Spring
)

#: Переменные, у которых читатель есть, но он не наш код и не префикс выше.
#: Ключ — имя, значение — где читается. Список именной: молчаливое исключение вернуло бы ровно ту
#: болезнь, от которой проверка написана.
EXCUSED = {
    "HORIZON_ENV": "подставляется самим compose в OTEL_RESOURCE_ATTRIBUTES",
    "HORIZON_VERSION": "подставляется самим compose в OTEL_RESOURCE_ATTRIBUTES и в тег образа",
    "HORIZON_REVISION": "аргумент сборки образа, не конфигурация процесса",
    "HORIZON_LOG_LEVEL": "читается logback-spring.xml / структурным логгером",
    "HORIZON_LOG_FORMAT": "читается logback-spring.xml / структурным логгером",
    "TZ": "часовой пояс контейнера, читает libc",
    "LANG": "локаль контейнера, читает libc",
    "OMP_NUM_THREADS": "читает OpenMP внутри NumPy/scikit-learn",
    "MKL_NUM_THREADS": "читает Intel MKL внутри NumPy",
    "OPENBLAS_NUM_THREADS": "читает OpenBLAS внутри NumPy",
}


def placeholders(path: Path) -> set[str]:
    """Имена вида `${VAR:умолчание}` в конфигурации Spring."""
    if not path.exists():
        return set()
    return set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)[:}]", path.read_text(encoding="utf-8")))


def aliases(path: Path) -> set[str]:
    """Имена, объявленные псевдонимами в pydantic-настройках Python-сервиса."""
    if not path.exists():
        return set()
    return set(re.findall(r'alias="([A-Z][A-Z0-9_]*)"', path.read_text(encoding="utf-8")))


def readers(service_dir: str) -> set[str]:
    base = ROOT / "services" / service_dir
    names: set[str] = set()
    for config in (base / "src" / "main" / "resources").glob("application*.yml"):
        names |= placeholders(config)
    for logback in (base / "src" / "main" / "resources").glob("logback*.xml"):
        names |= set(re.findall(r'name="([A-Z][A-Z0-9_]*)"', logback.read_text(encoding="utf-8")))
    names |= aliases(base / "src" / "horizon_analytics" / "config.py")
    # Только собственные исходники: `.venv` и `target` полны чужих `getenv`, и без этого
    # ограничения проверка требовала бы документировать PYTEST_CURRENT_TEST.
    sources = list((base / "src" / "main" / "java").rglob("*.java"))
    sources += list((base / "src" / "horizon_analytics").rglob("*.py"))
    for source in sources:
        names |= set(
            re.findall(
                r'getenv\(\s*"([A-Z][A-Z0-9_]*)"',
                source.read_text(encoding="utf-8", errors="ignore"),
            )
        )
    return names


def declared_in_compose(path: Path) -> dict[str, dict[str, str]]:
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    result: dict[str, dict[str, str]] = {}
    for name, spec in (document.get("services") or {}).items():
        if name in SERVICES and isinstance(spec.get("environment"), dict):
            result[name] = spec["environment"]
    return result


def declared_in_helm(path: Path) -> dict[str, dict[str, str]]:
    document = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    result: dict[str, dict[str, str]] = {}
    for name, spec in (document.get("services") or {}).items():
        if name in SERVICES and isinstance(spec, dict) and isinstance(spec.get("env"), dict):
            result[name] = spec["env"]
    return result


def main() -> int:
    known = {container: readers(directory) for container, directory in SERVICES.items()}

    sources: list[tuple[Path, dict[str, dict[str, str]]]] = []
    for compose in sorted((ROOT / "deploy" / "compose").glob("docker-compose*.yml")):
        sources.append((compose, declared_in_compose(compose)))
    for values in sorted((ROOT / "deploy" / "helm" / "horizon").glob("values*.yaml")):
        sources.append((values, declared_in_helm(values)))

    problems: list[str] = []
    checked = 0
    for path, per_service in sources:
        for container, environment in per_service.items():
            for name in environment:
                if name.startswith(FRAMEWORK_PREFIXES) or name in EXCUSED:
                    continue
                checked += 1
                if name not in known[container]:
                    problems.append(
                        f"{path.relative_to(ROOT)}: {container} получает {name}, "
                        f"но сервис такую переменную не читает"
                    )

    # Обратная сторона: настройка, о которой знает только код, не настраивается. Шаблон окружения —
    # то место, где её ищут; отсутствие в нём означает, что о ней узнают чтением исходников.
    template = (ROOT / ".env.example").read_text(encoding="utf-8")
    documented = set(re.findall(r"^#?\s*([A-Z][A-Z0-9_]*)=", template, re.M))
    undocumented = 0
    for container in SERVICES:
        for name in sorted(known[container]):
            if name.startswith(FRAMEWORK_PREFIXES) or name in EXCUSED:
                continue
            undocumented += 1
            if name not in documented:
                problems.append(f".env.example: {container} читает {name}, но шаблон о ней не знает")

    # Третья сторона: переменная, о которой знает только шаблон. Две проверки выше сверяют деплой с
    # кодом и код с шаблоном — и обе молчат, если имя вписано в шаблон и в ранбук, а кодом не
    # читается. Ровно так сюда попало выдуманное `HORIZON_INGESTION_CONTACT_EMAIL` при живом
    # `HORIZON_CONNECTOR_CONTACT_EMAIL`: два файла договорились между собой и оба разошлись с кодом.
    # Разбор: docs/01-analysis/76-a-variable-two-files-agreed-on.md
    #
    # Считается прочитанной и та, что встречается в манифестах деплоя: часть переменных потребляют
    # сторонние образы, и наш код о них знать не обязан.
    in_deploy: set[str] = set()
    for path in (ROOT / "deploy").rglob("*"):
        if path.is_file() and path.suffix in {".yml", ".yaml", ".env", ".conf", ".sh"}:
            in_deploy.update(
                re.findall(r"\b(HORIZON_[A-Z0-9_]+)\b", path.read_text(encoding="utf-8"))
            )
    everywhere = in_deploy.union(*known.values())
    orphans = 0
    for name in sorted(documented):
        if not name.startswith("HORIZON_") or name in EXCUSED:
            continue
        orphans += 1
        if name not in everywhere:
            problems.append(
                f".env.example: {name} задокументирована, но её никто не читает — "
                f"ни код, ни манифесты деплоя"
            )

    if not checked:
        print("не найдено ни одной переменной для сверки — проверка сверяла бы пустоту")
        return 1

    if problems:
        print("\n".join(f"  {line}" for line in sorted(set(problems))))
        print(f"\n  всего расхождений: {len(set(problems))} из {checked} сверенных переменных")
        return 1

    print(
        f"связность переменных окружения: {checked} выставленных читаются, "
        f"{undocumented} читаемых документированы, {orphans} задокументированных читаются"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
