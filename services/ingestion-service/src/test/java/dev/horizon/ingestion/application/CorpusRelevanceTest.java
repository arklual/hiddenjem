package dev.horizon.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.port.DocumentTexts;

class CorpusRelevanceTest {

    private static final UUID ON_TOPIC = UUID.randomUUID();
    private static final UUID SCATTERED = UUID.randomUUID();
    private static final UUID RUSSIAN = UUID.randomUUID();
    private static final UUID RESEARCH = UUID.randomUUID();

    private static final Map<UUID, DocumentTexts.DocumentText> TEXTS = Map.of(
            ON_TOPIC, new DocumentTexts.DocumentText(ON_TOPIC, "industry",
                    "Banks expand open banking APIs to third-party providers"),
            SCATTERED, new DocumentTexts.DocumentText(SCATTERED, "openalex",
                    "An open source toolkit for agents. Banking customers were surveyed separately."),
            RUSSIAN, new DocumentTexts.DocumentText(RUSSIAN, "habr",
                    "ВТБ назвал меры защиты данных при внедрении открытого банкинга"),
            RESEARCH, new DocumentTexts.DocumentText(RESEARCH, "deepresearch", "Unrelated wording entirely"));

    private final CorpusRelevance relevance = new CorpusRelevance(ids -> ids.stream().map(TEXTS::get).toList());

    private static Set<UUID> all() {
        return new LinkedHashSet<>(List.of(ON_TOPIC, SCATTERED, RUSSIAN, RESEARCH));
    }

    @Test
    void wordsScatteredOverTheDocumentAreNotThePhrase() {
        Set<UUID> kept = relevance.filter(
                "открытый банкинг",
                List.of("open banking", "open banking API", "account-to-account payments"),
                List.of(),
                all());

        // Фраза «open banking» и русская форма «открытого банкинга» совпадают с точностью до окончаний;
        // «open source … banking» в разных предложениях — нет. Страница исследования не проверяется.
        assertThat(kept).containsExactly(ON_TOPIC, RUSSIAN, RESEARCH);
    }

    @Test
    void withoutExpansionTheCorpusIsLeftAlone() {
        assertThat(relevance.filter("открытый банкинг", List.of(), List.of(), all())).isEqualTo(all());
    }

    @Test
    void aFilterThatWouldEmptyTheCorpusIsNotApplied() {
        Map<UUID, DocumentTexts.DocumentText> many = new java.util.LinkedHashMap<>(TEXTS);
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            many.put(id, new DocumentTexts.DocumentText(id, "openalex", "Unrelated paper number " + i));
        }
        CorpusRelevance wide = new CorpusRelevance(ids -> ids.stream().map(many::get).toList());
        Set<UUID> everything = new LinkedHashSet<>(many.keySet());

        Set<UUID> kept = wide.filter(
                "квантовые сенсоры", List.of("quantum sensing", "NV centers", "atomic clocks"), List.of(), everything);

        // Оставила бы одну страницу исследования из восьми — формулировки говорят не о корпусе.
        assertThat(kept).isEqualTo(everything);
    }

    @Test
    void booleanExpansionIsSplitIntoItsPhrases() {
        assertThat(CorpusRelevance.matchesAny(
                        "Сбер пилотирует токенизацию банковских депозитов",
                        List.of(CorpusRelevance.stems("токенизация банковских депозитов"))))
                .isTrue();
    }
}
