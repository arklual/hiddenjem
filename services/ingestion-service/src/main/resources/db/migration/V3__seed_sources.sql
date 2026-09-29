-- Registry of the sources this deployment knows about.
--
-- Seeded as data rather than hard-coded in the application so that operations can enable, disable
-- or re-tune a source without a release (BR-C2). `requires_api_key = true` sources report themselves
-- unavailable until a key is configured, instead of failing a collection run (BRULE-8).

INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('fixture', 'Эталонный корпус (offline)', 'PREPRINT', true,
     'file:fixtures/corpus/documents.jsonl', 6000, false,
     '{"path":"fixtures/corpus/documents.jsonl","description":"Детерминированный источник для демо и E2E"}'::jsonb,
     0.500),

    ('arxiv', 'arXiv', 'PREPRINT', true,
     'http://export.arxiv.org/api/query', 20, false,
     '{"pageSize":100,"minDelayMillis":3000}'::jsonb,
     0.600),

    ('openalex', 'OpenAlex', 'JOURNAL_ARTICLE', true,
     'https://api.openalex.org/works', 60, false,
     '{"pageSize":200,"politePool":true}'::jsonb,
     0.800),

    ('crossref', 'Crossref', 'JOURNAL_ARTICLE', true,
     'https://api.crossref.org/works', 50, false,
     '{"pageSize":100}'::jsonb,
     0.750),

    ('patentsview', 'PatentsView (USPTO)', 'PATENT', true,
     'https://search.patentsview.org/api/v1/patent/', 45, true,
     '{"pageSize":100,"apiKeyHeader":"X-Api-Key"}'::jsonb,
     0.900),

    ('github', 'GitHub', 'CODE_REPOSITORY', true,
     'https://api.github.com/search/repositories', 30, false,
     '{"pageSize":100,"minStars":25}'::jsonb,
     0.400),

    ('rss', 'Новостные ленты (RSS/Atom)', 'NEWS', true,
     'https://feeds.example', 60, false,
     '{"feeds":["https://techcrunch.com/feed/","https://www.technologyreview.com/feed/"]}'::jsonb,
     0.300)
ON CONFLICT (id) DO NOTHING;

INSERT INTO source_cursors (source_id, cursor_value, last_published_on)
SELECT id, NULL, NULL FROM sources
ON CONFLICT (source_id) DO NOTHING;
