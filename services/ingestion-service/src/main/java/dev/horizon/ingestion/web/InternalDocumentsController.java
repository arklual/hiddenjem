package dev.horizon.ingestion.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.ingestion.application.SnapshotDocumentsQuery;

/**
 * Внутренняя выдача собранного корпуса аналитическому движку — страницами, целыми документами.
 *
 * <p>Существует потому, что до сих пор собранный корпус до движка **не доходил вовсе**. Соседний
 * {@link InternalCorpusController} отдавал идентификаторы документов снапшота, а способа получить
 * сами документы не было ни здесь, ни в базе: питоновский репозиторий читает
 * {@code analytics.snapshot_documents}, которую никто никогда не заполнял. Движок в таком случае
 * молча переходил на эталонный корпус и анализировал фикстуру — сбор отрабатывал, отчёт
 * собирался, и ни одна строка в нём не относилась к тому, что нашли источники.
 *
 * <p>Форма ответа — ровно {@code contracts/schemas/document-ingested.event.json}: та же, что у
 * событий и у строк эталонного корпуса. Это не совпадение и не экономия: у движка есть один
 * канонический вид документа, и второй вид означал бы второй разбор, который разойдётся с первым.
 *
 * <p>Синхронно и по HTTP — по тому же доводу, что и у соседа: правило архитектуры звучит «сервис не
 * читает чужую базу» (ADR-0006), а не «сервис не зовёт соседа». Запрос неизменного, уже известного
 * набора документов не является ни долгим, ни требующим переживания отказа.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalDocumentsController {

    /** Потолок страницы. Пять тысяч документов — около двадцати мегабайт JSON; больше не нужно. */
    private static final int MAX_PAGE = 2_000;

    private final SnapshotDocumentsQuery documents;

    public InternalDocumentsController(SnapshotDocumentsQuery documents) {
        this.documents = documents;
    }

    /**
     * Документы снапшота целиком, страницами, в том же порядке, который задаёт его хэш.
     *
     * <p>Порядок несущий: он определяет содержимое снапшота, а значит и воспроизводимость анализа.
     */
    @GetMapping("/snapshots/{snapshotId}/documents/full")
    public Page documents(
            @PathVariable UUID snapshotId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "500") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        int safeOffset = Math.max(offset, 0);
        List<Map<String, Object>> page = documents.page(snapshotId, safeOffset, safeLimit);
        return new Page(snapshotId, safeOffset, safeLimit, page);
    }

    public record Page(UUID snapshotId, int offset, int limit, List<Map<String, Object>> documents) {}
}
