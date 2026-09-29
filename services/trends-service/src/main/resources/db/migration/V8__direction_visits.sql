-- Отметка «этот человек видел это направление тогда-то» (BR-A62, BR-A63).
--
-- Отдельная таблица, а не колонка в saved_domains: направление принадлежит организации, а «я это
-- видел» — конкретному человеку. Колонка в общей строке означала бы, что визит коллеги гасит мои
-- новости, то есть ровно ту ошибку, от которой правило P2 и заведено.
CREATE TABLE direction_visits (
    user_id         uuid        NOT NULL,
    saved_domain_id uuid        NOT NULL REFERENCES saved_domains (id) ON DELETE CASCADE,
    seen_at         timestamptz NOT NULL,
    PRIMARY KEY (user_id, saved_domain_id)
);

-- Радар читает отметки одного человека целиком: первичный ключ ведёт с user_id, поэтому отдельного
-- индекса под чтение не нужно — префикс ключа уже покрывает этот запрос.
--
-- А вот под внешний ключ индекс нужен: PostgreSQL ссылающиеся колонки сам не индексирует, а префикс
-- первичного ключа для saved_domain_id бесполезен. Без него каждое удаление сохранённого
-- направления сканировало бы direction_visits целиком.
CREATE INDEX idx_direction_visits_saved_domain ON direction_visits (saved_domain_id);
