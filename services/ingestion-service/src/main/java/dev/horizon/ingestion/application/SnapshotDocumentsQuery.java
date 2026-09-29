package dev.horizon.ingestion.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чтение документов снапшота в каноническом виде {@code document-ingested}.
 *
 * <p>Порт объявлен приложением, а не адаптером, по той же причине, что и остальные: форма ответа —
 * часть контракта с аналитическим движком, и менять её должно быть так же неудобно, как менять
 * схему события.
 */
public interface SnapshotDocumentsQuery {

    /**
     * Страница документов снапшота в порядке, который задаёт его содержимое.
     *
     * @return список карт вида {@code document-ingested.event.json}; пустой, если снапшота нет
     */
    List<Map<String, Object>> page(UUID snapshotId, int offset, int limit);
}
