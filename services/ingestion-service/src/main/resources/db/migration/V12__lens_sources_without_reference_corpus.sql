-- The Lens — научные работы и патенты — вместо эталонного корпуса.
--
-- Эталонный корпус был синтетическим источником для демо и сквозных тестов: в продукте он
-- стоял выключенным и только путал список источников. Вместе с ним уходят его прогоны и документы.

DELETE FROM documents WHERE source_id = 'fixture';
DELETE FROM ingestion_runs WHERE source_id = 'fixture';
DELETE FROM sources WHERE id = 'fixture';

INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('lens', 'The Lens: научные работы', 'JOURNAL_ARTICLE', true,
     'https://api.lens.org/scholarly/search', 10, true,
     '{"pageSize":100}'::jsonb,
     0.800),

    ('lenspatents', 'The Lens: патенты', 'PATENT', true,
     'https://api.lens.org/patent/search', 10, true,
     '{"pageSize":100}'::jsonb,
     0.800)
ON CONFLICT (id) DO NOTHING;
