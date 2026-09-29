package dev.horizon.trends.domain.report;

import java.util.List;

/**
 * Русский слой карточки: перевод рядом с оригиналом, а не вместо него (ADR-0017).
 *
 * <p>ТЗ требует двух вещей одновременно: «вся аналитическая выдача должна быть представлена на
 * русском языке» и «для зарубежного материала … сохранив оригинальное название, ссылку, дату
 * публикации и язык источника». Поэтому русская строка — дополнение, а не замена: аналитик,
 * который пойдёт проверять источник, обязан искать в нём то слово, которое там написано.
 *
 * <p>Модель называется и вид обработки различается — тоже по ТЗ: «при использовании автоматического
 * перевода или генеративного резюме это должно быть отмечено возле источника». Перевод названия и
 * пересказ абстракта имеют разную надёжность, и читатель вправе видеть, что именно перед ним.
 *
 * <p>Пустой экземпляр означает, что сервис моделей был выключен или недоступен. Отчёт при этом
 * полон и верен — он просто по-английски.
 */
public record TrendLocalization(
        String title,
        String titleModel,
        String titleMode,
        String definition,
        String problem,
        String benefit,
        List<String> evidenceTitles,
        String textModel,
        String textMode,
        String statement,
        String statementModel) {

    /** Русского слоя нет вовсе. */
    public static final TrendLocalization NONE =
            new TrendLocalization(null, null, null, null, null, null, List.of(), null, null, null, null);

    public TrendLocalization {
        evidenceTitles = evidenceTitles == null ? List.of() : List.copyOf(evidenceTitles);
    }

    /** Показывать нечего: ни одна модель не ответила. */
    public boolean empty() {
        return title == null
                && definition == null
                && problem == null
                && benefit == null
                && evidenceTitles.isEmpty()
                && statement == null;
    }
}
