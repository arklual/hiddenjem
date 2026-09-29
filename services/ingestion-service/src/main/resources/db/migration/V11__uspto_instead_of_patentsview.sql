-- PatentsView выведен, патенты США собирает публичный поиск USPTO (разбор 110).
--
-- Ключ PatentsView гражданам РФ не выдают: источник ни разу не работал, документов от него нет.
-- Строка реестра удаляется вместе с курсором; если где-то уже есть его прогоны или документы, строка
-- остаётся (на неё ссылаются), но выключается — удалять историю ради чистоты реестра нельзя.

DELETE FROM source_cursors WHERE source_id = 'patentsview';

UPDATE sources SET enabled = false WHERE id = 'patentsview';

DELETE FROM sources
 WHERE id = 'patentsview'
   AND NOT EXISTS (SELECT 1 FROM ingestion_runs WHERE source_id = 'patentsview')
   AND NOT EXISTS (SELECT 1 FROM documents WHERE source_id = 'patentsview');

INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('uspto', 'USPTO — патенты и заявки США', 'PATENT', true,
     'https://ppubs.uspto.gov', 6, false,
     '{"api":"ppubs","databases":["US-PGPUB","USPAT"],"fields":"ti,ab","pageSize":50,"maxPagesPerPhrase":2,"onePerFamily":true}'::jsonb,
     0.800)
ON CONFLICT (id) DO NOTHING;
