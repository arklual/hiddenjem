-- Источники ранней и рыночной стадии (разбор 109).
--
-- Строка реестра обязательна: документы и курсоры ссылаются на источник внешним ключом. Вес
-- авторитетности следует ТЗ: официальные реестры грантов, регуляторы и стандарты — выше, пресс-релизы
-- и витрина запусков — ниже отраслевых медиа (первичный индикатор, не основание). Каждый источник
-- проверен живым запросом и robots.txt 28.09.2026.

INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('sbir', 'SBIR/STTR — гранты малому бизнесу (США)', 'NEWS', true,
     'https://www.sbir.gov/awards', 20, false,
     '{"mode":"html","awardsPerPhrase":10,"crawlDelaySeconds":3}'::jsonb,
     0.650),

    ('nsf', 'NSF — гранты Национального научного фонда США', 'NEWS', true,
     'https://api.nsf.gov/services/v1/awards.json', 30, false,
     '{"rpp":25,"maxPagesPerPhrase":4,"quotedPhrase":true,"dateField":"date"}'::jsonb,
     0.650),

    ('regulators', 'Регуляторы и центробанки', 'NEWS', true,
     'https://www.bis.org/about/innovation-hub/projects', 20, false,
     '{"sites":["bis.org/about/innovation-hub/projects","fca.org.uk/firms/innovation/regulatory-sandbox/accepted-firms","cbr.ru/rss"],"matching":"local-whole-word"}'::jsonb,
     0.600),

    ('edgar', 'SEC EDGAR (раунды и IPO)', 'NEWS', true,
     'https://efts.sec.gov/LATEST/search-index', 6, false,
     '{"forms":"D,S-1","amendments":false,"maxPagesPerTerm":3}'::jsonb,
     0.500),

    ('ietf', 'IETF Datatracker (черновики стандартов)', 'STANDARD', true,
     'https://datatracker.ietf.org/api/v1/', 20, false,
     '{"fields":["title","abstract"],"maxPagesPerField":4}'::jsonb,
     0.700),

    ('globenewswire', 'GlobeNewswire (пресс-релизы)', 'NEWS', true,
     'https://www.globenewswire.com/RssFeed/keyword/{q}', 20, false,
     '{"feed":"keyword-rss","depth":20}'::jsonb,
     0.300),

    ('prnewswire', 'PR Newswire (пресс-релизы)', 'NEWS', true,
     'https://www.prnewswire.com/search/news/', 12, false,
     '{"pageSize":25,"maxPages":3}'::jsonb,
     0.300),

    ('producthunt', 'Product Hunt (запуски продуктов)', 'NEWS', true,
     'https://www.producthunt.com/feed', 20, false,
     '{"feed":"atom","maxCategories":6,"localMatch":true}'::jsonb,
     0.300)
ON CONFLICT (id) DO NOTHING;
