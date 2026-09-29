package dev.horizon.trends.adapter.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * What we answer must match what we published a contract for.
 *
 * <p>Написан по следу конкретного дефекта. Поле {@code seenAt} было объявлено в OpenAPI, добавлено в
 * zod-схему клиента, прочитано экраном — и не перенесено в {@code RadarDigestView.from}. Сервер не
 * отдавал его никогда; клиент видел {@code undefined}, признавал его допустимым (поле
 * необязательное) и молча не показывал ни одной пометки. Вся фича была мертва на чтении, и ни один
 * тест не мог этого заметить: юниты проверяли сборку сводки выше DTO, а типы клиента — совпадение с
 * контрактом, а не с ответом.
 *
 * <p>Для событий такая сверка уже есть ({@code PublishedEventContractTest}); для HTTP её не было, и
 * между схемой и записью не стояло ничего. Проверяются имена полей, а не типы: разъезжаются именно
 * имена — тип поменять, не заметив, гораздо труднее, чем забыть строку в конструкторе.
 *
 * <p>Список пар ниже — не весь API. Это те ответы, у которых цена расхождения наибольшая: сводка
 * радара, пересечения и сохранённые направления. Остальные DTO этой сверкой не покрыты, и это
 * сказано здесь, а не оставлено на догадку.
 */
class HttpContractTest {

    private static final Path SPEC = Path.of(System.getProperty("user.dir"))
            .getParent()
            .getParent()
            .resolve("contracts/openapi/horizon-api.yaml");

    private static Stream<Arguments> publishedResponses() {
        return Stream.of(
                Arguments.of("RadarDigest", RadarDigestView.class),
                Arguments.of("DirectionOverlap", DirectionOverlapView.class),
                Arguments.of("SavedDomain", SavedDomainView.class),
                Arguments.of("ReportDelta", ReportDeltaView.class),
                Arguments.of("Quota", QuotaView.class),
                Arguments.of("TopicSearchResult", TopicSearchView.class),
                Arguments.of("FoundTopic", TopicSearchView.TopicView.class),
                Arguments.of("TopicOccurrence", TopicSearchView.OccurrenceView.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedResponses")
    void theAnswerCarriesExactlyTheFieldsTheContractPromises(String schemaName, Class<?> view) throws Exception {
        assertThat(view.isRecord())
                .as("%s должен быть record — сверка опирается на его компоненты", view.getSimpleName())
                .isTrue();

        var promised = propertiesOf(schemaName);
        var answered = Stream.of(view.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(answered)
                .as("%s: поля ответа и поля схемы %s разошлись", view.getSimpleName(), schemaName)
                .containsExactlyInAnyOrderElementsOf(promised);
    }

    @SuppressWarnings("unchecked")
    private static List<String> propertiesOf(String schemaName) throws Exception {
        try (InputStream in = Files.newInputStream(SPEC)) {
            var spec = (Map<String, Object>) new Yaml().load(in);
            var schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
            var schema = (Map<String, Object>) schemas.get(schemaName);
            assertThat(schema).as("схема %s объявлена в контракте", schemaName).isNotNull();
            var properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties)
                    .as("схема %s описывает объект со свойствами", schemaName)
                    .isNotNull();
            return List.copyOf(properties.keySet());
        }
    }
}
