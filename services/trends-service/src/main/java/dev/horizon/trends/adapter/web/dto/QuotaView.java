package dev.horizon.trends.adapter.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.application.port.QuotaService;

/**
 * OpenAPI {@code Quota} — what is left of the hourly budget (BR-A49).
 *
 * <p>{@code known = false} rather than zeroes when the counter is unreadable: the quota is fail-open,
 * so requests still go through, and reporting "нет бюджета" would make the interface say the opposite
 * of what the system does (P6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuotaView(
        boolean known,
        Integer remaining,
        Integer userRemaining,
        Integer userLimit,
        Integer organizationRemaining,
        Integer organizationLimit) {

    public static QuotaView unknown() {
        return new QuotaView(false, null, null, null, null, null);
    }

    public static QuotaView of(QuotaService.QuotaBudget budget) {
        return new QuotaView(
                true,
                budget.effectiveRemaining(),
                budget.userRemaining(),
                budget.userLimit(),
                budget.organizationRemaining(),
                budget.organizationLimit());
    }
}
