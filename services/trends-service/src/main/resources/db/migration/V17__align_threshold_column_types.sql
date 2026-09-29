-- Два порога хранятся как numeric, а в коде они double — сервис из-за этого не стартует.
--
-- `ddl-auto: validate` сверяет тип каждой колонки с типом поля сущности и отказывается поднимать
-- контекст при расхождении: «wrong column type encountered in column [confidence_threshold] ...
-- found [numeric], but expecting [float(53)]». Отказ жёсткий и наступает при первом же запуске
-- против настоящей базы — он и наступил при развёртывании. В тестах его не было видно потому,
-- что проверка схемы срабатывает одинаково везде, а до запуска против живой схемы дело
-- доходило только в контейнерных тестах тех сервисов, где такого расхождения нет.
--
-- Почему колонка идёт к коду, а не код к колонке. Порог — величина вычислительная: домен
-- (`MethodologyProfile`, `ResearchRequest`), контракт API (`number`) и весь счёт эмерджентности
-- работают с `double` от края до края. Перевод двух полей в `BigDecimal` протащил бы точный
-- десятичный тип через слой, который его нигде не использует, ради двух значений, которые всё
-- равно немедленно превращаются в `double` при первом сравнении.
--
-- Что теряется и чем возмещается. `numeric(4,3)` попутно ограничивал величину — больше 9.999 в
-- колонку не вошло бы. Само по себе это не тот предел, который здесь нужен: домен требует
-- `0..1` (`Guards.requireRange`), и до сих пор это требование жило только в Java. Ограничение
-- ставится явным условием — оно строже утраченного и совпадает с тем, что проверяет домен.
--
-- Остальные numeric-колонки (emergence_score, confidence, relevance, burst_weight) не трогаем:
-- они пишутся через JDBC, сущностей не имеют и потому под проверку схемы не попадают. Менять их
-- тип означало бы менять хранение ради стройности, не имея отказа, который это чинит.

ALTER TABLE research_requests
    ALTER COLUMN min_confidence TYPE double precision USING min_confidence::double precision,
    ALTER COLUMN min_confidence SET DEFAULT 0.0,
    ADD CONSTRAINT research_requests_min_confidence_range
        CHECK (min_confidence BETWEEN 0 AND 1);

ALTER TABLE methodology_profiles
    ALTER COLUMN confidence_threshold TYPE double precision USING confidence_threshold::double precision,
    ALTER COLUMN confidence_threshold SET DEFAULT 0.4,
    ADD CONSTRAINT methodology_profiles_confidence_threshold_range
        CHECK (confidence_threshold BETWEEN 0 AND 1);
