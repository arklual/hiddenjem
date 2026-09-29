-- =====================================================================================
--  Профиль методологии по умолчанию (BR-B8).
--
--  Веса и параметры дословно совпадают с MethodologyProfile.defaultWeights() /
--  defaultParameters(). Дублирование намеренное и минимальное: сервис обязан быть
--  работоспособен сразу после миграции, до первого запуска приложения, иначе первый же
--  запрос упадёт на requireDefault(). Расхождение ловится тестом
--  DefaultMethodologyProfileSeedTest.
--
--  Идентификатор фиксирован (UUIDv7-совместимая раскладка: версия 7, вариант 10xx),
--  чтобы ссылки на профиль из отчётов были воспроизводимы между средами.
-- =====================================================================================

INSERT INTO methodology_profiles (
    id,
    name,
    version,
    methodology_version,
    aggregator,
    weights,
    parameters,
    confidence_threshold,
    is_default,
    created_by,
    created_at
) VALUES (
    '018f0000-0000-7000-8000-000000000001',
    'Базовый профиль',
    1,
    'em-1.0.0',
    'WEIGHTED_GEOMETRIC',
    '{"novelty":0.20,"growth":0.30,"diffusion":0.15,"weakness":0.15,"coherence":0.10,"impact":0.10}'::jsonb,
    '{"tau":3.0,"growthMax":3.0,"timeWeight":0.05,"orgRef":50,"venueRef":25,"citationsPerYearRef":10,"burstThreshold":2.0,"relevanceThreshold":0.25,"minDocuments":2,"minOrganizations":2}'::jsonb,
    0.400,
    true,
    NULL,
    now()
);
