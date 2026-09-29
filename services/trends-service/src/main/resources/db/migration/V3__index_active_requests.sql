-- Поиск «по этому направлению у меня уже идёт анализ» (BR-A24).
--
-- Существующий ix_requests_cache_lookup для него не годится: он частичный по status = 'COMPLETED',
-- то есть покрывает ровно противоположную половину жизненного цикла. Без своего индекса запрос
-- уходил бы в ix_requests_user_time и добирал бы остальные условия перебором всей истории
-- пользователя — то есть тем дороже, чем активнее аналитик, и на каждом запуске, включая двадцать
-- подряд в «Обновить все».
--
-- Частичный по живым статусам: незавершённых запросов у пользователя единицы, тогда как история
-- растёт без границы. Набор статусов совпадает с ix_requests_active_deadline и с
-- ResearchStatus.isTerminal().
CREATE INDEX ix_requests_active_question
    ON research_requests (user_id, normalized_query, params_discriminator)
    WHERE status IN ('PENDING','COLLECTING','ANALYZING','ASSEMBLING');
