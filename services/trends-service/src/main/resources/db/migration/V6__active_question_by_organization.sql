-- Поиск «по этому направлению уже идёт анализ» перешёл с пользователя на организацию (BR-A47).
--
-- Корпус собирается один раз и счёт приходит один раз, поэтому коллега, нажавший ту же кнопку
-- секундой раньше, — это не другой вопрос, а тот же, на который уже отвечают. До этого разница
-- между «повезло» и «не повезло» измерялась секундами: опоздавший на минуту получал готовый отчёт,
-- нажавший одновременно оплачивал второй сбор того же корпуса.
DROP INDEX IF EXISTS ix_requests_active_question;

CREATE INDEX ix_requests_active_question
    ON research_requests (organization_id, normalized_query, params_discriminator)
    WHERE status IN ('PENDING','COLLECTING','ANALYZING','ASSEMBLING');
