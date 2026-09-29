"""Уровень доверенности источника и его основание — требование ТЗ к каждому источнику.

ТЗ формулирует два разных требования, и путать их нельзя.

**Первое — показать.** «Для каждого источника система должна отображать наименование, ссылку,
дату публикации, тип источника, язык оригинала и уровень доверенности». Тип и дату отдаёт
коннектор, язык — метаданные документа; уровень доверенности не хранится нигде и обязан быть
выведен. Выводится он здесь.

**Второе — ограничить.** «Социальные сети, личные блоги, агрегаторы, анонимные ресурсы,
рекламные публикации и пресс-релизы могут использоваться как первичный индикатор, но не должны
быть единственным основанием для включения технологии в итоговую выдачу. Такие сведения должны
быть подтверждены независимыми источниками либо сопровождаться отметкой о пониженной
доверенности.» Отсюда два следствия: пресс-релиз отличается от отраслевого медиа, а тема, у
которой нет ни одного источника выше низкого, получает отметку — но **не удаляется**.
Удаление уничтожило бы самые ранние сигналы, ради которых продукт существует: у технологии,
о которой пока написал один отраслевой портал, другого следа и не бывает.

**Почему уровень считается от источника, а не от темы.** Доверенность относится к
утверждению, а не к площадке: сайт компании — хороший первоисточник для «компания выпустила
SDK» и плохой для «наш SDK эффективнее всех». Утверждений мы не разбираем, поэтому уровень
здесь — свойство документа, и его основание пишется словами, чтобы читатель видел правило,
а не ярлык.
"""

from __future__ import annotations

import re
from typing import Final, Literal
from urllib.parse import urlsplit

from horizon_analytics.domain.models import Document

__all__ = [
    "CREDIBILITY_LABELS_RU",
    "Credibility",
    "CredibilityVerdict",
    "assess_credibility",
    "is_substantive",
]

Credibility = Literal["HIGH", "MEDIUM", "LOW"]

#: Подписи для интерфейса и выгрузок. Английских здесь быть не может: вся выдача на русском.
CREDIBILITY_LABELS_RU: Final[dict[Credibility, str]] = {
    "HIGH": "высокая",
    "MEDIUM": "средняя",
    "LOW": "пониженная",
}

#: Домены верхнего уровня и хосты, которые ТЗ прямо относит к доверенным: государственные
#: органы, регуляторы, международные организации, университеты и научные центры.
_OFFICIAL_SUFFIXES: Final[tuple[str, ...]] = (
    ".gov",
    ".gov.uk",
    ".gov.ru",
    ".mil",
    ".edu",
    ".ac.uk",
    ".ac.jp",
    ".ac.cn",
    ".edu.au",
    ".europa.eu",
    ".int",
    ".who.int",
    ".un.org",
    ".nist.gov",
    ".nasa.gov",
    ".cbr.ru",
    # Банк международных расчётов и британский регулятор: официальные организации без домена
    # .gov/.int — их программы (Innovation Hub, регуляторная песочница) собирает коннектор
    # ``regulators``, и без этой строки официальный проект центробанков читался бы как новость.
    ".bis.org",
    ".fca.org.uk",
    ".rospatent.gov.ru",
    ".fips.ru",
    ".minobrnauki.gov.ru",
)

#: Площадки, чьё ремесло — распространение пресс-релизов. Публикация здесь не является
#: независимым свидетельством: десять сайтов, перепечатавших один релиз, — это один источник.
_PRESS_RELEASE_HOSTS: Final[tuple[str, ...]] = (
    "prnewswire.com",
    "businesswire.com",
    "globenewswire.com",
    "newswire.com",
    "einpresswire.com",
    "prweb.com",
    "accesswire.com",
    "openpr.com",
    "pressrelease.com",
)

