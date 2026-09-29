package dev.horizon.ingestion.connector.ietf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.connector.edgar.EdgarLiveCheck;

/**
 * Живая проверка IETF Datatracker: настоящая сеть, ни одной подмены.
 *
 * <p>В обычном прогоне пропускается. Запуск — {@code ./mvnw -pl ingestion-service test
 * -Dtest=IetfLiveCheck -Dhorizon.live=true -Dsurefire.failIfNoSpecifiedTests=false}. Печать та же,
 * что у {@link EdgarLiveCheck}: число черновиков, отвергнутые, даты, организации, отказ.
 */
class IetfLiveCheck {

    @Test
    @DisplayName("Datatracker отвечает на четыре запроса")
    void collectLive() {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        int answered = EdgarLiveCheck.run(IetfConnector.SOURCE_ID, 30, IetfConnector::new, EdgarLiveCheck.QUERIES);
        assertThat(answered).as("хоть один запрос выполнен").isPositive();
    }
}
