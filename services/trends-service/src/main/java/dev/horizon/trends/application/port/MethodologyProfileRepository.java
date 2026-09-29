package dev.horizon.trends.application.port;

import java.util.Optional;
import java.util.UUID;

import dev.horizon.trends.domain.methodology.MethodologyProfile;

/**
 * Профили весов — только на чтение.
 *
 * <p>Создавать профили из продукта больше нельзя: экран методологии выведен вместе с её движком.
 * Профиль остаётся частью команды анализа (веса индикаторов общего конвейера), поэтому его читают
 * приём запроса (умолчание), сага (профиль запроса) и объяснение отсутствующего термина.
 */
public interface MethodologyProfileRepository {

    Optional<MethodologyProfile> findById(UUID id);

    MethodologyProfile requireDefault();
}
