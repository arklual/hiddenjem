package dev.horizon.ingestion.connector.hackernews;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.connector.ResearchSourcesTestSupport;
import dev.horizon.ingestion.connector.hackernews.model.HackerNewsResponse;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.SourceClass;

/**
 * Разбор подлинного ответа Hacker News (запрос «speculative decoding», 2026-09-18).
 *
 * <p>Документ — само обсуждение: его адрес и есть то, по чему правила доверенности узнают
 * агрегатор. Ссылка на статью уходит в текст, но первоисточником обсуждение не становится.
 */
class HackerNewsTest {

    private static HackerNewsResponse response;

    @BeforeAll
    static void parse() throws IOException {
        response = ResearchSourcesTestSupport.read("/connector/hackernews/search-response.json", HackerNewsResponse.class);
    }

    private static Document first() {
        var hit = response.hits().get(0);
        return new HackerNewsNormalizer().normalize(new HackerNewsResponse.Raw(
                hit, "hackernews", hit.objectId(), ResearchSourcesTestSupport.provenance("hackernews")));
    }

    @Test
    @DisplayName("адрес документа — страница обсуждения на news.ycombinator.com")
    void urlIsTheDiscussion() {
        assertThat(first().identifiers().url()).isEqualTo("https://news.ycombinator.com/item?id=48696585");
    }

    @Test
    @DisplayName("ссылка на обсуждаемую статью сохраняется в тексте")
    void linkedArticleIsKept() {
        assertThat(first().abstractText()).contains("github.com/deepseek-ai/DeepSpec");
    }

    @Test
    @DisplayName("класс — новость, язык — английский")
    void classAndLanguage() {
        assertThat(first().sourceClass()).isEqualTo(SourceClass.NEWS);
        assertThat(first().language()).isEqualTo("en");
    }
}