#: Соцсети, личные блоги и агрегаторы — по ТЗ первичный индикатор, не основание.
_SOCIAL_AND_AGGREGATOR_HOSTS: Final[tuple[str, ...]] = (
    "twitter.com",
    "x.com",
    "facebook.com",
    "linkedin.com",
    "reddit.com",
    "t.me",
    "vk.com",
    "medium.com",
    "substack.com",
    "dev.to",
    "habr.com",
    "dzen.ru",
    "news.ycombinator.com",
    "hackernews.com",
    # Витрина запусков: карточку продукта пишет его автор, это самопрезентация, а не свидетельство.
    "producthunt.com",
)

#: Профессиональные отраслевые медиа, которые собирает коннектор ``industry`` (разбор 101).
#:
#: ТЗ перечисляет их среди доверенных — «профессиональные отраслевые медиа и аналитические
#: отчёты», — отдельно от «социальных сетей, личных блогов и агрегаторов». Список закрытый, а не
#: «любой NEWS»: профессиональность издания здесь проверена руками (редакция, отраслевая
#: специализация, подписанные материалы), и новостная лента произвольного сайта этим не становится.
_PROFESSIONAL_MEDIA_HOSTS: Final[tuple[str, ...]] = (
    "edgeir.com",
    "siliconangle.com",
    "plantengineering.com",
    "robohub.org",
    "cyberscoop.com",
    "thefintechtimes.com",
    # Разбор 110: редакционные отраслевые и деловые издания, на которых стоят рыночные сигналы
    # веб-корпуса. Отобраны по корпусу руками: подписанные материалы и отраслевая редакция;
    # пересказчики пресс-релизов (Pulse 2.0) и ленты релизов сюда намеренно не входят.
    "fintech.global",
    "finextra.com",
    "finovate.com",
    "americanbanker.com",
    "pymnts.com",
    "paymentsdive.com",
    "bankingdive.com",
    "ledgerinsights.com",
    "thepaypers.com",
    "theblock.co",
    "securityweek.com",
    "infosecurity-magazine.com",
    "helpnetsecurity.com",
    "anti-malware.ru",
    "iiot-world.com",
    "therobotreport.com",
    "robotrends.ru",
    "servethehome.com",
    "semiengineering.com",
    "rcrwireless.com",
    "tomshardware.com",
    "3dprint.com",
    "3dprintingindustry.com",
    "agfundernews.com",
    "futurefarming.com",
    "thequantuminsider.com",
    "theaiinsider.tech",
    "electrek.co",
    "newatlas.com",
    "geekwire.com",
    "venturebeat.com",
    "thenextweb.com",
    "fortune.com",
    "calcalistech.com",
    "kr-asia.com",
    "retailtechinnovationhub.com",
    "greenqueen.com.hk",
    "cnews.ru",
    "comnews.ru",
    "vedomosti.ru",
    "frankmedia.ru",
    "3dnews.ru",
    "ixbt.com",
    "36kr.com",
    "eu.36kr.com",
    "leiphone.com",
    "qbitai.com",
    "jiqizhixin.com",
    "tmtpost.com",
    "mpaypass.com.cn",
    "01caijing.com",
    "cls.cn",
    "stcn.com",
)

#: Разделы официального сайта компании, где она сама пишет о своих продуктах и пилотах: ТЗ относит
#: «официальные сайты компаний-разработчиков» к доверенным. Рекламный заголовок по-прежнему
#: делает публикацию релизом.
_OFFICIAL_CHANNEL_PATH: Final = re.compile(
    r"/(?:news|newsroom|press|press-releases?|media-center|blog|blogs|insights|stories|updates)(?:/|$)", re.I
)
_OFFICIAL_CHANNEL_HOST: Final = re.compile(r"^(?:blog|blogs|newsroom|press|developer|developers)\.")

#: Корпоративный блог на Хабре: ``habr.com/ru/companies/<компания>/articles/…``. ТЗ относит к
#: доверенным «официальные сайты компаний-разработчиков»; блог компании на Хабре — её
#: официальный канал, тогда как личная публикация там же — «личный блог».
_HABR_COMPANY_BLOG: Final = re.compile(r"^/(?:ru|en)/companies/[^/]+/")

