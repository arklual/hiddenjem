-- Источники, взятые из двух исследований к кейсу (разбор 89).
--
-- Строка реестра обязательна: документы и курсоры ссылаются на источник внешним ключом, и
-- коннектор без строки не смог бы сохранить ни одного документа. Вес авторитетности — по классу:
-- научные указатели рядом с OpenAlex и Crossref, раннее внимание и новости — ниже GitHub.

INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('semanticscholar', 'Semantic Scholar', 'JOURNAL_ARTICLE', true,
     'https://api.semanticscholar.org/graph/v1/paper/search/bulk', 20, false,
     '{"bulk":true}'::jsonb,
     0.750),

    ('europepmc', 'Europe PMC', 'JOURNAL_ARTICLE', true,
     'https://www.ebi.ac.uk/europepmc/webservices/rest/search', 60, false,
     '{"pageSize":200,"resultType":"core"}'::jsonb,
     0.800),

    ('hackernews', 'Hacker News', 'NEWS', true,
     'https://hn.algolia.com/api/v1/search', 60, false,
     '{"pageSize":100,"tags":"story"}'::jsonb,
     0.250),

    ('gdelt', 'GDELT (мировые новости)', 'NEWS', true,
     'https://api.gdeltproject.org/api/v2/doc/doc', 3, false,
     '{"mode":"artlist","timespan":"36months","languages":["all","russian"]}'::jsonb,
     0.300)
ON CONFLICT (id) DO NOTHING;
