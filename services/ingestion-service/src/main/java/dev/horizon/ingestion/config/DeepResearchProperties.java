package dev.horizon.ingestion.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.horizon.ingestion.domain.port.AnalysisMode;

/**
 * Глубокое исследование ({@code horizon.deep-research.*}): как спрашивать сервис моделей.
 *
 * <p>Включение источника — там же, где у всех: {@code horizon.connectors.sources.deepresearch.enabled}
 * ({@code HORIZON_SOURCE_DEEPRESEARCH_ENABLED}), по умолчанию выключен. Здесь — бюджет одного
 * прогона, свой на каждый режим анализа.
 *
 * <p>Поля верхнего уровня — бюджет быстрого режима: весь анализ укладывается в двадцать минут,
 * источники идут параллельно, и шесть минут исследования укладываются внутри сбора. Вложенный
 * {@code quality} — бюджет качественного режима: анализ — сорок минут, агенту отдано двадцать, и он
 * читает втрое больше страниц. Остальные источники тем временем собирают и спрашивают узкими
 * запросами — сбор их не ждёт вслед за исследованием.
 *
 * @param nlpUrl адрес сервиса моделей; пустой — источник недоступен
 * @param timeBudget сколько времени отдано агенту в быстром режиме; сервис моделей держит его сам,
 *     а ожидание ответа здесь на минуту длиннее — на последний ход модели и сборку ответа
 * @param maxIterations потолок ходов модели в быстром режиме
 * @param maxFetches потолок прочитанных страниц в быстром режиме
 * @param minSources минимум прочитанных страниц, о котором агенту говорит инструкция, в быстром режиме
 * @param quality бюджет качественного режима ({@code horizon.deep-research.quality.*})
 */
@ConfigurationProperties(prefix = "horizon.deep-research")
public record DeepResearchProperties(
        String nlpUrl, Duration timeBudget, int maxIterations, int maxFetches, int minSources, Budget quality) {

    /** Быстрый режим: шесть минут, из них агенту — пять с половиной. */
    static final Budget FAST_DEFAULTS = new Budget(Duration.ofMinutes(6), 40, 30, 25);

    /**
     * Качественный режим: двадцать минут агенту и полминуты на последний ход. Вместе с запасом на
     * ответ это ≈ 21,5 минуты сбора — внутри саги в сорок минут остаётся время на анализ.
     */
    static final Budget QUALITY_DEFAULTS = new Budget(Duration.ofMinutes(20).plusSeconds(30), 120, 90, 40);

    public DeepResearchProperties {
        nlpUrl = nlpUrl == null ? "" : nlpUrl.trim();
        Budget fast = new Budget(timeBudget, maxIterations, maxFetches, minSources).orDefaults(FAST_DEFAULTS);
        timeBudget = fast.timeBudget();
        maxIterations = fast.maxIterations();
        maxFetches = fast.maxFetches();
        minSources = fast.minSources();
        quality = quality == null ? QUALITY_DEFAULTS : quality.orDefaults(QUALITY_DEFAULTS);
    }

    public static DeepResearchProperties defaults() {
        return new DeepResearchProperties("", null, 0, 0, 0, null);
    }

    /** Бюджет быстрого режима одной записью. */
    public Budget fast() {
        return new Budget(timeBudget, maxIterations, maxFetches, minSources);
    }

    /** Бюджет, которым исследовать для этого режима анализа. */
    public Budget budget(AnalysisMode mode) {
        return mode == AnalysisMode.QUALITY ? quality : fast();
    }

    /**
     * Бюджет одного прогона.
     *
     * <p>Потолки — те же, что держит сервис моделей: больше он всё равно не даст, а запрос сверх
     * его границ он отвергнет целиком.
     *
     * @param timeBudget сколько времени отдано прогону целиком
     * @param maxIterations потолок ходов модели
     * @param maxFetches потолок прочитанных страниц
     * @param minSources минимум прочитанных страниц, о котором агенту говорит инструкция
     */
    public record Budget(Duration timeBudget, int maxIterations, int maxFetches, int minSources) {

        /** Незаданное — из умолчаний режима, заданное — в пределах потолков сервиса моделей. */
        Budget orDefaults(Budget defaults) {
            return new Budget(
                    timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()
                            ? defaults.timeBudget()
                            : timeBudget,
                    maxIterations <= 0 ? defaults.maxIterations() : Math.min(maxIterations, 120),
                    maxFetches <= 0 ? defaults.maxFetches() : Math.min(maxFetches, 100),
                    minSources <= 0 ? defaults.minSources() : Math.min(minSources, 50));
        }

        /** Сколько секунд отдать агенту: бюджет без полуминуты на последний ход и сборку ответа. */
        public long agentSeconds() {
            return Math.max(30, timeBudget.toSeconds() - 30);
        }

        /** Сколько ждать ответа сервиса моделей: бюджет агента и минута на последний ход. */
        public Duration requestTimeout() {
            return timeBudget.plusSeconds(60);
        }
    }
}
