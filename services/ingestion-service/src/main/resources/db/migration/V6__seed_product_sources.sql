-- Продуктовые и отраслевые источники (разбор 101).
--
-- Строка реестра обязательна: документы и курсоры ссылаются на источник внешним ключом. Вес
-- авторитетности — между Hacker News и научными указателями: ТЗ относит профессиональные отраслевые
-- медиа и сайты компаний-разработчиков к доверенным, но свидетельство о рынке, а не о методе.
-- Шесть запросов в минуту — Crawl-delay: 10 из robots.txt площадок.

INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('habr', 'Хабр', 'NEWS', true,
     'https://habr.com/ru/rss/search/', 6, false,
     '{"targetType":"posts","order":"relevance"}'::jsonb,
     0.400),

    ('industry', 'Отраслевые медиа', 'NEWS', true,
     'https://www.edgeir.com/search/{q}/feed/rss2/', 6, false,
     '{"outlets":["edgeir.com","siliconangle.com","plantengineering.com","robohub.org","cyberscoop.com","thefintechtimes.com"]}'::jsonb,
     0.450)
ON CONFLICT (id) DO NOTHING;
