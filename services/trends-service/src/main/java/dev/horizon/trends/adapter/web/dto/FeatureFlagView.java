package dev.horizon.trends.adapter.web.dto;

import java.util.List;

import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureFlags;

/**
 * OpenAPI {@code FeatureFlag} — what this build has switched on.
 *
 * <p>Read after sign-in, alongside every other endpoint — see {@code FeatureFlagController} for why
 * this is not the anonymous endpoint an earlier comment described.
 *
 * @param source {@code default} when the declared default applies, {@code override} when
 *     configuration set it — so an incident can be diagnosed without reading a deployment manifest
 */
public record FeatureFlagView(String key, String title, String description, boolean enabled, String source) {

    public static List<FeatureFlagView> from(FeatureFlags flags) {
        return FeatureFlag.all().stream()
                .map(flag -> new FeatureFlagView(
                        flag.key(),
                        flag.title(),
                        flag.description(),
                        flags.isEnabled(flag),
                        flags.isOverridden(flag) ? "override" : "default"))
                .toList();
    }
}
