package dev.horizon.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Адрес, по которому нельзя ответить, стоит по умолчанию — и должен себя объявлять.
 *
 * <p>Crossref и OpenAlex пускают представившихся в «вежливый пул» и режут остальных. Найдено не
 * рассуждением: на заглушке {@code horizon@example.org} OpenAlex отвечал 429 часами подряд.
 * Симптом приходит не сборкой, а пустым прогоном сбора через несколько часов.
 */
class ContactEmailPlaceholderTest {

    private static ConnectorsProperties withEmail(String email) {
        return new ConnectorsProperties(null, email, null, null, 0, null, Map.of());
    }

    @Test
    @DisplayName("умолчание — заглушка, и оно опознаётся")
    void theDefaultIsRecognisedAsAPlaceholder() {
        assertThat(ConnectorsProperties.defaults().contactEmailIsPlaceholder()).isTrue();
    }

    @Test
    @DisplayName("пример из values-prod тоже заглушка — его копируют чаще всего")
    void theSampleFromTheChartIsAlsoAPlaceholder() {
        assertThat(withEmail("ops@horizon.example.bank").contactEmailIsPlaceholder())
                .isTrue();
    }

    @Test
    @DisplayName("зарезервированный домен .example — самая частая заглушка в этом репозитории")
    void theReservedExampleTldIsAPlaceholder() {
        assertThat(withEmail("ops@horizon.example").contactEmailIsPlaceholder()).isTrue();
    }

    @Test
    @DisplayName("настоящий адрес заглушкой не считается")
    void aRealAddressIsNotAPlaceholder() {
        assertThat(withEmail("ops@bank.ru").contactEmailIsPlaceholder()).isFalse();
        assertThat(withEmail("examples@bank.ru").contactEmailIsPlaceholder()).isFalse();
    }

    @Test
    @DisplayName("адрес попадает в User-Agent целиком: источник узнаёт нас именно по нему")
    void theAddressReachesTheUserAgent() {
        assertThat(withEmail("ops@bank.ru").fullUserAgent()).contains("mailto:ops@bank.ru");
    }

    @Test
    @DisplayName("контакт в заголовке ровно один")
    void theHeaderCarriesExactlyOneContact() {
        // Манифесты вписывали контакт в сам User-Agent, а сервис приписывает его вторым — заголовок
        // получался с двумя mailto, и при разных значениях они противоречили друг другу. Источник
        // разбирает mailto и на неоднозначность вправе ответить как безымянному вызывающему, то
        // есть ограничением частоты — тем самым симптомом, который мы ловили сутки.
        String header = withEmail("ops@bank.ru").fullUserAgent();
        assertThat(header.split("mailto:", -1)).hasSize(2);
    }
}
