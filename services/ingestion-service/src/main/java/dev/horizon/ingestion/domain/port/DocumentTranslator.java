package dev.horizon.ingestion.domain.port;

import java.util.List;

/** Перевод заголовков и аннотаций на английский — порт к сервису моделей. */
public interface DocumentTranslator {

    /**
     * Перевести пачку. Документов, которые модель не перевела, в ответе нет; отказ модели —
     * исключение, и вызывающий оставит пачку без перевода до следующего сбора.
     */
    Result translate(List<DocumentTranslations.Pending> batch);

    record Result(List<DocumentTranslations.Translated> documents, String model) {

        public Result {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
