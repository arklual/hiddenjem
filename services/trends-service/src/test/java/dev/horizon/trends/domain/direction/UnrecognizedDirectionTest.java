package dev.horizon.trends.domain.direction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Запись очереди пополнения словаря направлений.
 *
 * <p>Журнал существует потому, что до него словарь пополнялся догадкой: статьи добавлялись по
 * прочтению корпуса, а не по тому, что набирают аналитики, и покрывал он ровно то, о чём подумал
 * автор. Здесь проверяется, что запись отвечает на вопросы куратора, а не просто копит строки.
 */
class UnrecognizedDirectionTest {

    private static final UUID ORGANIZATION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant SEEN = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    @DisplayName("первое наблюдение считается одним случаем")
    void theFirstSightingCountsAsOne() {
        var direction =
                UnrecognizedDirection.firstTime(ORGANIZATION, "цифровой рубль", "Цифровой рубль", List.of(), SEEN);

        assertThat(direction.occurrences()).isEqualTo(1);
        assertThat(direction.firstSeen()).isEqualTo(direction.lastSeen());
    }

    @Test
    @DisplayName("формулировка аналитика сохраняется как он её написал")
    void theAnalystWordingIsKeptAsTyped() {
        // Куратор читает очередь глазами, и «Цифровой рубль» понятнее, чем нормализованная форма.
        // Нормализованная при этом остаётся ключом слияния — иначе «ИИ» и «ии» стали бы двумя
        // задачами вместо одной.
        var direction =
                UnrecognizedDirection.firstTime(ORGANIZATION, "цифровой рубль", "Цифровой  Рубль", List.of(), SEEN);

        assertThat(direction.rawQuery()).isEqualTo("Цифровой  Рубль");
        assertThat(direction.normalizedQuery()).isEqualTo("цифровой рубль");
    }

    @Test
    @DisplayName("без исходной формулировки берётся нормализованная")
    void theNormalizedFormStandsInWhenTheRawOneIsMissing() {
        var direction = UnrecognizedDirection.firstTime(ORGANIZATION, "цифровой рубль", null, List.of(), SEEN);

        assertThat(direction.rawQuery()).isEqualTo("цифровой рубль");
    }

    @Test
    @DisplayName("наличие выхода отличает две разные задачи куратора")
    void havingAWayOutSeparatesTwoDifferentJobs() {
        // Формулировка, от которой до известной статьи один щелчок, стоит куратору минуты.
        // Формулировка, на которую система ни разу не смогла предложить ничего, — это направление,
        // о котором продукт не знает вовсе, и разбирать её надо первой. Без этого различия очередь
        // сортируется только по числу и смешивает две несравнимые задачи.
        var withExit = UnrecognizedDirection.firstTime(
                ORGANIZATION, "квантовый компьютинг", null, List.of("квантовые вычисления"), SEEN);
        var deadEnd = UnrecognizedDirection.firstTime(ORGANIZATION, "цифровой рубль", null, List.of(), SEEN);

        assertThat(withExit.hadAWayOut()).isTrue();
        assertThat(deadEnd.hadAWayOut()).isFalse();
    }

    @Test
    @DisplayName("запись без организации не создаётся")
    void arecordWithoutAnOrganisationIsRefused() {
        // Область — организация: перечень направлений, которые исследует банк, это его повестка.
        // Запись без области однажды попала бы в чужую выдачу.
        assertThatThrownBy(() -> UnrecognizedDirection.firstTime(null, "цифровой рубль", null, List.of(), SEEN))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("последнее наблюдение не может предшествовать первому")
    void thelastSightingCannotPrecedeTheFirst() {
        assertThatThrownBy(() -> new UnrecognizedDirection(
                        ORGANIZATION, "цифровой рубль", null, 2, List.of(), SEEN, SEEN.minusSeconds(1)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("нулевой счётчик — не наблюдение")
    void azeroCountIsNotAnObservation() {
        // Строка со счётчиком ноль означала бы «спрашивали ноль раз», то есть не спрашивали.
        assertThatThrownBy(
                        () -> new UnrecognizedDirection(ORGANIZATION, "цифровой рубль", null, 0, List.of(), SEEN, SEEN))
                .isInstanceOf(RuntimeException.class);
    }
}
