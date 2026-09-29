package dev.horizon.trends.domain.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.support.Fixtures;

/**
 * {@link AnalysisParameters#cacheDiscriminator()} is the identity used to decide whether two
 * requests are the same question, so its stability is a correctness property: a discriminator that
 * varied with iteration order would silently disable the result cache, and one that collapsed
 * genuinely different parameters would serve the wrong report.
 */
class AnalysisParametersTest {

    @Test
    void sourceClassOrderDoesNotChangeTheDiscriminator() {
        var ascending = new LinkedHashSet<>(List.of(SourceClass.NEWS, SourceClass.PATENT, SourceClass.PREPRINT));
        var descending = new LinkedHashSet<>(List.of(SourceClass.PREPRINT, SourceClass.PATENT, SourceClass.NEWS));

        assertThat(parametersWith(ascending).cacheDiscriminator())
                .isEqualTo(parametersWith(descending).cacheDiscriminator());
    }

    @Test
    void anEmptySetMeansEveryClassAndHasItsOwnMarker() {
        assertThat(parametersWith(Set.of()).cacheDiscriminator()).contains("|*|");
    }

    @Test
    void anEmptySetIsNotTheSameQuestionAsAnExplicitSelection() {
        assertThat(parametersWith(Set.of()).cacheDiscriminator())
                .isNotEqualTo(parametersWith(Set.of(SourceClass.PATENT)).cacheDiscriminator());
    }

    @Test
    void everyParameterParticipatesInTheIdentity() {
        var base = new AnalysisParameters(
                15, 7, Set.of(SourceClass.PATENT), 0.5, false, Fixtures.PROFILE_ID, AnalysisMode.FAST);

        assertThat(base.cacheDiscriminator())
                .isNotEqualTo(new AnalysisParameters(
                                16, 7, Set.of(SourceClass.PATENT), 0.5, false, Fixtures.PROFILE_ID, AnalysisMode.FAST)
                        .cacheDiscriminator())
                .isNotEqualTo(new AnalysisParameters(
                                15, 8, Set.of(SourceClass.PATENT), 0.5, false, Fixtures.PROFILE_ID, AnalysisMode.FAST)
                        .cacheDiscriminator())
                .isNotEqualTo(new AnalysisParameters(
                                15, 7, Set.of(SourceClass.NEWS), 0.5, false, Fixtures.PROFILE_ID, AnalysisMode.FAST)
                        .cacheDiscriminator())
                .isNotEqualTo(new AnalysisParameters(
                                15, 7, Set.of(SourceClass.PATENT), 0.6, false, Fixtures.PROFILE_ID, AnalysisMode.FAST)
                        .cacheDiscriminator())
                .isNotEqualTo(new AnalysisParameters(
                                15, 7, Set.of(SourceClass.PATENT), 0.5, true, Fixtures.PROFILE_ID, AnalysisMode.FAST)
                        .cacheDiscriminator())
                .isNotEqualTo(new AnalysisParameters(
                                15,
                                7,
                                Set.of(SourceClass.PATENT),
                                0.5,
                                false,
                                java.util.UUID.randomUUID(),
                                AnalysisMode.FAST)
                        .cacheDiscriminator())
                .isNotEqualTo(new AnalysisParameters(
                                15,
                                7,
                                Set.of(SourceClass.PATENT),
                                0.5,
                                false,
                                Fixtures.PROFILE_ID,
                                AnalysisMode.QUALITY)
                        .cacheDiscriminator());
    }

    @Test
    void fastAndQualityAreTwoQuestions() {
        // Идентичность параметров — то, по чему проверка свежести решает, годится ли готовый отчёт.
        // Быстрый отчёт, выданный тому, кто согласился ждать качественного, — чужой ответ.
        assertThat(AnalysisParameters.defaults(Fixtures.PROFILE_ID, AnalysisMode.FAST)
                        .cacheDiscriminator())
                .isNotEqualTo(AnalysisParameters.defaults(Fixtures.PROFILE_ID, AnalysisMode.QUALITY)
                        .cacheDiscriminator());
    }

    @Test
    void aKeyWrittenByARetiredEngineNeverMatchesANewQuestion() {
        // Строки до вывода движка методологии хранят ключ с его именем на конце (V20). Новый ключ
        // оканчивается режимом, и совпасть с прежним не может ни в каком режиме: отчёт выведенного
        // движка не выдаётся готовым ответом на вопрос, заданный после этого.
        for (var mode : AnalysisMode.values()) {
            var discriminator =
                    AnalysisParameters.defaults(Fixtures.PROFILE_ID, mode).cacheDiscriminator();
            assertThat(discriminator)
                    .endsWith("|" + mode.wireName())
                    .doesNotEndWith("|methodology")
                    .doesNotEndWith("|signals");
        }
    }

    @Test
    void theDiscriminatorFitsTheDatabaseColumn() {
        var everyClass = Set.of(SourceClass.values());
        var widest = new AnalysisParameters(50, 15, everyClass, 0.999, true, Fixtures.PROFILE_ID, AnalysisMode.QUALITY);

        assertThat(widest.cacheDiscriminator()).hasSizeLessThanOrEqualTo(160);
    }

    @Test
    void theSameValuesAlwaysProduceTheSameString() {
        assertThat(Fixtures.parameters().cacheDiscriminator())
                .isEqualTo(Fixtures.parameters().cacheDiscriminator());
    }

    @Test
    void withProfileOnlyReplacesTheProfile() {
        var profileId = java.util.UUID.randomUUID();
        var updated = Fixtures.parameters().withProfile(profileId);

        assertThat(updated.methodologyProfileId()).isEqualTo(profileId);
        assertThat(updated.topN()).isEqualTo(Fixtures.parameters().topN());
        assertThat(updated.yearsWindow()).isEqualTo(Fixtures.parameters().yearsWindow());
    }

    @Test
    void theSourceClassSetIsDefensivelyCopied() {
        var mutable = new LinkedHashSet<>(Set.of(SourceClass.PATENT));
        var parameters = parametersWith(mutable);
        mutable.add(SourceClass.NEWS);

        assertThat(parameters.sourceClasses()).containsExactly(SourceClass.PATENT);
    }

    private static AnalysisParameters parametersWith(Set<SourceClass> classes) {
        return new AnalysisParameters(15, 7, classes, 0.0, false, Fixtures.PROFILE_ID, AnalysisMode.FAST);
    }
}
