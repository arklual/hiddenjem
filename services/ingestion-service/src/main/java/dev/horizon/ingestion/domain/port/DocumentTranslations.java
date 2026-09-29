package dev.horizon.ingestion.domain.port;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Хранилище переводов документов на английский (V5). */
public interface DocumentTranslations {

    /**
     * Документы из {@code ids}, которым нужен перевод: заголовок на кириллице или иероглифами, а
     * перевода ещё нет. По заголовку, а не по аннотации: на стенде четверть «ждущих перевода»
     * оказалась английскими статьями и постами с одним русским словом или иероглифом в аннотации.
     */
    List<Pending> untranslated(Collection<UUID> ids);

    void save(UUID id, Translated translation, String sourceLanguage, String model);

    /** Документ, ждущий перевода; язык определён по письменности: иероглифы — zh, кириллица — ru. */
    record Pending(UUID id, String title, String abstractText, String language) {}

    record Translated(UUID id, String title, String abstractText) {}
}
