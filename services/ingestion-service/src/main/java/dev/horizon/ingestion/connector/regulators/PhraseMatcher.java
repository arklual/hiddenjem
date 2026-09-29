package dev.horizon.ingestion.connector.regulators;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Совпадает ли запись перечня с формулировкой запроса — поиска у площадок регуляторов нет.
 *
 * <p>Перечень проектов BIS, таблица песочницы FCA и ленты Банка России — это списки, а не поиск:
 * площадка отдаёт всё, и отбор делается здесь. Правило выбрано ради точности, а не охвата — запись
 * регулятора весит в оценке как официальный источник, и ложное совпадение стоит дороже пропуска:
 *
 * <ul>
 *   <li><b>Целые слова, подряд.</b> Слова формулировки должны идти в тексте подряд и каждое — целым
 *       словом. «central bank digital currency» не находится на странице, где «central bank»,
 *       «digital» и «currency» разбросаны по разным абзацам, — а на страницах BIS «central bank»
 *       есть везде. «edge ai» не находится в «knowledge aid».
 *   <li><b>Окончания.</b> Слово длиной от пяти букв сравнивается по основе — без двух последних
 *       букв, но не короче четырёх, — и допускает до четырёх букв окончания: «currency» находит
 *       «currencies», «цифровой рубль» — «цифрового рубля», «stablecoin» — «stablecoins». Короткое
 *       слово — только целиком или с {@code s}/{@code es}: «ai» не должно находить «aid», а «bank»
 *       — «bankruptcy».
 *   <li><b>Британское написание.</b> BIS и FCA пишут «tokenisation», аналитик — «tokenization»;
 *       обе стороны приводятся к {@code -iz-}. Замена симметрична, поэтому безвредна и для слов,
 *       где {@code -is-} — не суффикс.
 *   <li><b>{@code ё} = {@code е}</b> — как пишут в русских заголовках кто как.
 * </ul>
 */
final class PhraseMatcher {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern BRITISH = Pattern.compile("(\\p{L}{2})is(ation|ations|e|ed|es|ing)\\b");
    private static final int STEM_FROM = 5;
    private static final int MIN_STEM = 4;
    private static final int MAX_ENDING = 4;

    private final List<List<String>> phrases;

    PhraseMatcher(List<String> phrases) {
        List<List<String>> tokenized = new ArrayList<>();
        for (String phrase : phrases) {
            List<String> tokens = tokens(phrase);
            if (!tokens.isEmpty()) {
                tokenized.add(tokens);
            }
        }
        this.phrases = List.copyOf(tokenized);
    }

    boolean isEmpty() {
        return phrases.isEmpty();
    }

    /** Первая совпавшая формулировка или {@code null}. */
    String firstMatch(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> tokens = tokens(text);
        for (List<String> phrase : phrases) {
            if (contains(tokens, phrase)) {
                return String.join(" ", phrase);
            }
        }
        return null;
    }

    boolean matches(String text) {
        return firstMatch(text) != null;
    }

    private static boolean contains(List<String> tokens, List<String> phrase) {
        for (int start = 0; start + phrase.size() <= tokens.size(); start++) {
            boolean all = true;
            for (int offset = 0; offset < phrase.size() && all; offset++) {
                all = wordMatches(tokens.get(start + offset), phrase.get(offset));
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    static boolean wordMatches(String token, String word) {
        if (word.length() < STEM_FROM) {
            return token.equals(word) || token.equals(word + "s") || token.equals(word + "es");
        }
        String stem = word.substring(0, Math.max(MIN_STEM, word.length() - 2));
        return token.startsWith(stem) && token.length() <= word.length() + MAX_ENDING;
    }

    static List<String> tokens(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        lower = BRITISH.matcher(lower).replaceAll("$1iz$2");
        List<String> tokens = new ArrayList<>();
        for (String token : TOKEN_SPLIT.split(lower)) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
