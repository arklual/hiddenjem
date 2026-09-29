package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.horizon.trends.config.FeatureFlags;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.LifecycleStage;
import dev.horizon.trends.support.Fixtures;

/**
 * Записка называет стадии теми же словами, что экран.
 *
 * <p>Найдено чтением готовой записки целиком, а не проверкой кода: строка формировалась верно и
 * называла стадию по своему словарю. У выгрузки был свой набор слов — «зарождение», «появление»,
 * «ускорение», «зрелость», — и беда не в том, что он другой.
 *
 * <p><b>Наборы перекрещивались.</b> Записка называла {@code EMBRYONIC} «зарождением», а экран
 * называет «Зарождающейся» соседнюю стадию {@code EMERGING}. Читатель, видевший на экране
 * «Зарождающаяся» и встретивший в записке «зарождение», уверенно принимает одно за другое и
 * ошибается на шаг — причём в ту сторону, где тема выглядит более ранней, чем она есть.
 *
 * <p>Источник правды — словарь экрана: он переведён на оба языка и снабжён подсказками с правилами
 * стадий. Сверка исполняема и читает тот самый файл, а не его копию: копия молча разошлась бы с
 * оригиналом в день правки — ровно то, что здесь и произошло.
 */
class StageNamesMatchTheScreenTest {

    private static final Path DICTIONARY =
            Path.of(System.getProperty("user.dir")).getParent().getParent().resolve("frontend/src/lib/i18n/ru.ts");

    private final ExportReportUseCase export =
            new ExportReportUseCase(new FeatureGate(new FeatureFlags(new MockEnvironment())));

    /** Группа `lifecycle` словаря экрана: стадия → её русское имя. */
    private static Map<String, String> screenNames() throws IOException {
        String source = Files.readString(DICTIONARY, StandardCharsets.UTF_8);
        int start = source.indexOf("  lifecycle: {");
        assertThat(start).as("группа lifecycle есть в словаре экрана").isNotNegative();
        String block = source.substring(start, source.indexOf("},", start));

        var names = new LinkedHashMap<String, String>();
        Matcher matcher = Pattern.compile("([A-Z_]+): '([^']+)'").matcher(block);
        while (matcher.find()) {
            names.put(matcher.group(1), matcher.group(2));
        }
        return names;
    }

    @Test
    @DisplayName("словарь экрана прочитан и содержит все четыре стадии")
    void theScreenDictionaryIsReadable() throws IOException {
        // Канарейка разборщика: пустая карта сделала бы проверку ниже вечнозелёной — она сверяла бы
        // записку ни с чем.
        assertThat(screenNames()).hasSize(LifecycleStage.values().length);
    }

    @Test
    @DisplayName("каждая стадия названа в записке так же, как на экране")
    void everyStageIsNamedTheSameWay() throws IOException {
        var screen = screenNames();

        for (LifecycleStage stage : LifecycleStage.values()) {
            String expected = screen.get(stage.name());
            assertThat(expected).as("экран знает стадию %s", stage).isNotNull();
            assertThat(briefingFor(stage)).as("стадия %s в записке", stage).contains("Стадия: " + expected);
        }
    }

    @Test
    @DisplayName("имена стадий не повторяются: одно слово на две стадии — та же ошибка наоборот")
    void noTwoStagesShareAName() throws IOException {
        assertThat(screenNames().values()).doesNotHaveDuplicates();
    }

    private String briefingFor(LifecycleStage stage) {
        var request = Fixtures.assemblingRequest();
        var source = Fixtures.reportWithTrends(request, "em-1.0.0", java.util.List.of("a"), 50.0);
        var trends = source.trends().stream()
                .map(trend -> Fixtures.withLifecycleStage(trend, stage))
                .toList();
        var report = dev.horizon.trends.domain.report.TrendReport.create(
                request.id(),
                Fixtures.requester(),
                source.query(),
                Fixtures.methodology(),
                Fixtures.SNAPSHOT_ID,
                source.coverage(),
                trends,
                trends.size(),
                1,
                null,
                Fixtures.NOW);
        return export.toMarkdown(report);
    }
}
