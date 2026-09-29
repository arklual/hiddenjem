package dev.horizon.trends.adapter.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.horizon.platform.spring.caller.Caller;
import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.platform.spring.web.ApiPage;
import dev.horizon.trends.adapter.cache.ProgressEvents;
import dev.horizon.trends.adapter.cache.SseEmitterRegistry;
import dev.horizon.trends.adapter.web.dto.AnalysisParametersDto;
import dev.horizon.trends.adapter.web.dto.QuotaView;
import dev.horizon.trends.adapter.web.dto.ResearchRequestView;
import dev.horizon.trends.adapter.web.dto.SubmitResearchRequestBody;
import dev.horizon.trends.adapter.web.mapper.ResearchViewMapper;
import dev.horizon.trends.application.port.QuotaService;
import dev.horizon.trends.application.usecase.CancelResearchRequestUseCase;
import dev.horizon.trends.application.usecase.ResearchRequestQueryService;
import dev.horizon.trends.application.usecase.SubmitResearchRequestUseCase;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;

/**
 * Research requests: submit, observe, cancel (OpenAPI tag {@code Research}).
 *
 * <p>The controller is intentionally thin — parse, delegate, map. Authorisation is not done here but
 * in the application services, so that the SSE path, the polling path and any future non-HTTP caller
 * are all governed by the same rule instead of three copies of it.
 */
@RestController
@RequestMapping("/api/v1/research-requests")
@Validated
public class ResearchRequestController {

    private final SubmitResearchRequestUseCase submitUseCase;
    private final ResearchRequestQueryService queryService;
    private final CancelResearchRequestUseCase cancelUseCase;
    private final ResearchViewMapper mapper;
    private final SseEmitterRegistry emitters;
    private final ProgressEvents progressEvents;
    private final QuotaService quotas;
    private final CurrentCaller currentUser;

    public ResearchRequestController(
            SubmitResearchRequestUseCase submitUseCase,
            ResearchRequestQueryService queryService,
            CancelResearchRequestUseCase cancelUseCase,
            ResearchViewMapper mapper,
            SseEmitterRegistry emitters,
            ProgressEvents progressEvents,
            QuotaService quotas,
            CurrentCaller currentUser) {
        this.quotas = quotas;
        this.submitUseCase = submitUseCase;
        this.queryService = queryService;
        this.cancelUseCase = cancelUseCase;
        this.mapper = mapper;
        this.emitters = emitters;
        this.progressEvents = progressEvents;
        this.currentUser = currentUser;
    }

    /**
     * Submits an analysis.
     *
     * <p>202 means new work was started; 200 means the answer already existed — either the same
     * {@code Idempotency-Key} was replayed or an equivalent fresh result was reused (BR-A8). The
     * distinction matters to the client: a 202 justifies opening the progress stream, a 200 means the
     * report can be fetched immediately.
     */
    @PostMapping
    public ResponseEntity<ResearchRequestView> submit(
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 80) String idempotencyKey,
            @Valid @RequestBody SubmitResearchRequestBody body) {

        Caller caller = currentUser.require();
        var result = submitUseCase.submit(
                SubmitResearchRequestUseCase.requester(caller.userId(), caller.organizationId()),
                body.query(),
                // Незнакомый режим отсюда не проходит — домен отказывает, и клиент получает 400 с
                // перечнем режимов, а не отчёт, посчитанный не так, как просили.
                AnalysisParametersDto.toDomain(body.parameters()),
                idempotencyKey,
                body.forceRefresh());

        var view = mapper.toView(result.request(), result.outcome());
        if (result.fromCache()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.accepted()
                .location(URI.create(
                        "/api/v1/research-requests/" + result.request().id()))
                .body(view);
    }

    /**
     * What is left of the hourly budget (BR-A49).
     *
     * <p>Its own small request rather than a field of something bigger: it is read on two screens and
     * changes more often than anything else on them — including because of a colleague.
     */
    @GetMapping("/quota")
    public QuotaView quota() {
        var caller = currentUser.require();
        return quotas.remaining(caller.userId(), caller.organizationId())
                .map(QuotaView::of)
                .orElseGet(QuotaView::unknown);
    }

    @GetMapping
    public ApiPage<ResearchRequestView> history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ResearchStatus status,
            @RequestParam(defaultValue = "false") boolean onlyMine) {

        Caller caller = currentUser.require();
        var result = queryService.history(ReportViewers.of(caller), onlyMine, status, page, size);
        return new ApiPage<>(
                result.content().stream()
                        .map(request -> mapper.toView(request, caller.userId()))
                        .toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }

    @GetMapping("/{requestId}")
    public ResearchRequestView get(@PathVariable UUID requestId) {
        Caller caller = currentUser.require();
        return mapper.toView(queryService.get(new ResearchRequestId(requestId), ReportViewers.of(caller)));
    }

    /**
     * Progress stream (ADR-0012).
     *
     * <p>The authorisation check runs <em>before</em> the emitter is created: an unauthorised caller
     * must get a problem+json response, and once the response is committed as an event stream there
     * is no way to send one. A snapshot of the current state is pushed immediately so a client that
     * connects late — or reconnects — is never left staring at an empty stream waiting for the next
     * transition.
     */
    @GetMapping(path = "/{requestId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable UUID requestId, @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {

        Caller caller = currentUser.require();
        var request = queryService.get(new ResearchRequestId(requestId), ReportViewers.of(caller));
        return emitters.register(requestId.toString(), lastEventId, progressEvents.from(mapper.toView(request)));
    }

    @PostMapping("/{requestId}/cancel")
    @ResponseStatus(HttpStatus.OK)
    public ResearchRequestView cancel(@PathVariable UUID requestId) {
        Caller caller = currentUser.require();
        return mapper.toView(cancelUseCase.cancel(new ResearchRequestId(requestId), ReportViewers.of(caller)));
    }
}
