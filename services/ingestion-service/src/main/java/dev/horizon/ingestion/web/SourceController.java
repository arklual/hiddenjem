package dev.horizon.ingestion.web;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.ingestion.application.ListIngestionRunsUseCase;
import dev.horizon.ingestion.application.ListSourcesUseCase;
import dev.horizon.ingestion.application.RunConnectorUseCase;
import dev.horizon.ingestion.application.UpdateSourceUseCase;
import dev.horizon.ingestion.connector.openalex.OpenAlexQuotaService;
import dev.horizon.ingestion.domain.run.RunMode;
import dev.horizon.ingestion.web.dto.IngestionRunView;
import dev.horizon.ingestion.web.dto.SourceView;
import dev.horizon.ingestion.web.dto.TriggerIngestionRequest;
import dev.horizon.ingestion.web.dto.UpdateSourceRequest;
import dev.horizon.platform.spring.web.ApiPage;

/** Source registry and manual runs — the {@code Sources} tag of the OpenAPI contract. */
@RestController
@RequestMapping("/api/v1")
public class SourceController {

    private final ListSourcesUseCase listSources;
    private final UpdateSourceUseCase updateSource;
    private final RunConnectorUseCase runConnector;
    private final ListIngestionRunsUseCase listRuns;
    private final OpenAlexQuotaService openAlexQuota;

    public SourceController(
            ListSourcesUseCase listSources,
            UpdateSourceUseCase updateSource,
            RunConnectorUseCase runConnector,
            ListIngestionRunsUseCase listRuns,
            OpenAlexQuotaService openAlexQuota) {
        this.listSources = listSources;
        this.updateSource = updateSource;
        this.runConnector = runConnector;
        this.listRuns = listRuns;
        this.openAlexQuota = openAlexQuota;
    }

    @GetMapping("/sources")
    public List<SourceView> list() {
        return listSources.list().stream().map(SourceView::from).toList();
    }

    @GetMapping("/sources/openalex/quota")
    public OpenAlexQuotaService.Snapshot openAlexQuota() {
        return openAlexQuota.snapshot();
    }

    @PatchMapping("/sources/{sourceId}")
    public SourceView update(@PathVariable String sourceId, @Valid @RequestBody UpdateSourceRequest request) {
        return SourceView.from(updateSource.update(sourceId, request.enabled(), request.rateLimitPerMinute()));
    }

    @PostMapping("/sources/{sourceId}/runs")
    public ResponseEntity<IngestionRunView> trigger(
            @PathVariable String sourceId, @Valid @RequestBody(required = false) TriggerIngestionRequest request) {
        TriggerIngestionRequest effective = request == null ? TriggerIngestionRequest.defaults() : request;
        var run = runConnector.trigger(
                sourceId,
                effective.mode() == null ? RunMode.INCREMENTAL : RunMode.valueOf(effective.mode()),
                effective.query(),
                effective.windowFrom(),
                effective.windowTo());
        return ResponseEntity.accepted().body(IngestionRunView.from(run));
    }

    @GetMapping("/ingestion-runs")
    public ApiPage<IngestionRunView> runs(
            @RequestParam(required = false) String sourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = listRuns.list(sourceId, page, size);
        return new ApiPage<>(
                result.content().stream().map(IngestionRunView::from).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }

    @SuppressWarnings("unused")
    private static final HttpStatus ACCEPTED = HttpStatus.ACCEPTED;
}
