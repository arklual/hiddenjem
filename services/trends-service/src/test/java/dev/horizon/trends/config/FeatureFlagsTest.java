package dev.horizon.trends.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;

import dev.horizon.platform.common.error.HorizonException;

/**
 * The registry and the way it is read.
 *
 * <p>A registry nothing walks becomes a list of strings, some of which switch nothing off — and that
 * is discovered during the demonstration where someone needs a feature hidden. Every case here is
 * parameterised over the whole enum for that reason.
 */
class FeatureFlagsTest {

    private static FeatureFlags with(String property, String value) {
        var environment = new MockEnvironment();
        if (property != null) {
            environment.setProperty(property, value);
        }
        return new FeatureFlags(environment);
    }

    @ParameterizedTest
    @EnumSource(FeatureFlag.class)
    void everyFlagIsOnWhenNothingIsConfigured(FeatureFlag flag) {
        // The registry adds a lever; it does not move it. Introducing it must not change what the
        // product does on the day it ships.
        assertThat(with(null, null).isEnabled(flag)).isTrue();
        assertThat(flag.enabledByDefault()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(FeatureFlag.class)
    void everyFlagCanActuallyBeSwitchedOff(FeatureFlag flag) {
        assertThat(with(flag.property(), "false").isEnabled(flag)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(FeatureFlag.class)
    void switchingOneFlagOffLeavesTheOthersAlone(FeatureFlag flag) {
        var flags = with(flag.property(), "false");

        for (FeatureFlag other : FeatureFlag.values()) {
            if (other != flag) {
                assertThat(flags.isEnabled(other))
                        .as("%s не должен зависеть от %s", other.key(), flag.key())
                        .isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(FeatureFlag.class)
    void aSwitchedOffFeatureIsRefusedAsNotFound(FeatureFlag flag) {
        // Not an empty body: "there is no data" is a statement about the subject under study, and an
        // analyst would act on it. "This build has no such feature" is a statement about the
        // deployment.
        var gate = new FeatureGate(with(flag.property(), "false"));

        assertThatThrownBy(() -> gate.require(flag)).isInstanceOf(HorizonException.class);
    }

    @Test
    void keysAreUniqueAndUrlSafe() {
        // The key is the identity in the API, in configuration and on the admin screen at once, so a
        // duplicate would silently make two features share a switch.
        var seen = new HashSet<String>();
        for (FeatureFlag flag : FeatureFlag.values()) {
            assertThat(seen.add(flag.key())).as("дубликат ключа %s", flag.key()).isTrue();
            assertThat(flag.key()).matches("[a-z0-9-]+");
        }
    }

    @Test
    void everyFlagExplainsWhatDisappears() {
        // The decision to switch something off is made on the effect, not on the name.
        for (FeatureFlag flag : FeatureFlag.values()) {
            assertThat(flag.title()).isNotBlank();
            assertThat(flag.description()).isNotBlank();
        }
    }

    @Test
    void anUnparseableValueLeavesTheFeatureOnRatherThanGuessing() {
        // A typo in a deployment manifest must not remove a feature: switching something off because
        // of "ture" is a worse outcome than ignoring the line.
        assertThat(with(FeatureFlag.RADAR.property(), "ture").isEnabled(FeatureFlag.RADAR))
                .isTrue();
    }

    @Test
    void reportsWhetherTheValueCameFromConfigurationOrFromTheDefault() {
        // During an incident this is the difference between "someone turned it off" and "it was
        // never on", and the answer must not require reading a deployment manifest.
        assertThat(with(null, null).isOverridden(FeatureFlag.RADAR)).isFalse();
        assertThat(with(FeatureFlag.RADAR.property(), "false").isOverridden(FeatureFlag.RADAR))
                .isTrue();
    }

    @Test
    void aBlankValueCountsAsUnset() {
        var flags = with(FeatureFlag.RADAR.property(), "   ");

        assertThat(flags.isEnabled(FeatureFlag.RADAR)).isTrue();
        assertThat(flags.isOverridden(FeatureFlag.RADAR)).isFalse();
    }
}
