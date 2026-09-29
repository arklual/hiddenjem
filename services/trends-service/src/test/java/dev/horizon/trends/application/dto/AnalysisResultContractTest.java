package dev.horizon.trends.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Граница с движком: то, что публикует Python, обязано читаться этим DTO.
 *
 * <p>Самый опасный шов в системе и до сих пор единственный непроверенный. По обе стороны стоят две
 * независимо написанные записи — {@code build_domain_analyzed} на Python и {@link AnalysisResult}
 * здесь, — и до этого теста ничто не требовало, чтобы они совпадали. Опечатка в имени поля не
 * роняет ничего: Jackson молча оставляет {@code null}, отчёт собирается без оговорки, без кейса или
 * без года первого упоминания и выглядит целым.
 *
 * <p>Со стороны движка производитель уже проверяется против схемы
 * ({@code tests/integration/test_event_contract.py}). Здесь замыкается вторая половина кольца:
 * записанная выдача сверяется со схемой (чтобы образец не устарел молча) и скармливается
 * потребителю (чтобы потребитель не разошёлся с ней).
 *
 * <p>Образцы порождены настоящим конвейером на эталонном корпусе, а не написаны руками. Написанный
 * руками образец доказывал бы, что схема совпадает с тем, что вообразил автор теста, — единственное,
 * в чём никто и не сомневался.
 */
class AnalysisResultContractTest {

    /**
     * Поля схемы, которые этот контекст сознательно не потребляет.
     *
     * <p>Список существует, чтобы отказ от поля был решением, а не случайностью: новое поле схемы
     * без записи здесь и без компонента в DTO роняет проверку.
     */
    private static final Set<String> DELIBERATELY_UNUSED = Set.of();

    private static ObjectMapper mapper;
    private static JsonNode schema;

    @BeforeAll
    static void loadContract() throws IOException {
        // Тот же режим, что у слушателя: неизвестные поля игнорируются ради прямой совместимости,
        // даты читаются модулем java.time.
        mapper = JsonMapper.builder().findAndAddModules().build();
        // Схема читается из репозитория, а не из копии в ресурсах: копия разошлась бы с
        // опубликованным контрактом молча, и проверка стала бы проверкой копии.
        Path published = Path.of("..", "..", "contracts", "schemas", "domain-analyzed.event.json");
        assertThat(Files.isRegularFile(published))
                .as("опубликованная схема " + published.toAbsolutePath().normalize())
                .isTrue();
        schema = mapper.readTree(Files.readString(published, StandardCharsets.UTF_8));
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = AnalysisResultContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("ресурс " + resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static AnalysisResult parse(String resource) throws IOException {
        return mapper.readValue(read(resource), AnalysisResult.class);
    }

    @Test
    @DisplayName("каждое поле схемы имеет компонент в DTO")
    void everySchemaPropertyHasAComponent() {
        // Структурная проверка, а не проверка значений: она ловит опечатку в имени поля в тот же
        // день, когда её сделали, а не тогда, когда аналитик заметит пропавшую оговорку.
        Set<String> components = Arrays.stream(AnalysisResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> missing = new LinkedHashSet<>();
        schema.get("properties").fieldNames().forEachRemaining(name -> {
            if (!components.contains(name) && !DELIBERATELY_UNUSED.contains(name)) {
                missing.add(name);
            }
        });

        assertThat(missing)
                .as("поля схемы без компонента в DTO — они прочитаются как null и ничего не уронят")
                .isEmpty();
    }

    @Test
    @DisplayName("записанный образец несёт всё, что схема объявила обязательным")
    void theRecordedPayloadSatisfiesTheRequiredFields() throws IOException {
        // Держит образец в согласии со схемой. Без этого образец тихо устареет, и проверка
        // потребителя выродится в проверку прошлогодней выдумки.
        JsonNode payload = mapper.readTree(read("/contract/domain-analyzed.json"));

        Set<String> absent = new LinkedHashSet<>();
        schema.get("required").forEach(name -> {
            if (!payload.has(name.asText())) {
                absent.add(name.asText());
            }
        });

        assertThat(absent).isEmpty();
    }

    @Test
    @DisplayName("подлинная выдача движка читается целиком")
    void theRealEngineOutputIsParsed() throws IOException {
        AnalysisResult result = parse("/contract/domain-analyzed.json");

        assertThat(result.researchRequestId()).isEqualTo("7f1c9d5e-0b3a-4c2f-9a1e-2b6d4f8c0a11");
        assertThat(result.methodologyVersion()).isNotBlank();
        assertThat(result.documentsAnalyzed()).isPositive();
        assertThat(result.windowFrom()).isBefore(result.windowTo());
        assertThat(result.trends()).isNotEmpty();
    }

    @Test
    @DisplayName("у каждого тренда есть всё, из чего собирается карточка")
    void everyTrendCarriesWhatTheCardIsBuiltFrom() throws IOException {
        // Ровно те поля, отсутствие которых не роняет сборку отчёта, а обедняет его: тема без
        // мотивации или без доказательств пройдёт в выдачу и будет выглядеть неубедительно, а
        // причина окажется на другом конце системы.
        assertThat(parse("/contract/domain-analyzed.json").trends()).allSatisfy(trend -> {
            assertThat(trend.trendKey()).isNotBlank();
            assertThat(trend.title()).isNotBlank();
            assertThat(trend.motivation()).isNotNull();
            assertThat(trend.indicators()).isNotEmpty();
            assertThat(trend.evidence()).isNotEmpty();
            assertThat(trend.firstMentionYear()).isPositive();
            assertThat(trend.lifecycleStage()).isNotBlank();
        });
    }

    @Test
    @DisplayName("распознанное направление приходит распознанным")
    void arecognisedDirectionArrivesRecognised() throws IOException {
        AnalysisResult result = parse("/contract/domain-analyzed.json");

        assertThat(result.directionRecognizedOrTrue()).isTrue();
        assertThat(result.directionSuggestionsOrEmpty()).isEmpty();
    }

    @Test
    @DisplayName("нераспознанное направление приходит вместе с подсказками")
    void anUnrecognisedDirectionArrivesWithItsSuggestions() throws IOException {
        // Образец записан на запросе-промахе «квантовый компьютинг»: словарь знает «квантовый
        // компьютер», и вся разница между работающим продуктом и бесполезным — в одном слове.
        AnalysisResult result = parse("/contract/domain-analyzed-unrecognized.json");

        assertThat(result.directionRecognizedOrTrue()).isFalse();
        assertThat(result.directionSuggestionsOrEmpty()).contains("квантовые вычисления");
    }
}
