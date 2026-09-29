"""Тихие дыры: артефакт есть, а связи с ним не выражено ничем.

Общее у всех проверок этого файла — способ отказа: ничего не ломается, ничего не краснеет, и вещь
выглядит работающей ровно потому, что выглядеть — единственное, что она делает.

Семейства, а не перечень: перечень пришлось бы дописывать при каждой новой проверке, и он отстал бы
первым — это уже случилось с прежней шапкой, где значилось пять проверок при тринадцати.

* **Обещание без исполнителя** — маркер `TODO` вместо записи в бэклоге; проверка, выключенная на
  месте (`@Disabled`, `skip`, и особенно `.only`, выключающий все остальные в файле).
* **Артефакт без потребителя** — инструмент, которого не зовёт ни гейт, ни Makefile, ни CI; список
  в `domain/resources`, которого не читает загрузчик; схема, которую никто не упоминает;
  спецификация e2e мимо шаблона отбора; служба, чьи метрики никто не собирает.
* **Свод, отставший от предмета** — коннектор без строки в обзоре архитектуры и строка про
  несуществующий коннектор.
* **Близнецы по обе стороны границы** — одно понятие, объявленное дважды и более: классы источника
  (тип, кортеж, веса авторитета, схема события, перечисление ingestion), стадии прогресса, значения
  всех перечислений контрактов разом.

Все выполняются на момент написания: ни одной дыры не найдено. Проверки не догоняют долг, а
закрепляют состояние — потому и собраны вместе: по отдельности каждая выглядит придиркой, вместе
они называют самый частый класс дефектов этого проекта
(`docs/01-analysis/77-a-list-of-what-i-happened-to-see.md`).

Почему у части проверок нет обратной стороны. Пары сверяются в обе стороны только там, где отказ
тихий. Где обратное направление громко чинит себя само — несуществующий инструмент в `verify.sh`
роняет гейт немедленно, загрузчик пропавшего ресурса бросает исключение при первом обращении, —
сверка была бы дублированием того, что и так кричит.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]

#: Где ищем. Сгенерированное и чужое не наше дело.
TREES = ("services", "frontend/src", "tools", "deploy")
SUFFIXES = {".java", ".py", ".ts", ".tsx", ".yml", ".yaml", ".sh", ".sql"}
SKIP = ("/node_modules/", "/target/", "/.venv/", "/dist/", "/__pycache__/")

MARKER = re.compile(r"\b(TODO|FIXME|XXX|HACK)\b")

#: Сама проверка — единственное место, где маркеры стоят законно: она их называет. Исключение
#: узкое и по имени файла, а не по признаку «в тестах»: иначе запрет перестал бы действовать на
#: половину репозитория.
SELF = Path(__file__).name


def sources() -> list[Path]:
    found: list[Path] = []
    for tree in TREES:
        for path in (ROOT / tree).rglob("*"):
            if path.name == SELF:
                continue
            if path.suffix in SUFFIXES and not any(part in str(path) for part in SKIP):
                found.append(path)
    return found


def test_no_marker_stands_in_place_of_a_backlog_entry() -> None:
    files = sources()
    assert len(files) > 100, "проверка смотрит не туда: исходников найдено подозрительно мало"
    offenders: list[str] = []
    for path in files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if MARKER.search(line):
                offenders.append(f"{path.relative_to(ROOT)}:{number}: {line.strip()[:80]}")
    assert (
        not offenders
    ), "обещание оставлено маркером вместо записи в docs/BACKLOG.md:\n  " + "\n  ".join(offenders)


#: Способы выключить проверку, не удаляя её. Отказ тихий вдвойне: набор остаётся зелёным, а
#: выключенное выглядит покрытым — файл на месте, имя на месте, в отчёте прочерк, который никто не
#: читает. Хуже всех `.only`: он не выключает одну проверку, а выключает **все остальные** в файле,
#: и оставленный по недосмотру превращает прогон в запуск одного случая.
#:
#: `test.skip(условие, "причина")` в e2e сюда НЕ входит и входить не должен: это пропуск во время
#: выполнения, когда предусловия нет, с названной причиной — совсем другое действие.
DISABLED = re.compile(r"@Disabled\b|pytest\.mark\.skip\b|\bxit\(|\bxdescribe\(|\.only\(")


def test_no_check_is_switched_off_in_place() -> None:
    files = [path for path in sources() if "test" in str(path).lower() or path.suffix == ".java"]
    assert len(files) > 50, "проверка смотрит не туда: файлов проверок найдено подозрительно мало"
    offenders: list[str] = []
    for path in files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if DISABLED.search(line):
                offenders.append(f"{path.relative_to(ROOT)}:{number}: {line.strip()[:80]}")
    assert (
        not offenders
    ), "проверка выключена на месте вместо удаления или починки:\n  " + "\n  ".join(offenders)


def test_every_tool_is_invoked_from_somewhere() -> None:
    """Инструмент, который никто не зовёт, — мёртвый, и молчит об этом.

    Двадцать скриптов в `tools/` живут не сами по себе: их зовёт гейт, Makefile или CI. Скрипт,
    добавленный и не подключённый, выглядит работающим — он лежит, он читается, у него есть шапка,
    — и не выполняется ни разу. Отказ тихий, значит проверка, а не память.

    Сверяется имя файла, а не путь: в `verify.sh` вызовы идут относительными путями, в Makefile —
    от корня, а в CI — через `make`.
    """
    callers = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (
            ROOT / "tools" / "verify.sh",
            ROOT / "Makefile",
            *sorted((ROOT / ".github" / "workflows").glob("*.yml")),
        )
        if path.is_file()
    )
    tools = sorted(path for suffix in ("*.py", "*.sh") for path in (ROOT / "tools").glob(suffix))
    assert len(tools) > 10, "проверка смотрит не туда: инструментов найдено подозрительно мало"
    orphans = [path.name for path in tools if path.name not in callers]
    assert not orphans, f"инструмент не вызывается ниоткуда: {orphans}"


def test_every_domain_resource_is_loaded_by_code() -> None:
    """Список, который никто не читает, — то же, что мёртвый инструмент, только тише.

    Восемь списков в `domain/resources` несут знание, добытое замерами: стоп-слова, границы имени,
    атрибутивные хвосты, вершины-процессы, устоявшиеся имена, перекрёстный словарь. Файл, добавленный
    и не подключённый к загрузчику, выглядит действующим правилом — его читают, на него ссылаются
    разборы — и не влияет ни на что.

    Проверяется упоминание имени файла в коде: загрузчики берут ресурс по имени
    (`files(...).joinpath("...")`), а не по перечислению каталога.
    """
    package = ROOT / "services" / "analytics-service" / "src" / "horizon_analytics"
    resources = sorted((package / "domain" / "resources").glob("*.txt"))
    assert len(resources) > 5, "проверка смотрит не туда: ресурсов найдено подозрительно мало"
    code = "\n".join(path.read_text(encoding="utf-8") for path in package.rglob("*.py"))
    orphans = [path.name for path in resources if path.name not in code]
    assert not orphans, f"ресурс не загружается ниоткуда: {orphans}"


def test_every_published_schema_is_referenced() -> None:
    """Схема, по которой никто не валидирует, — обещание внешней команде без исполнителя.

    Шестнадцать схем в `contracts/schemas` — это то, на что подписывается потребитель события. Схема,
    добавленная и никем не упомянутая, хуже мёртвого ресурса: её читают снаружи и на неё
    рассчитывают, а внутри ей никто не проверяется. Отказ тихий с обеих сторон.

    Ссылкой считается упоминание имени файла в коде, тестах, инструментах или в AsyncAPI.
    """
    schemas = sorted((ROOT / "contracts" / "schemas").glob("*.json"))
    assert len(schemas) > 10, "проверка смотрит не туда: схем найдено подозрительно мало"
    haystack = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for tree in ("services", "tools", "contracts/asyncapi", "frontend/src")
        for path in (ROOT / tree).rglob("*")
        if path.is_file()
        and path.suffix in {".java", ".py", ".ts", ".tsx", ".yaml", ".yml", ".json"}
        and not any(part in str(path) for part in SKIP)
    )
    orphans = [path.name for path in schemas if path.name not in haystack]
    assert not orphans, f"схема не упомянута нигде: {orphans}"


def test_every_e2e_spec_matches_a_configured_pattern() -> None:
    """Спецификация, не подпадающая под `testMatch`, лежит в каталоге и не запускается.

    Playwright отбирает файлы двумя шаблонами — по одному на проект (`api` и `web`). Файл, названный
    мимо них, не попадает ни в один прогон: он читается, он в репозитории, у него есть проверки — и
    ни одна не выполняется.

    Шаблоны **вычитываются из самого конфига**, а не переписываются сюда: список, копирующий список,
    разошёлся бы вместе с ним и молчал бы ровно тогда, когда нужен.
    """
    config = ROOT / "tests" / "e2e" / "playwright.config.ts"
    assert config.is_file(), f"конфиг e2e не найден: {config}"
    suffixes = re.findall(r"testMatch:\s*/\.\*\\\.(\w+)\\\.spec\\\.ts/", config.read_text("utf-8"))
    assert suffixes, "шаблоны отбора не разобрались — проверка сверяла бы пустоту"
    specs = sorted((ROOT / "tests" / "e2e" / "specs").glob("*.ts"))
    assert specs, "спецификаций e2e не найдено"
    orphans = [
        path.name
        for path in specs
        if not any(path.name.endswith(f".{suffix}.spec.ts") for suffix in suffixes)
    ]
    assert not orphans, f"спецификация не попадает ни в один прогон: {orphans}"


def test_every_horizon_service_is_scraped() -> None:
    """Служба, которую никто не опрашивает, испускает метрики в никуда.

    Так уже случилось однажды: `analytics-worker` поднимал слушателя метрик, а задания на сбор не
    было — панели по нему оставались пустыми, и заметить это можно было только по самой пустоте.
    Тогда починили случай; здесь закрывается класс.

    Compose разбирается парсером, а не шаблоном: первая версия этой проверки искала службы
    регулярным выражением и объявила службой `options` из блока логирования. Разбор такой ошибки —
    в `docs/01-analysis/77-a-list-of-what-i-happened-to-see.md`, и повторять её в проверке, которая
    на этот разбор ссылается, было бы неловко.
    """
    import yaml

    compose = yaml.safe_load(
        (ROOT / "deploy" / "compose" / "docker-compose.yml").read_text("utf-8")
    )
    prometheus = (ROOT / "deploy" / "compose" / "observability" / "prometheus.yml").read_text(
        "utf-8"
    )

    #: Статика метрик не отдаёт: у фронтенда нет ни рантайма на сервере, ни эндпойнта. Исключение
    #: названо по имени, а не признаком «нет порта»: признак прикрыл бы и настоящую пропажу.
    without_metrics = {"frontend"}

    # Наша служба — та, что собирается из наших исходников **или** запускается нашим образом с
    # другой командой. Первая версия проверки смотрела только на `build:` и потому не покрывала
    # `analytics-worker` — ровно ту службу, ради которой писалась: он поднимается образом
    # analytics-service с `command: ["worker"]`. Нашла это проба, а не чтение.
    services = compose.get("services") or {}
    built = {
        (spec.get("image") or "").split(":", 1)[0]
        for spec in services.values()
        if isinstance(spec, dict) and "build" in spec
    }
    ours = sorted(
        name
        for name, spec in services.items()
        if isinstance(spec, dict)
        and name not in without_metrics
        and (
            "build" in spec
            or any(image and (spec.get("image") or "").startswith(image) for image in built)
        )
    )
    assert len(ours) > 3, "проверка смотрит не туда: служб найдено подозрительно мало"
    scraped = set(re.findall(r"targets:\s*\['([a-z][a-z0-9-]+):", prometheus))
    missing = [name for name in ours if name not in scraped]
    assert not missing, f"служба не опрашивается Prometheus: {missing}"






def test_every_source_class_has_an_explicit_authority() -> None:
    """Класс источника без веса получает 0.5 молча — выше, чем у новостей.

    Авторитет берётся как `DEFAULT_SOURCE_AUTHORITY.get(document.source_class, 0.5)`. Запасное
    значение здесь опаснее исключения: новый класс — а он понадобится первым же, если продукт
    возьмёт соцсети (`docs/01-analysis/80-social-networks-as-sources.md`) — получит 0.5 и окажется
    авторитетнее `NEWS` с его 0.30. Никто об этом не узнает: балл посчитается, отчёт соберётся.

    Обе стороны тихие: вес без класса — мёртвая строка, которую переживёт удаление своего класса.

    Объявлений классов **два** — тип `SourceClass` и кортеж `SOURCE_CLASSES`, — и их согласие тоже
    не проверялось ничем. Нашлось пробой: подмена добавила класс в тип, а проверка промолчала,
    потому что смотрела только в кортеж. Сверяются все три множества.
    """
    from typing import get_args

    from horizon_analytics.domain.models import SOURCE_CLASSES, SourceClass
    from horizon_analytics.domain.scoring.profile import DEFAULT_SOURCE_AUTHORITY

    declared = set(get_args(SourceClass))
    listed = set(SOURCE_CLASSES)
    weights = set(DEFAULT_SOURCE_AUTHORITY)
    assert len(declared) > 3, "проверка смотрит не туда: классов найдено подозрительно мало"
    assert declared == listed, f"тип и кортеж классов разошлись: {sorted(declared ^ listed)}"
    assert not declared - weights, f"класс без явного веса авторитета: {sorted(declared - weights)}"
    assert not weights - declared, f"вес для несуществующего класса: {sorted(weights - declared)}"


def test_the_contract_and_the_type_agree_on_source_classes() -> None:
    """Класс, разрешённый схемой и незнакомый коду, получает 0.5 молча.

    `sourceClass` объявлен дважды: перечислением в `document-ingested.event.json` — это обещание
    поставщику события — и типом `SourceClass` в коде. Схема шире типа означает документ, который
    пройдёт проверку контракта и получит приор для незнакомого класса
    (`profile.prior_authority`), то есть 0.5 — выше, чем у новостей. Тип шире схемы означает
    ветку кода, до которой не дойдёт ни один настоящий документ.

    Оба отказа тихие, поэтому сверяются оба направления. Перечисление берётся разбором схемы, а
    не переписыванием в проверку: копия разошлась бы вместе с оригиналом.
    """
    import json
    from typing import get_args

    from horizon_analytics.domain.models import SourceClass

    schema = json.loads(
        (ROOT / "contracts" / "schemas" / "document-ingested.event.json").read_text("utf-8")
    )

    def enums(node: object) -> list[list[str]]:
        found: list[list[str]] = []
        if isinstance(node, dict):
            if isinstance(node.get("enum"), list):
                found.append([str(value) for value in node["enum"]])
            for value in node.values():
                found.extend(enums(value))
        elif isinstance(node, list):
            for value in node:
                found.extend(enums(value))
        return found

    declared = set(get_args(SourceClass))
    matching = [set(e) for e in enums(schema) if set(e) & declared]
    assert len(matching) == 1, f"перечисление классов в схеме не найдено однозначно: {matching}"
    assert matching[0] == declared, (
        f"схема и тип разошлись: только в схеме {sorted(matching[0] - declared)}, "
        f"только в типе {sorted(declared - matching[0])}"
    )

    # Четвёртое объявление — перечисление на JVM-стороне, где документы и рождаются. Схема стоит
    # между ними: разойтись с ней может любая сторона, и обе тихо. Сверяется тем же множеством, а
    # не с типом напрямую — иначе проверка утверждала бы согласие двух, ничего не говоря о третьем.
    java = (
        ROOT
        / "services"
        / "ingestion-service"
        / "src"
        / "main"
        / "java"
        / "dev"
        / "horizon"
        / "ingestion"
        / "domain"
        / "document"
        / "SourceClass.java"
    )
    assert java.is_file(), f"перечисление на JVM-стороне не найдено: {java}"
    produced = set(re.findall(r"^\s{4}([A-Z][A-Z_]+)[,;]", java.read_text("utf-8"), re.M))
    assert produced == declared, (
        f"перечисление ingestion и схема разошлись: только в ingestion "
        f"{sorted(produced - declared)}, только в схеме {sorted(declared - produced)}"
    )


def test_the_contract_and_the_type_agree_on_progress_stages() -> None:
    """Стадия, известная схеме и незнакомая коду, теряет прогресс на глазах у аналитика.

    Стадии объявлены дважды: перечислением в `analysis-progressed.event.json` и типом
    `ProgressStage`. Схема шире типа — воркер не сможет отчитаться о стадии, разрешённой контрактом;
    тип шире схемы — отчёт о стадии не пройдёт проверку события и потеряется по дороге. Второе
    страшнее: аналитик видит замерший индикатор, а в логах ошибка сериализации, а не анализа.

    Проверка заведена вслед за такой же для классов источника: перечислений в этом продукте
    несколько, и каждое объявлено дважды по обе стороны контракта.
    """
    import json
    from typing import get_args

    from horizon_analytics.domain.ports import ProgressStage

    schema = json.loads(
        (ROOT / "contracts" / "schemas" / "analysis-progressed.event.json").read_text("utf-8")
    )

    def enums(node: object) -> list[list[str]]:
        found: list[list[str]] = []
        if isinstance(node, dict):
            if isinstance(node.get("enum"), list):
                found.append([str(value) for value in node["enum"]])
            for value in node.values():
                found.extend(enums(value))
        elif isinstance(node, list):
            for value in node:
                found.extend(enums(value))
        return found

    declared = set(get_args(ProgressStage))
    matching = [set(e) for e in enums(schema) if set(e) & declared]
    assert len(matching) == 1, f"перечисление стадий в схеме не найдено однозначно: {matching}"
    assert matching[0] == declared, (
        f"схема и тип разошлись по стадиям: только в схеме {sorted(matching[0] - declared)}, "
        f"только в типе {sorted(declared - matching[0])}"
    )


def test_every_contract_enum_value_is_known_to_someone() -> None:
    """Значение, разрешённое контрактом и незнакомое всем сторонам, — обещание без исполнителя.

    Две проверки выше сверяют по одному перечислению — классы источника и стадии прогресса, — и
    писать такую на каждое значило бы собирать «список того, что попалось на глаза», против чего и
    написан разбор 77. Здесь один проход по всем схемам: 13 перечислений, 61 значение.

    Направление одно и намеренно: «схема разрешает то, чего никто не знает». Обратное — «код знает
    то, чего схема не разрешает» — этим способом не ловится, потому что требует знать, какой тип
    какому перечислению близнец; для двух самых опасных случаев это сделано отдельно и точно.

    Способ грубый — вхождение строки в исходники, — и потому даёт ложное «знают» на значениях,
    совпадающих со случайной строкой. Ложных «не знают» он не даёт, а именно они здесь и опасны.
    """
    import json

    schemas = sorted((ROOT / "contracts" / "schemas").glob("*.json"))
    assert len(schemas) > 10, "проверка смотрит не туда: схем найдено подозрительно мало"

    def enums(node: object) -> list[list[str]]:
        found: list[list[str]] = []
        if isinstance(node, dict):
            if isinstance(node.get("enum"), list):
                # Только строковые: перечисление вида ["A", "B", null] описывает необязательность,
                # а не значение. Первый заход сравнивал строку "None" и объявил её незнакомой —
                # седьмая по счёту ошибка шаблона за эти дни, все записаны в разборе 77.
                found.append([value for value in node["enum"] if isinstance(value, str)])
            for value in node.values():
                found.extend(enums(value))
        elif isinstance(node, list):
            for value in node:
                found.extend(enums(value))
        return found

    known = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for tree in ("services", "frontend/src")
        for path in (ROOT / tree).rglob("*")
        if path.is_file()
        and path.suffix in {".java", ".py", ".ts", ".tsx"}
        and not any(part in str(path) for part in SKIP)
    )
    unknown = [
        f"{path.name}: {value}"
        for path in schemas
        for enum in enums(json.loads(path.read_text(encoding="utf-8")))
        for value in enum
        if f'"{value}"' not in known
    ]
    assert not unknown, f"контракт разрешает значение, которого никто не знает: {unknown}"




