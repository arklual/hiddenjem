package dev.horizon.ingestion.domain.port;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Тексты собранных документов — заголовок, аннотация и их английский перевод — для проверки, о
 * запросе ли документ (разбор 110).
 */
public interface DocumentTexts {

    /** Тексты документов с этими идентификаторами; отсутствующие просто не возвращаются. */
    List<DocumentText> texts(Collection<UUID> ids);

    /** Источник и весь проверяемый текст документа одной строкой. */
    record DocumentText(UUID id, String sourceId, String text) {}
}
