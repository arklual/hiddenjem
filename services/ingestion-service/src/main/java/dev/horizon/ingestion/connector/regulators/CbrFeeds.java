package dev.horizon.ingestion.connector.regulators;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.horizon.ingestion.connector.support.SearchFeeds;
import dev.horizon.ingestion.domain.document.Provenance;

/**
 * Ленты Банка России: русская и английская версии одного и того же события сводятся в одну запись.
 *
 * <p>Банк России публикует ленты парами — «События и комментарии» и «News and Comments»,
 * «Пресс-релизы» и «Press-releases» (перечень — {@code /development/RSS/}). У пары разные
 * идентификаторы и адреса, но одно и то же время публикации до минуты (замер 28.09.2026: 87 из 100
 * английских событий совпали с русскими по времени). Нужны обе: запрос приходит и русской
 * формулировкой («цифровой рубль»), и английскими целями словаря («central bank digital currency»),
 * а у событий нет анонса — только заголовок. Но одно событие в двух переводах — одно свидетельство,
 * а не два.
 *
 * <p>Поэтому запись русской ленты берёт английский заголовок и анонс парной записи себе в текст —
 * по нему и сопоставляется с английской формулировкой, — а английская запись, нашедшая пару, сама
 * документом не становится. Пара ставится, только если время однозначно: две записи одного языка
 * в одну минуту не сводятся ни с чем. Английская запись без пары остаётся английским документом.
 */
final class CbrFeeds {

    private CbrFeeds() {}

    /** Одна прочитанная лента и откуда она. */
    record Fetched(String url, SearchFeeds.Feed feed, Provenance provenance) {

        boolean russian() {
            return SearchFeeds.hasCyrillic(feed.title());
        }
    }

    /** Запись после сведения: основная версия, её перевод (или {@code null}) и лента основной. */
    record Merged(SearchFeeds.Entry entry, SearchFeeds.Entry translation, Fetched origin, boolean russian) {}

    static List<Merged> merge(List<Fetched> feeds) {
        Map<String, List<Holder>> russian = new HashMap<>();
        Map<String, List<Holder>> english = new HashMap<>();
        List<Holder> all = new ArrayList<>();
        for (Fetched fetched : feeds) {
            boolean ru = fetched.russian();
            for (SearchFeeds.Entry entry : fetched.feed().entries()) {
                if (entry.publishedAt() == null || entry.link() == null || entry.title() == null) {
                    continue;
                }
                Holder holder = new Holder(entry, fetched, ru);
                all.add(holder);
                (ru ? russian : english)
                        .computeIfAbsent(keyOf(entry), key -> new ArrayList<>())
                        .add(holder);
            }
        }
        List<Merged> merged = new ArrayList<>();
        for (Holder holder : all) {
            List<Holder> sameMinuteRu = russian.getOrDefault(keyOf(holder.entry), List.of());
            List<Holder> sameMinuteEn = english.getOrDefault(keyOf(holder.entry), List.of());
            boolean unique = sameMinuteRu.size() == 1 && sameMinuteEn.size() == 1;
            if (holder.russian) {
                merged.add(new Merged(holder.entry, unique ? sameMinuteEn.get(0).entry : null, holder.origin, true));
            } else if (!unique) {
                merged.add(new Merged(holder.entry, null, holder.origin, false));
            }
        }
        return merged;
    }

    /**
     * Ключ пары: вид записи и время. Вид — путь адреса без {@code /eng}: событие
     * ({@code /press/event/}) сводится только с событием, пресс-релиз ({@code /press/PR/}) — только
     * с пресс-релизом, даже если оба вышли в одну минуту.
     */
    static String keyOf(SearchFeeds.Entry entry) {
        String path;
        try {
            path = URI.create(entry.link()).getPath();
        } catch (IllegalArgumentException e) {
            path = entry.link();
        }
        if (path == null) {
            path = "";
        }
        if (path.startsWith("/eng/")) {
            path = path.substring(4);
        }
        return path + "|" + entry.publishedAt();
    }

    private record Holder(SearchFeeds.Entry entry, Fetched origin, boolean russian) {}
}