#: Слова в заголовке, по которым публикация опознаётся как рекламная или релизная.
_PROMO_MARKERS: Final[tuple[str, ...]] = (
    "press release",
    "пресс-релиз",
    "announces",
    "объявляет о",
    "launches new",
    "запускает новый",
    "выходит на рынок",
    "partners with",
    "award-winning",
    "leading provider",
    "лидер рынка",
    "революционн",
    "game-changing",
    "game changing",
)


class CredibilityVerdict:
    """Уровень, его основание словами и признак независимости свидетельства."""

    __slots__ = ("basis", "independent", "level")

    def __init__(self, level: Credibility, basis: str, *, independent: bool) -> None:
        """Сохранить вердикт; структура неизменяемая по соглашению домена."""
        self.level = level
        self.basis = basis
        self.independent = independent

    def __eq__(self, other: object) -> bool:
        """Сравнение по значению — иначе тесты сравнивали бы адреса объектов."""
        if not isinstance(other, CredibilityVerdict):
            return NotImplemented
        return (
            self.level == other.level
            and self.basis == other.basis
            and self.independent == other.independent
        )

    def __hash__(self) -> int:
        """Хэш по значению, согласованный с ``__eq__``."""
        return hash((self.level, self.basis, self.independent))

    def __repr__(self) -> str:
        """Читаемое представление для отчётов об ошибках тестов."""
        return f"CredibilityVerdict({self.level!r}, {self.basis!r}, independent={self.independent})"


def _host(url: str) -> str:
    """Хост ссылки в нижнем регистре, без ``www.``; пустая строка, если ссылки нет."""
    try:
        netloc = urlsplit(url).netloc.lower()
    except ValueError:
        return ""
    if netloc.startswith("www."):
        netloc = netloc[4:]
    return netloc.split(":", 1)[0]


def _path(url: str) -> str:
    """Путь ссылки; пустая строка, если ссылки нет."""
    try:
        return urlsplit(url).path
    except ValueError:
        return ""


def _matches(host: str, hosts: tuple[str, ...]) -> bool:
    """Совпадение по хосту целиком или по его домену — не по подстроке.

    Подстрока здесь опасна в одну сторону: ``medium.com`` встречается внутри
    ``notmedium.company``, и тогда доверенность понижалась бы чужому источнику по совпадению
    букв. Сравнение по границе точки этого не допускает.
    """
    return any(host == candidate or host.endswith("." + candidate) for candidate in hosts)


def _has_promo_marker(title: str) -> bool:
    """Заголовок содержит рекламный или релизный маркер."""
    lowered = title.lower()
    return any(marker in lowered for marker in _PROMO_MARKERS)


