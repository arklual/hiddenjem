package dev.horizon.trends.adapter.web;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.horizon.trends.adapter.web.dto.TrendReportView;
import dev.horizon.trends.domain.feedback.TrendFeedback;

/**
 * Какие темы скрыты из отчёта пометкой смотрящего.
 *
 * <p>Отдельным классом, а не веткой внутри контроллера: правило здесь неочевидное и его надо
 * проверять, а контроллер проверяется только веб-срезом, поднимающим половину приложения.
 *
 * <p>Что именно правило утверждает:
 *
 * <ul>
 *   <li>скрывает только {@code NOISE} — «мы это уже знаем» тема настоящая, и её исчезновение было бы
 *       враньём, а «полезно» тем более;
 *   <li>темы, присутствующие в отчёте, исключаются: их пометка видна на самой карточке, и повторять
 *       её сверху значит говорить, будто она скрыта, когда она на экране;
 *   <li>порядок устойчивый — список не должен переставляться от запроса к запросу;
 *   <li>ключ становится запасным именем, если названия не нашлось: тема, скрытая аналитиком и не
 *       встречавшаяся ни в одном сохранённом отчёте, всё равно перечисляется — иначе число над
 *       списком и сам список разойдутся, и доверия не будет ни тому, ни другому.
 * </ul>
 */
public final class HiddenTopics {

    private HiddenTopics() {}

    /** Ключи тем, которые надо скрыть: только шум и только то, чего в отчёте нет. */
    public static List<String> keysToHide(Collection<TrendFeedback> marks, Set<String> presentInReport) {
        return marks.stream()
                .filter(mark -> mark.verdict() == TrendFeedback.Verdict.NOISE)
                .map(TrendFeedback::trendKey)
                .filter(key -> !presentInReport.contains(key))
                .distinct()
                .sorted()
                .toList();
    }

    /** Строки для показа: название, если оно известно, иначе сам ключ. */
    public static List<TrendReportView.HiddenTopicView> describe(List<String> keys, Map<String, String> titlesByKey) {
        return keys.stream()
                .map(key -> new TrendReportView.HiddenTopicView(key, titlesByKey.getOrDefault(key, key)))
                .toList();
    }
}
