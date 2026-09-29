package dev.horizon.ingestion.connector.support;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Цели направления в виде, пригодном для поискового выражения внешнего каталога.
 *
 * <p>Общая часть трёх ошибок, найденных на стенде по одной: GitHub отвергал запрос с кодами
 * классификатора ({@code 422}), OpenAlex соединял мешок слов через И и находил 30 работ вместо
 * пяти миллионов (разбор 88). Во всех случаях каталогу нужно одно и то же — список осмысленных
 * фраз, а уж соединять их через {@code OR}, {@code |} или отдельными запросами решает коннектор.
 */
public final class SearchPhrases {

    /** Коды предметных словарей arXiv: {@code cs.LG}, {@code stat.ML}, {@code cond-mat.mtrl-sci}. */
    private static final Pattern CLASSIFICATION_CODE = Pattern.compile("[a-z-]{2,12}\\.[a-zA-Z-]{2,12}");

    private SearchPhrases() {}

    /**
     * Фразы без кодов классификатора, пустых строк, повторов и кавычек внутри.
     *
     * @param minLength фразы короче отбрасываются: GDELT, например, отвергает запрос со словом
     *     из двух букв, а «ai» в любом каталоге находит всё подряд
     */
    public static List<String> of(List<String> upstreamTerms, int minLength) {
        return upstreamTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(term -> term.replace("\"", "").trim())
                .filter(term -> term.length() >= minLength)
                .filter(term -> !CLASSIFICATION_CODE.matcher(term).matches())
                .distinct()
                .toList();
    }

    /** Фразы в кавычках через разделитель; одна фраза — без кавычек, как её написал аналитик. */
    public static String joined(List<String> phrases, String separator) {
        if (phrases.size() == 1) {
            return phrases.get(0);
        }
        return String.join(separator, phrases.stream().map(phrase -> "\"" + phrase + "\"").toList());
    }
}