def assess_credibility(document: Document) -> CredibilityVerdict:
    """Определить уровень доверенности документа и назвать правило, которое его присвоило.

    Порядок проверок значим: хост-признак сильнее класса источника в обе стороны. Новость на
    сайте регулятора — первоисточник, а не новость; статья в журнале, перепечатанная службой
    релизов, остаётся публикацией, но её независимость под вопросом.
    """
    host = _host(document.url)
    title = document.title or ""

    if _matches(host, _PRESS_RELEASE_HOSTS):
        return CredibilityVerdict("LOW", "служба распространения пресс-релизов", independent=False)
    if _matches(host, _PROFESSIONAL_MEDIA_HOSTS) or (
        host == "habr.com" and _HABR_COMPANY_BLOG.match(_path(document.url))
    ):
        # Рекламный заголовок сильнее площадки: релиз, перепечатанный профильным изданием или
        # выложенный в блог компании, остаётся релизом.
        if _has_promo_marker(title):
            return CredibilityVerdict(
                "LOW",
                "рекламная или релизная публикация по признакам заголовка",
                independent=False,
            )
        if host == "habr.com":
            return CredibilityVerdict(
                "MEDIUM",
                "блог компании-разработчика: первоисточник о её продукте, без рецензирования",
                independent=True,
            )
        return CredibilityVerdict(
            "MEDIUM",
            "профессиональное отраслевое медиа: редакционный материал, ТЗ относит к доверенным",
            independent=True,
        )
    if _matches(host, _SOCIAL_AND_AGGREGATOR_HOSTS):
        return CredibilityVerdict("LOW", "соцсеть, блог-платформа или агрегатор", independent=False)
    # Суффикс «.cbr.ru» обязан узнавать и сам «cbr.ru»: хост приходит без «www.», и до этой
    # проверки Банк России на www.cbr.ru считался рядовой новостью.
    if host.endswith(_OFFICIAL_SUFFIXES) or f".{host}" in _OFFICIAL_SUFFIXES:
        return CredibilityVerdict(
            "HIGH",
            "официальный домен государственного или научного учреждения",
            independent=True,
        )

    # Тип объявляет закрытый перечень, но замыкающая ветка обязана пережить его пополнение:
    # новый класс источника не должен ронять оценку доверенности.
    source_class: str = document.source_class
    if source_class == "STANDARD":
        return CredibilityVerdict("HIGH", "отраслевой или национальный стандарт", independent=True)
    if source_class == "PATENT":
        return CredibilityVerdict(
            "HIGH",
            "запись патентного ведомства с проверяемым номером",
            independent=True,
        )
    if source_class == "JOURNAL_ARTICLE":
        return CredibilityVerdict("HIGH", "публикация в рецензируемом издании", independent=True)
    if source_class == "PREPRINT":
        return CredibilityVerdict(
            "MEDIUM", "препринт: первоисточник, но без рецензирования", independent=True
        )
    if source_class == "ANALYST_REPORT":
        return CredibilityVerdict(
            "MEDIUM",
            "аналитический отчёт: методика расчётов раскрывается не полностью",
            independent=True,
        )
    if source_class == "CODE_REPOSITORY":
        # Владелец-организация опознаётся по разобранной аффилиации: коннектор GitHub ставит её
        # только организациям, личному аккаунту — нет. На этом же различии держится правило
        # достоверности «две независимые организации».
        owner_is_organization = any(
            author.organization_type is not None for author in document.authors
        )
        if owner_is_organization:
            return CredibilityVerdict(
                "MEDIUM",
                "репозиторий организации: код проверяем, заявления автора — нет",
                independent=True,
            )
        return CredibilityVerdict("LOW", "репозиторий личного аккаунта", independent=False)
    if source_class == "NEWS":
        if _has_promo_marker(title):
            return CredibilityVerdict(
                "LOW",
                "рекламная или релизная публикация по признакам заголовка",
                independent=False,
            )
        if _OFFICIAL_CHANNEL_HOST.match(host) or _OFFICIAL_CHANNEL_PATH.search(_path(document.url)):
            return CredibilityVerdict(
                "MEDIUM",
                "официальный канал компании-разработчика: первоисточник о её продукте",
                independent=True,
            )
        return CredibilityVerdict(
            "LOW",
            "отраслевое медиа: первичный индикатор, требует подтверждения",
            independent=True,
        )
    return CredibilityVerdict(
        "MEDIUM", "класс источника не даёт оснований для оценки", independent=True
    )


#: Классы, которые сами по себе — научно-техническая основа темы.
_SUBSTANCE_CLASSES: Final[frozenset[str]] = frozenset(
    {"PREPRINT", "JOURNAL_ARTICLE", "PATENT", "CODE_REPOSITORY", "STANDARD"}
)


def is_substantive(document: Document) -> bool:
    """Может ли документ быть основанием для включения темы, а не только первичным индикатором.

    ТЗ: соцсети, личные блоги, агрегаторы, реклама и пресс-релизы «не должны быть единственным
    основанием для включения технологии в итоговую выдачу». Основание — публикация, патент,
    стандарт, репозиторий **и** то, что ТЗ прямо называет доверенным среди медиа: профессиональные
    отраслевые издания и официальные каналы компаний-разработчиков. До разбора 101 основанием
    считался только класс источника, и тема, о которой писали два профильных издания и блог
    компании, выбывала как «медийная видимость без основы» — то есть ровно рыночные сигналы
    эталона, ради которых эти источники и заведены.
    """
    if document.source_class in _SUBSTANCE_CLASSES:
        return True
    return assess_credibility(document).level != "LOW"
