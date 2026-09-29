package dev.horizon.trends.adapter.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.trends.adapter.web.dto.FeatureFlagView;
import dev.horizon.trends.config.FeatureFlags;

/**
 * What this build has switched on (OpenAPI tag {@code Features}).
 *
 * <p>The client reads this once and hides the features that are off, so a switched-off feature never
 * shows a control that answers 404.
 *
 * <p>Authenticated like every other endpoint: the default filter chain admits only actuator and the
 * API docs anonymously. An earlier version of this comment claimed otherwise — the registry is in
 * fact read after sign-in, which is when it is needed, and opening a public endpoint to make a
 * comment true would have been the wrong repair.
 */
@RestController
@RequestMapping("/api/v1/features")
public class FeatureFlagController {

    private final FeatureFlags flags;

    public FeatureFlagController(FeatureFlags flags) {
        this.flags = flags;
    }

    @GetMapping
    public List<FeatureFlagView> list() {
        return FeatureFlagView.from(flags);
    }
}
