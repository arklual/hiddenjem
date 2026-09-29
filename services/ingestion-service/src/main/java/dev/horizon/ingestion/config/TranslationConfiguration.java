package dev.horizon.ingestion.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.application.CorpusRelevance;
import dev.horizon.ingestion.application.CorpusTranslation;
import dev.horizon.ingestion.domain.port.DocumentTexts;
import dev.horizon.ingestion.domain.port.DocumentTranslations;
import dev.horizon.ingestion.translation.HttpDocumentTranslator;

/** Перевод корпуса включается явно и только с адресом сервиса моделей. */
@Configuration
public class TranslationConfiguration {

    @Bean
    public CorpusTranslation corpusTranslation(
            TranslationProperties properties, DocumentTranslations store, ObjectMapper objectMapper) {
        return properties.active()
                ? new CorpusTranslation(store, new HttpDocumentTranslator(properties, objectMapper), properties)
                : CorpusTranslation.NONE;
    }

    /**
     * Отбор собранного корпуса по фразам запроса (разбор 110). Выключается
     * {@code HORIZON_CORPUS_RELEVANCE_ENABLED=false} — для сравнения отчётов с отбором и без.
     */
    @Bean
    public CorpusRelevance corpusRelevance(
            DocumentTexts texts, @Value("${HORIZON_CORPUS_RELEVANCE_ENABLED:true}") boolean enabled) {
        return enabled ? new CorpusRelevance(texts) : CorpusRelevance.NONE;
    }
}
