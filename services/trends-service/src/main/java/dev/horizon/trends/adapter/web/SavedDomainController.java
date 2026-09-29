package dev.horizon.trends.adapter.web;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.trends.adapter.web.dto.AnalysisParametersDto;
import dev.horizon.trends.adapter.web.dto.DirectionOverlapView;
import dev.horizon.trends.adapter.web.dto.RadarDigestView;
import dev.horizon.trends.adapter.web.dto.RadarRefreshView;
import dev.horizon.trends.adapter.web.dto.SaveDomainRequestBody;
import dev.horizon.trends.adapter.web.dto.SavedDomainView;
import dev.horizon.trends.application.usecase.RadarDigestUseCase;
import dev.horizon.trends.application.usecase.RefreshRadarUseCase;
import dev.horizon.trends.application.usecase.SavedDomainService;
import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.research.RequesterRef;

/**
 * Directions an analyst tracks over time (OpenAPI tag {@code SavedDomains}).
 *
 * <p>Every operation is scoped to the caller inside the service, never by an identifier from the
 * path alone — an id is not an authorisation token, and a delete that trusted one would be a
 * textbook IDOR.
 */
@RestController
@RequestMapping("/api/v1/saved-domains")
public class SavedDomainController {

    private final SavedDomainService service;
    private final FeatureGate features;
    private final Clock clock;
    private final RefreshRadarUseCase refresh;
    private final RadarDigestUseCase radar;
    private final CurrentCaller currentUser;

    public SavedDomainController(
            SavedDomainService service,
            FeatureGate features,
            Clock clock,
            RefreshRadarUseCase refresh,
            RadarDigestUseCase radar,
            CurrentCaller currentUser) {
        this.service = service;
        this.features = features;
        this.clock = clock;
        this.refresh = refresh;
        this.radar = radar;
        this.currentUser = currentUser;
    }

    /**
     * The state of everything the analyst tracks, in one request (UC-13).
     *
     * <p>Server-side because the client alternative is two requests per tracked direction: fifty
     * saved directions would become a hundred round trips and a screen that fills in over seconds.
     */
    @GetMapping("/digest")
    public List<RadarDigestView> digest() {
        features.require(FeatureFlag.RADAR);
        var caller = currentUser.require();
        return radar.digest(ReportViewers.of(caller)).stream()
                .map(RadarDigestView::from)
                .toList();
    }

    /**
     * Отметить направление просмотренным (BR-A62, BR-A64).
     *
     * <p>Отдельное действие, а не побочный эффект загрузки радара: радар открывают, чтобы решить,
     * куда смотреть, и пометить в этот момент всё просмотренным значило бы стереть ответ на вопрос,
     * ради которого пришли.
     */
    @PostMapping("/{id}/seen")
    public ResponseEntity<Void> markSeen(@PathVariable UUID id) {
        features.require(FeatureFlag.RADAR);
        radar.markSeen(ReportViewers.of(currentUser.require()), id, clock.instant());
        return ResponseEntity.noContent().build();
    }

    /**
     * Topics that surfaced in more than one tracked direction (UC-15).
     *
     * <p>Its own request rather than a field of the digest: the radar opens on every visit, and this
     * answers a question the analyst asks occasionally and deliberately.
     */
    @GetMapping("/overlap")
    public DirectionOverlapView overlap() {
        features.require(FeatureFlag.DIRECTION_OVERLAP);
        var caller = currentUser.require();
        return DirectionOverlapView.from(radar.overlap(ReportViewers.of(caller)));
    }

    /**
     * Put every tracked direction back on the queue (UC-14).
     *
     * <p>Answers 200 with a row per direction even when none was queued: three of the four outcomes
     * are normal, and a failed status would hide which direction met which.
     */
    @PostMapping("/refresh")
    public List<RadarRefreshView> refresh() {
        features.require(FeatureFlag.SAVED_DOMAINS);
        var caller = currentUser.require();
        return RadarRefreshView.from(refresh.refresh(new RequesterRef(caller.userId(), caller.organizationId())));
    }

    @GetMapping
    public List<SavedDomainView> list() {
        features.require(FeatureFlag.SAVED_DOMAINS);
        return service.list(currentUser.require().userId()).stream()
                .map(SavedDomainView::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<SavedDomainView> save(@Valid @RequestBody SaveDomainRequestBody body) {
        features.require(FeatureFlag.SAVED_DOMAINS);
        var saved = service.save(
                currentUser.require().userId(), body.query(), AnalysisParametersDto.toDomain(body.parameters()));
        return ResponseEntity.status(HttpStatus.CREATED).body(SavedDomainView.from(saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        features.require(FeatureFlag.SAVED_DOMAINS);
        service.delete(currentUser.require().userId(), id);
        return ResponseEntity.noContent().build();
    }
}
