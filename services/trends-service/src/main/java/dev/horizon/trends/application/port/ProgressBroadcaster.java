package dev.horizon.trends.application.port;

import dev.horizon.trends.domain.research.ResearchRequest;

/**
 * Pushes progress to connected SSE clients (ADR-0012).
 *
 * <p>Fan-out crosses replicas (a client may be attached to a different instance than the one
 * processing the saga), so the implementation publishes through Redis pub/sub. Delivery is
 * best-effort by design: the authoritative state is always available via {@code GET}, and the client
 * falls back to polling.
 */
public interface ProgressBroadcaster {

    void broadcast(ResearchRequest request);
}
