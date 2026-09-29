package dev.horizon.ingestion.connector.newswire;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Фрагмент HTML → простой текст.
 *
 * <p>Библиотеки разбора HTML в модуле нет, и ради двух площадок её не заводим: карточка результата
 * и анонс ленты — несколько тегов и сущностей, а не документ. Зато сущности разбираются полностью:
 * числовые ({@code &#8217;}, {@code &#x2019;}) и те именованные, что встречаются в записанных
 * ответах, — иначе в заголовке остаётся {@code &amp;amp;}, и дубли одного релиза перестают совпадать.
 */
public final class HtmlText {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"),
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", " "),
            Map.entry("ndash", "–"),
            Map.entry("mdash", "—"),
            Map.entry("lsquo", "'"),
            Map.entry("rsquo", "'"),
            Map.entry("ldquo", "\""),
            Map.entry("rdquo", "\""),
            Map.entry("hellip", "…"),
            Map.entry("reg", "®"),
            Map.entry("trade", "™"),
            Map.entry("copy", "©"));

    private HtmlText() {}

    /** Текст без тегов, с раскрытыми сущностями и схлопнутыми пробелами; пустой — {@code null}. */
    public static String plain(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = unescape(TAG.matcher(html).replaceAll(" "));
        String collapsed =
                WHITESPACE.matcher(text.replace(' ', ' ')).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    /** Раскрыть сущности HTML; неизвестные остаются как есть. */
    public static String unescape(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }
        // Дважды: площадки экранируют уже экранированное («&amp;amp;»), и одного прохода мало.
        return unescapeOnce(unescapeOnce(value));
    }

    private static String unescapeOnce(String value) {
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement;
            if (name.charAt(0) == '#') {
                boolean hex = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X');
                int code = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
                replacement = Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : matcher.group();
            } else {
                replacement = NAMED.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), matcher.group());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
