package dev.horizon.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.config.TranslationProperties;
import dev.horizon.ingestion.domain.port.DocumentTranslations;
import dev.horizon.ingestion.domain.port.DocumentTranslator;

/**
 * Перевод корпуса: пачками, с сохранением, без права сорвать сбор.
 *
 * <p>Без перевода русский документ о федеративном обучении образует тему «федеративное обучение»,
 * китайский — третью, и каждая слабее настоящей.
 */
class CorpusTranslationTest {

    private static final TranslationProperties ON =
            new TranslationProperties(true, "http://nlp", 2, 2, Duration.ofSeconds(30), Duration.ofSeconds(5));

    @Test
    void переводСохраняетсяСЯзыкомИМоделью() {
        var store = new MemoryStore(List.of(
                pending("федеративное обучение", "ru"), pending("联邦学习", "zh"), pending("квантование", "ru")));
        DocumentTranslator translator = batch -> new DocumentTranslator.Result(
                batch.stream()
                        .map(item -> new DocumentTranslations.Translated(item.id(), "EN " + item.title(), null))
                        .toList(),
                "gpt-5.6-luna");

        int translated = new CorpusTranslation(store, translator, ON).translate(store.ids());

        assertThat(translated).isEqualTo(3);
        assertThat(store.saved.values()).allSatisfy(saved -> assertThat(saved).endsWith("|gpt-5.6-luna"));
        assertThat(store.saved.values()).anySatisfy(saved -> assertThat(saved).startsWith("EN 联邦学习|zh"));
    }

    @Test
    void отказМоделиНеСрываетСборИНеТеряетОстальныеПачки() {
        var store = new MemoryStore(List.of(
                pending("а", "ru"), pending("б", "ru"), pending("в", "ru"), pending("г", "ru")));
        DocumentTranslator flaky = batch -> {
            if (batch.get(0).title().equals("а")) {
                throw new IllegalStateException("сервис моделей ответил 503");
            }
            return new DocumentTranslator.Result(
                    batch.stream()
                            .map(item -> new DocumentTranslations.Translated(item.id(), "EN", null))
                            .toList(),
                    "m");
        };

        int translated = new CorpusTranslation(store, flaky, ON).translate(store.ids());

        assertThat(translated).isEqualTo(2);
    }

    @Test
    void выключенныйПереводНичегоНеСпрашивает() {
        var store = new MemoryStore(List.of(pending("федеративное обучение", "ru")));
        DocumentTranslator mustNotBeCalled = batch -> {
            throw new AssertionError("перевод выключен, а модель спросили");
        };

        int translated =
                new CorpusTranslation(store, mustNotBeCalled, TranslationProperties.disabled()).translate(store.ids());

        assertThat(translated).isZero();
    }

    private static DocumentTranslations.Pending pending(String title, String language) {
        return new DocumentTranslations.Pending(UUID.randomUUID(), title, null, language);
    }

    private static final class MemoryStore implements DocumentTranslations {

        private final List<Pending> pending;
        private final Map<UUID, String> saved = new ConcurrentHashMap<>();

        private MemoryStore(List<Pending> pending) {
            this.pending = pending;
        }

        private List<UUID> ids() {
            return new ArrayList<>(pending.stream().map(Pending::id).toList());
        }

        @Override
        public List<Pending> untranslated(Collection<UUID> ids) {
            return pending.stream().filter(item -> ids.contains(item.id())).toList();
        }

        @Override
        public void save(UUID id, Translated translation, String sourceLanguage, String model) {
            saved.put(id, translation.title() + "|" + sourceLanguage + "|" + model);
        }
    }
}
