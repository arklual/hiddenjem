package dev.horizon.ingestion.connector.regulators;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст из фрагмента HTML: без тегов, с раскрытыми сущностями и схлопнутыми пробелами.
 *
 * <p>Страницы BIS и FCA разбираются регулярными выражениями, а не парсером DOM: нужны три-четыре
 * узла с устойчивыми классами разметки, и зависимость ради них не окупается. Если разметка
 * поменяется, разбор не найдёт узлов, и площадка честно откажет (см. {@link RegulatorsConnector}), а
 * не принесёт пустоту.
 */
final class HtmlText {

    private static final Pattern DROPPED = Pattern.compile("(?is)<(script|style|svg)\\b.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    private HtmlText() {}

    /** Текст фрагмента; {@code null}, если в нём нет ни одного непробельного символа. */
    static String of(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = DROPPED.matcher(html).replaceAll(" ");
        // Абзацы и ячейки не должны склеиваться словами: «payments.Project» — одно слово для сопоставления.
        text = TAG.matcher(text).replaceAll(" ");
        text = unescape(text);
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    static String unescape(String text) {
        Matcher numeric = NUMERIC.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (numeric.find()) {
            int code;
            try {
                code = Integer.parseInt(numeric.group(2), numeric.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                code = ' ';
            }
            numeric.appendReplacement(decoded, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        numeric.appendTail(decoded);
        return decoded.toString()
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&ndash;", "–")
                .replace("&mdash;", "—")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&rsquo;", "'")
                .replace("&lsquo;", "'")
                .replace("&ldquo;", "\"")
                .replace("&rdquo;", "\"")
                .replace("&hellip;", "…")
                // Последней: иначе «&amp;nbsp;» превратилось бы в пробел, а не в текст «&nbsp;».
                .replace("&amp;", "&");
    }
}
