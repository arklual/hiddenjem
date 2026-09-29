package dev.horizon.trends.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Visit marks, executed rather than mocked (BR-A62, BR-A63).
 *
 * <p>Радар читает отметки одним запросом и раскладывает их по строкам. Ошибиться тут можно ровно
 * одним способом — границей между людьми: ключ, схлопывающий двух аналитиков в одного, дал бы визиту
 * коллеги гасить чужие новости. Выше этого слоя такое не ловится, там репозиторий подменён.
 *
 * <p><b>Проверяется чтение, не запись.</b> Отметка ставится одним {@code ON CONFLICT ... DO UPDATE}
 * (см. {@link DirectionVisitJpaRepository#upsert}) — единственной формой, не проигрывающей гонке на
 * первом визите. H2 её не исполняет: {@code DO UPDATE} он не понимает даже в режиме совместимости с
 * PostgreSQL. Поэтому строки здесь готовятся обычным INSERT, а сам {@code upsert} остаётся в списке
 * непокрытого этим слоем (правило P4 спецификации среднего слоя) до Testcontainers — там же, где
 * {@code DISTINCT ON}.
 *
 * <p>Схема строится из сущностей ({@code ddl-auto=create-drop}, Flyway выключен), поэтому внешнего
 * ключа на {@code saved_domains} и {@code ON DELETE CASCADE} из V8 в ней нет — каскад не исполняется
 * ни разу, и это утверждение миграции тоже остаётся необоснованным.
 */
class DirectionVisitQueriesTest extends PersistenceTestBase {

    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");
    private static final UUID ME = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COLLEAGUE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DIRECTION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_DIRECTION = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired
    private DirectionVisitJpaRepository visits;

    @Autowired
    private EntityManager entities;

    /** Подготовка строки в обход {@code upsert}: его синтаксис H2 не исполняет, см. javadoc класса. */
    private void mark(UUID userId, UUID savedDomainId, Instant seenAt) {
        entities.createNativeQuery("INSERT INTO direction_visits (user_id, saved_domain_id, seen_at) VALUES (?, ?, ?)")
                .setParameter(1, userId)
                .setParameter(2, savedDomainId)
                .setParameter(3, seenAt)
                .executeUpdate();
        entities.clear();
    }

    private DirectionVisitRepositoryAdapter adapter() {
        return new DirectionVisitRepositoryAdapter(visits);
    }

    @Test
    void aMarkIsFoundAgainByItsOwner() {
        mark(ME, DIRECTION, NOW);

        assertThat(adapter().lastSeenBy(ME)).containsExactly(entry(DIRECTION, NOW));
    }

    @Test
    void aColleaguesVisitDoesNotExtinguishMyNews() {
        // BR-A63 и вся причина, по которой отметка живёт в отдельной таблице, а не колонкой в
        // saved_domains: направление принадлежит организации, а «я это видел» — нет.
        //
        // Обе отметки на одном и том же направлении и в разные моменты — иначе тест не проверял бы
        // того, что заявляет: с одной строкой он остался бы зелёным и при ключе из одного
        // saved_domain_id, потому что схлопывать было бы нечего.
        mark(COLLEAGUE, DIRECTION, NOW);
        mark(ME, DIRECTION, NOW.minusSeconds(3600));

        assertThat(adapter().lastSeenBy(ME)).containsExactly(entry(DIRECTION, NOW.minusSeconds(3600)));
        assertThat(adapter().lastSeenBy(COLLEAGUE)).containsExactly(entry(DIRECTION, NOW));
    }

    @Test
    void marksOfDifferentDirectionsDoNotCollide() {
        mark(ME, DIRECTION, NOW);
        mark(ME, OTHER_DIRECTION, NOW.plusSeconds(60));

        assertThat(adapter().lastSeenBy(ME))
                .containsEntry(DIRECTION, NOW)
                .containsEntry(OTHER_DIRECTION, NOW.plusSeconds(60));
    }

    @Test
    void anAnalystWithoutVisitsHasNoMarks() {
        // P4 опирается на пустоту, а не на нули: первый визит не должен выглядеть как новость, и
        // отличить «не был» от «был давно» можно только по отсутствию записи.
        mark(COLLEAGUE, DIRECTION, NOW);

        assertThat(adapter().lastSeenBy(ME)).isEmpty();
    }
}
