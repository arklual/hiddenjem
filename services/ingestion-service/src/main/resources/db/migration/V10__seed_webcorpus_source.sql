-- Собственный веб-корпус (разбор 110): страницы компаний, пресс-релизы, ленты новостей и отраслевые
-- издания, собранные заранее агентами-сборщиками по корзине вероятных запросов с проверкой
-- robots.txt (включая запреты для ИИ-краулеров). Ищет сервис моделей — BM25 в памяти.
--
-- Вес — как у отраслевых изданий: страницы — первоисточники (пресс-релиз, заметка издания), но отбор
-- их сделан агентами, а не редакцией. Включается HORIZON_SOURCE_WEBCORPUS_ENABLED.

INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                     rate_limit_per_minute, requires_api_key, config, authority_weight)
VALUES
    ('webcorpus', 'Веб-корпус', 'NEWS', true,
     'http://horizon-nlp:8086/webcorpus/search', 30, false,
     '{"index":"bm25","file":"HORIZON_NLP_WEBCORPUS_PATH","robots":"checked at collection"}'::jsonb,
     0.500)
ON CONFLICT (id) DO NOTHING;
