package dev.horizon.trends.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolved state of every {@link FeatureFlag}, read once at startup.
 *
 * <p>Read once because a flag that changes under a running request would make two halves of one
 * response disagree — the report carrying a portrait while the endpoint that explains it has already
 * gone. Changing a flag is a deployment decision, and deployments restart the service.
 */
@Component
public class FeatureFlags {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);

    private final Map<FeatureFlag, Boolean> state;
    private final Map<FeatureFlag, Boolean> overridden;

    public FeatureFlags(Environment environment) {
        var resolved = new LinkedHashMap<FeatureFlag, Boolean>();
        var fromOverride = new LinkedHashMap<FeatureFlag, Boolean>();
        for (FeatureFlag flag : FeatureFlag.values()) {
            String raw = environment.getProperty(flag.property());
            resolved.put(flag, parse(flag, raw));
            fromOverride.put(flag, raw != null && !raw.isBlank());
        }
        this.state = Map.copyOf(resolved);
        this.overridden = Map.copyOf(fromOverride);

        var off = resolved.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(entry -> entry.getKey().key())
                .toList();
        log.info("Функции выключены: {}", off.isEmpty() ? "нет" : String.join(", ", off));
    }

    /**
     * A value that is present but not a boolean counts as enabled, loudly.
     *
     * <p>A typo in a deployment manifest must not remove a feature: silently switching something off
     * because of {@code "ture"} is a far worse outcome than ignoring the line and saying so.
     */
    private static boolean parse(FeatureFlag flag, String raw) {
        if (raw == null || raw.isBlank()) {
            return flag.enabledByDefault();
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.equals("true") || value.equals("false")) {
            return value.equals("true");
        }
        log.warn("Значение {} для {} не разобрано как булево — функция оставлена включённой", raw, flag.property());
        return true;
    }

    public boolean isEnabled(FeatureFlag flag) {
        return Boolean.TRUE.equals(state.get(flag));
    }

    /** Whether the current value came from configuration rather than from the declared default. */
    public boolean isOverridden(FeatureFlag flag) {
        return Boolean.TRUE.equals(overridden.get(flag));
    }
}
