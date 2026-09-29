package dev.horizon.ingestion.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.horizon.ingestion.domain.port.DocumentTexts;

/**
 * Отбор собранного корпуса по фразам запроса (разбор 110).
 *
 * <p>Источники ищут по-своему: OpenAlex и Semantic Scholar — по отдельным словам, HN — по всему
 * тексту обсуждения, ленты изданий — по любому вхождению. Поэтому в корпус анализа попадают
 * документы, где слова запроса стоят порознь и речь о другом, и из них вырастают темы вроде
 * «lateral movement» в отчёте об открытом банкинге. Здесь каждый документ проверяется так же, как
 * страницы веб-корпуса: стоит ли в заголовке или аннотации (оригинал или перевод) хоть одна
 * формулировка — исходная фраза, узкая формулировка расширения или цель запроса — фразой, с
 * точностью до окончаний. Короткая формулировка (до трёх слов) — целиком подряд, длинная — всеми
 * словами, кроме одного, и хотя бы парой соседних подряд.
 *
 * <p><b>Предохранители.</b> Без расширения запроса (модель не ответила) отбора нет: одна русская
 * фраза направления над англоязычным корпусом отбросила бы всё. Если отбор оставил бы меньше
 * {@link #MIN_KEPT_SHARE} корпуса, он тоже не применяется — это признак того, что формулировки и
 * корпус говорят на разных языках, а не того, что корпус пуст. Страницы глубокого исследования и
 * веб-корпуса не проверяются: их уже отобрали по смыслу агент и поиск по фразе.
 */
public class CorpusRelevance {

    private static final Logger log = LoggerFactory.getLogger(CorpusRelevance.class);

    /** Без отбора: для тестов и развёртываний без хранилища текстов. */
    public static final CorpusRelevance NONE = new CorpusRelevance(null);

    static final int MIN_PHRASES = 3;
    static final double MIN_KEPT_SHARE = 0.15;
    static final int STEM = 6;
    static final Set<String> EXEMPT = Set.of("deepresearch", "webcorpus");

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}\\-]*");
    private static final Set<String> STOP = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "into", "is", "it", "its",
            "of", "on", "or", "the", "to", "with", "via", "using", "based",
            "и", "в", "во", "не", "на", "с", "со", "по", "к", "из", "за", "от", "до", "для", "как", "или", "о",
            "об", "при", "у");

    private final DocumentTexts texts;

    public CorpusRelevance(DocumentTexts texts) {
        this.texts = texts;
    }

    /**
     * Документы, где стоит хоть одна формулировка, в исходном порядке; при сработавшем
     * предохранителе — все.
     */
    public Set<UUID> filter(String query, List<String> expansion, List<String> targets, Set<UUID> documentIds) {
        if (texts == null || documentIds.isEmpty()) {
            return documentIds;
        }
        if (expansion.size() < MIN_PHRASES) {
            log.info("Отбор корпуса по фразам «{}» не применён: формулировок расширения {} (нужно {})",
                    query, expansion.size(), MIN_PHRASES);
            return documentIds;
        }
        List<List<String>> phrases = new ArrayList<>();
        for (String phrase : concat(query, expansion, targets)) {
            for (String variant : phrase.split("\"|\\bOR\\b")) {
                List<String> stems = stems(variant);
                if (!stems.isEmpty() && !phrases.contains(stems)) {
                    phrases.add(stems);
                }
            }
        }
        Map<UUID, Boolean> verdict = new LinkedHashMap<>();
        Map<String, int[]> bySource = new LinkedHashMap<>();
        for (DocumentTexts.DocumentText document : texts.texts(documentIds)) {
            boolean keep = EXEMPT.contains(document.sourceId()) || matchesAny(document.text(), phrases);
            verdict.put(document.id(), keep);
            int[] counts = bySource.computeIfAbsent(document.sourceId(), key -> new int[2]);
            counts[keep ? 0 : 1]++;
        }
        Set<UUID> kept = new LinkedHashSet<>();
        for (UUID id : documentIds) {
            // Документ, текста которого хранилище не вернуло, не выбрасывается: не знаем — не судим.
            if (verdict.getOrDefault(id, true)) {
                kept.add(id);
            }
        }
        double share = (double) kept.size() / documentIds.size();
        if (share < MIN_KEPT_SHARE) {
            log.warn("Отбор корпуса по фразам «{}» не применён: остался бы {} из {} документов ({}%)",
                    query, kept.size(), documentIds.size(), Math.round(share * 100));
            return documentIds;
        }
        StringBuilder detail = new StringBuilder();
        bySource.forEach((source, counts) ->
                detail.append(' ').append(source).append(' ').append(counts[0]).append('/').append(counts[0] + counts[1]));
        log.info("Отбор корпуса по фразам «{}»: оставлено {} из {} документов по {} формулировкам;{}",
                query, kept.size(), documentIds.size(), phrases.size(), detail);
        return kept;
    }

    static boolean matchesAny(String text, List<List<String>> phrases) {
        String haystack = " " + String.join(" ", stems(text == null ? "" : text)) + " ";
        for (List<String> phrase : phrases) {
            if (matches(haystack, phrase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String haystack, List<String> phrase) {
        if (phrase.size() <= 3) {
            return haystack.contains(" " + String.join(" ", phrase) + " ");
        }
        int present = 0;
        for (String stem : new LinkedHashSet<>(phrase)) {
            if (haystack.contains(" " + stem + " ")) {
                present++;
            }
        }
        if (present < new LinkedHashSet<>(phrase).size() - 1) {
            return false;
        }
        for (int i = 0; i + 1 < phrase.size(); i++) {
            if (haystack.contains(" " + phrase.get(i) + " " + phrase.get(i + 1) + " ")) {
                return true;
            }
        }
        return false;
    }

    static List<String> stems(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.length() >= 2 && !STOP.contains(word)) {
                out.add(word.length() > STEM ? word.substring(0, STEM) : word);
            }
        }
        return out;
    }

    private static List<String> concat(String query, List<String> expansion, List<String> targets) {
        List<String> all = new ArrayList<>();
        all.add(query);
        all.addAll(expansion);
        all.addAll(targets);
        return all;
    }
}
