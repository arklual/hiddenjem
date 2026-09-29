package dev.horizon.ingestion.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import dev.horizon.ingestion.application.SourceSummary;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.source.Source;

class SourceViewTest {

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    @DisplayName("источник без единого прогона приходит без lastRun, а не с null — так велит контракт")
    void aSourceThatNeverRanHasNoLastRunField() throws Exception {
        var source = Source.of("gdelt", "GDELT", SourceClass.values()[0], true, "https://example.org", 60, false,
                Map.of(), 0.5, 0);

        var body = json.readTree(json.writeValueAsString(SourceView.from(new SourceSummary(source, false, 0, null))));

        assertThat(body.has("lastRun")).isFalse();
        assertThat(body.get("id").asText()).isEqualTo("gdelt");
    }
}
