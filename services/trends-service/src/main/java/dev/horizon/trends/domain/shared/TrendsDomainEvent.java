package dev.horizon.trends.domain.shared;

import dev.horizon.platform.common.event.DomainEvent;

/**
 * Facts published by this bounded context — the context's published language.
 *
 * <p>Every implementation routes to {@link Topics#TRENDS_EVENTS}, so the destination is decided once
 * here instead of being repeated (and eventually mistyped) in each event.
 *
 * <p>Deliberately <em>not</em> {@code sealed}: outside a named module Java requires permitted
 * subtypes to live in the same package, and these events belong next to their aggregates
 * ({@code domain.research.event}, {@code domain.report.event}, {@code domain.feedback}). Grouping
 * them by transport rather than by aggregate would be worse modelling than losing a
 * compiler-enforced closed set. The closed set is maintained instead by an ArchUnit rule asserting
 * that every implementation lives under {@code dev.horizon.trends.domain} and exposes an explicit
 * payload record — so a new published fact cannot slip in unreviewed.
 */
public interface TrendsDomainEvent extends DomainEvent {

    @Override
    default String topic() {
        return Topics.TRENDS_EVENTS;
    }
}
