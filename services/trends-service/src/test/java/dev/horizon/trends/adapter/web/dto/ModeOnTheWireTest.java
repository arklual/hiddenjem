package dev.horizon.trends.adapter.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.support.Fixtures;

/**
 * Режим анализа на границе HTTP: поле обязано её пережить.
 *
 * <p>Граница — то самое место, где такие поля теряются. У слоя HTTP своя модель, и всё, чего в ней
 * нет, он отбрасывает молча: запрос обрабатывается, отчёт собирается, и разница видна только в
 * сроке и полноте, которые никто не сверяет с просьбой.
 */
class ModeOnTheWireTest {

    /** Как у Spring Boot: незнакомые поля тела не отвергаются. */
    private final ObjectMapper json =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theRequestBodyCarriesTheModeIntoTheDomain() throws Exception {
        var dto = json.readValue(
                """
                {"topN": 20, "mode": "quality"}
                """, AnalysisParametersDto.class);

        assertThat(dto.toDomain().mode()).isEqualTo(AnalysisMode.QUALITY);
    }

    @Test
    void anAbsentModeIsFast() throws Exception {
        var dto = json.readValue("{\"topN\": 20}", AnalysisParametersDto.class);

        assertThat(dto.toDomain().mode()).isEqualTo(AnalysisMode.FAST);
    }

    @Test
    void anAbsentParametersObjectIsFastToo() {
        assertThat(AnalysisParametersDto.toDomain(null).mode()).isEqualTo(AnalysisMode.FAST);
    }

    @Test
    void anUnknownModeIsRefusedAsAValidationError() throws Exception {
        // `IllegalArgumentException` платформа отдаёт как 400 с перечнем режимов (ProblemDetailAdvice).
        // Тихий откат к умолчанию вернул бы 200 и быстрый отчёт тому, кто согласился ждать качественного.
        var dto = json.readValue("{\"mode\": \"slow\"}", AnalysisParametersDto.class);

        assertThatThrownBy(dto::toDomain)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slow")
                .hasMessageContaining("fast, quality");
    }

    @Test
    void aClientStillSendingEngineAndProfileGetsTheSameAnswer() throws Exception {
        // Старый клиент присылает `engine: methodology` и профиль, выбранный на снятом экране. Ни то,
        // ни другое не отвергается и ни на что не влияет: движок один, профиль — всегда умолчание.
        var dto = json.readValue(
                """
                {"engine": "methodology", "methodologyProfileId": "33333333-3333-4333-8333-333333333333"}
                """,
                AnalysisParametersDto.class);

        assertThat(dto.toDomain()).isEqualTo(AnalysisParameters.defaults(null));
    }

    @Test
    void theModeGoesBackOutInTheView() {
        var parameters = AnalysisParameters.defaults(Fixtures.PROFILE_ID, AnalysisMode.QUALITY);

        var view = AnalysisParametersDto.from(parameters);

        assertThat(view.mode()).isEqualTo("quality");
        assertThat(view.toDomain().mode()).isEqualTo(AnalysisMode.QUALITY);
    }
}
