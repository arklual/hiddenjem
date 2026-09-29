package dev.horizon.trends.config;

import org.springframework.stereotype.Component;

import dev.horizon.platform.common.error.HorizonException;

/**
 * Refuses a call to a feature that is switched off.
 *
 * <p>404 rather than an empty body, and rather than 403. An empty body reads as "there is no data",
 * which is a statement about the subject an analyst is studying; "this build does not have that
 * feature" is a statement about the deployment, and the two demand different reactions. 403 would
 * claim the caller lacks a right, which is also untrue.
 */
@Component
public class FeatureGate {

    private final FeatureFlags flags;

    public FeatureGate(FeatureFlags flags) {
        this.flags = flags;
    }

    /** @throws HorizonException with a not-found problem when {@code flag} is switched off */
    public void require(FeatureFlag flag) {
        if (!flags.isEnabled(flag)) {
            throw HorizonException.notFound("Функция", flag.key());
        }
    }

    public boolean isEnabled(FeatureFlag flag) {
        return flags.isEnabled(flag);
    }
}
