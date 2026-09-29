-- Каким движком считать и каким посчитано.
--
-- Движок — способ получить отчёт целиком, а не настройка внутри одного способа: `methodology` —
-- конвейер методологии, `signals` — скоринг по внешним признакам. Колонки две, потому что вопросов
-- два и они разные: у запроса это «чем считать» (часть идентичности вопроса, см.
-- `params_discriminator`), у отчёта — «чем посчитано», подпись, которую нельзя восстановить из
-- запроса задним числом, когда движков станет больше одного.
--
-- Умолчание `'methodology'` — не удобство, а утверждение о прошлом: всё, что записано до этой
-- миграции, посчитано именно им, и любое другое значение было бы неправдой о готовых отчётах.

ALTER TABLE research_requests
    ADD COLUMN engine varchar(24) NOT NULL DEFAULT 'methodology';

ALTER TABLE trend_reports
    ADD COLUMN engine varchar(24) NOT NULL DEFAULT 'methodology';

-- Идентичность параметров теперь включает движок, и прежние строки её не содержат. Дописать
-- суффикс, а не пересчитать: формула ключа живёт в домене (`AnalysisParameters.cacheDiscriminator`),
-- и вторая её копия на SQL разошлась бы с первой при первой же правке. Дописанное совпадает с тем,
-- что домен посчитает для тех же параметров, — строки остаются находимыми проверкой свежести.
UPDATE research_requests
   SET params_discriminator = params_discriminator || '|methodology'
 WHERE params_discriminator NOT LIKE '%|methodology'
   AND params_discriminator NOT LIKE '%|signals';
