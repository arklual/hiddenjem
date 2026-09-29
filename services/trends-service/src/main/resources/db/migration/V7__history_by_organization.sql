-- История запросов организации (BR-A52).
--
-- ix_requests_user_time (user_id, submitted_at DESC) обслуживал личный список и для
-- организационного не годится: ведущая колонка не та. Без своего индекса выборка «свежие первыми»
-- по организации превращается в сортировку всей её истории на каждом открытии экрана — а это
-- первый экран, на который заходят.
CREATE INDEX ix_requests_organization_time
    ON research_requests (organization_id, submitted_at DESC);

-- Фильтр по статусу применяется к тому же списку. Статус — второй колонкой, а не третьей:
-- равенство идёт до сортировки, иначе индекс упорядочит всю историю организации и только потом
-- отберёт нужные строки.
CREATE INDEX ix_requests_organization_status_time
    ON research_requests (organization_id, status, submitted_at DESC);
