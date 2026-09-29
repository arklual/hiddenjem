-- Журнал направлений, которых перекрёстный словарь не знает.
--
-- Зачем: словарь — ручная работа, и до этой таблицы он пополнялся догадкой. Журнал превращает
-- пополнение в очередь, отсортированную по реальному спросу аналитиков, и даёт число, которого не
-- было ни у кого, — долю запросов, где система не поняла вопроса.
--
-- Ключ по паре «организация + нормализованная формулировка»: строка на каждый запрос превратила бы
-- очередь в протокол. Счётчик и есть то, ради чего таблица заведена.
--
-- Область — организация: словарь направлений одного банка это его исследовательская повестка.
CREATE TABLE unrecognized_directions (
    organization_id  uuid          NOT NULL,
    normalized_query varchar(200)  NOT NULL,
    raw_query        varchar(200)  NOT NULL,
    occurrences      int           NOT NULL DEFAULT 1,
    suggestions      varchar(200)[] NOT NULL DEFAULT '{}',
    first_seen       timestamptz   NOT NULL,
    last_seen        timestamptz   NOT NULL,
    PRIMARY KEY (organization_id, normalized_query)
);

-- Очередь читается «сначала то, что спрашивают чаще» и всегда внутри одной организации.
CREATE INDEX ix_unrecognized_by_demand
    ON unrecognized_directions (organization_id, occurrences DESC, last_seen DESC);

COMMENT ON TABLE unrecognized_directions IS
    'Очередь пополнения перекрёстного словаря направлений, отсортированная по спросу аналитиков';
COMMENT ON COLUMN unrecognized_directions.suggestions IS
    'Что предлагалось в последний раз; пусто — значит выхода аналитику не показали вовсе';
