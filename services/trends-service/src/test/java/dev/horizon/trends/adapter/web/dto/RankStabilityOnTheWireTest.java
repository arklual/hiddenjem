package dev.horizon.trends.adapter.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Неизмеренная устойчивость места на проводе отсутствует, а не равна {@code null}.
 *
 * <p>Контракт объявляет поле необязательным объектом. {@code null} фронтенд не принимает и
 * отказывается показывать весь отчёт — из-за поля, которого движок signals не считает вовсе.
 */
class RankStabilityOnTheWireTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void anUnmeasuredRangeIsOmitted() throws Exception {
        var view = new TrendReportView.EmergenceAssessmentView(0.5, 0.7, false, List.of(), null);

        assertThat(json.readTree(json.writeValueAsString(view)).has("rankStability")).isFalse();
    }

    @Test
    void aMeasuredRangeIsWritten() throws Exception {
        var view = new TrendReportView.EmergenceAssessmentView(
                0.5, 0.7, false, List.of(), new TrendReportView.RankStabilityView(2, 11));

        var node = json.readTree(json.writeValueAsString(view)).get("rankStability");
        assertThat(node.get("best").asInt()).isEqualTo(2);
        assertThat(node.get("worst").asInt()).isEqualTo(11);
    }
}
