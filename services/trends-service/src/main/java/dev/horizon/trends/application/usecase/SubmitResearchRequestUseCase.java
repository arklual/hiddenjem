package dev.horizon.trends.application.usecase;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.CommandSender;
import dev.horizon.trends.application.port.DirectionCrosswalk;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.QuotaService;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.config.ResearchProperties;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.domain.shared.Topics;

/**
 * Accepts a new analysis request and starts the saga (UC-02).
 *
 * <p>Short-circuits are applied before doing any work, in this order:
 *
 * <ol>
 *   <li><b>Idempotency</b> — a repeated {@code Idempotency-Key} returns the original request, so a
 *       retrying client never starts a second expensive analysis;
 *   <li><b>In flight</b> — the same question of the same user already being analysed is returned as
 *       it stands (BR-A24); it outranks a finished result, because starting a recomputation is
 *       itself a judgement that the finished one is stale;
 *   <li><b>Freshness</b> — an equivalent completed request within the TTL is reused (BR-A8);
 *   <li><b>Quota</b> — only then is budget consumed (FR-02.6).
 * </ol>
 *
 * <p>The order matters: charging quota before the cache check would penalise users for asking a
 * question the system can answer for free.
 */
@Service
public class SubmitResearchRequestUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitResearchRequestUseCase.class);

    private final ResearchRequestRepository requests;
    private final MethodologyProfileRepository profiles;
    private final QuotaService quotas;
    private final DomainEventPublisher events;
    private final CommandSender commands;
    private final DirectionCrosswalk crosswalk;
    private final ResearchProperties properties;
    private final Clock clock;

    public SubmitResearchRequestUseCase(
            ResearchRequestRepository requests,
            MethodologyProfileRepository profiles,
            QuotaService quotas,
            DomainEventPublisher events,
            CommandSender commands,
            DirectionCrosswalk crosswalk,
            ResearchProperties properties,
            Clock clock) {
        this.requests = requests;
        this.profiles = profiles;
        this.quotas = quotas;
        this.events = events;
        this.commands = commands;
        this.crosswalk = crosswalk;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param outcome tells the controller whether to answer 202 (new work) or 200 (no new work) */
    public record Result(ResearchRequest request, Outcome outcome) {
        public enum Outcome {
            ACCEPTED("accepted"),
            IDEMPOTENT_REPLAY("idempotent-replay"),
            REUSED_FRESH_RESULT("reused"),
            /** The same question is already being analysed for this organisation; no second run started. */
            ALREADY_RUNNING("already-running");

            private final String wireName;

            Outcome(String wireName) {
                this.wireName = wireName;
            }

            /** The name clients see. Kept next to the constant so the two cannot drift apart. */
            public String wireName() {
                return wireName;
            }
        }

        /**
         * Whether no new analysis was started.
         *
         * <p>Deliberately not the only thing clients get: it collapses three different situations —
         * a finished report, a run already under way, a repeated call — into one bit, and they call
         * for three different words on screen. The outcome itself travels alongside it.
         */
        public boolean fromCache() {
            return outcome != Outcome.ACCEPTED;
        }
    }

    /** Keeps the four-argument form working for callers that never force a recomputation. */
    @Transactional
    public Result submit(
            RequesterRef requester, String rawQuery, AnalysisParameters requestedParameters, String idempotencyKey) {
        return submit(requester, rawQuery, requestedParameters, idempotencyKey, false);
    }

    /**
     * @param refresh skip the freshness reuse and compute again (BR-A39). Only that check is
     *     skipped: the idempotency key still collapses a double click, the in-flight guard still
     *     refuses a second parallel run, and quota is still charged — forcing a recomputation is a
     *     request for real work, not an exemption from paying for it.
     */
    @Transactional
    public Result submit(
            RequesterRef requester,
            String rawQuery,
            AnalysisParameters requestedParameters,
            String idempotencyKey,
            boolean refresh) {

        var now = clock.instant();
        var query = TechnologyDomainQuery.of(rawQuery);
        // Профиль всегда по умолчанию: выбора профиля в продукте больше нет. Назначается и тогда,
        // когда вызывающий его уже назвал, — параметры сохранённого направления хранят профиль,
        // выбранный когда-то на экране методологии, и новый запуск не должен тихо считать им.
        var parameters =
                requestedParameters.withProfile(profiles.requireDefault().id());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<ResearchRequest> existing = requests.findByIdempotencyKey(requester.userId(), idempotencyKey);
            if (existing.isPresent()) {
                log.debug("Идемпотентный повтор запроса {}", existing.get().id());
                return new Result(existing.get(), Result.Outcome.IDEMPOTENT_REPLAY);
            }
        }

        // Before the freshness lookup and before the charge: an analysis already under way makes both
        // pointless. It also outranks a finished one — if someone started a recomputation, the
        // finished report has already been judged stale.
        //
        // Across the organisation, not just this analyst: the corpus is collected once and the bill
        // arrives once, so a colleague who pressed the same button a second earlier is not asking a
        // different question — they are asking the same one, already being answered.
        Optional<ResearchRequest> running =
                requests.findActive(requester.organizationId(), query.normalized(), parameters);
        if (running.isPresent()) {
            log.debug("По направлению уже идёт запрос {}", running.get().id());
            return new Result(running.get(), Result.Outcome.ALREADY_RUNNING);
        }

        if (!refresh) {
            var freshCutoff = now.minus(properties.resultTtl());
            Optional<ResearchRequest> fresh = requests.findFreshCompleted(
                    query.normalized(), parameters, requester.organizationId(), freshCutoff);
            if (fresh.isPresent()) {
                log.debug(
                        "Переиспользуется актуальный отчёт запроса {}",
                        fresh.get().id());
                return new Result(fresh.get(), Result.Outcome.REUSED_FRESH_RESULT);
            }
        }

        quotas.checkAndConsume(requester.userId(), requester.organizationId());

        // Everything after the charge is compensated on failure. The counter lives in Redis and takes
        // no part in this transaction, so a rollback undoes the request and leaves the charge — the
        // analyst pays twice: once by not getting the analysis, and again by hitting "quota
        // exceeded" earlier than they should, with nothing in the interface connecting the two.
        try {
            // Срок — по режиму: качественный запрос, закрытый таймаутом быстрого, был бы отказом в
            // том самом ожидании, на которое аналитик согласился.
            var request = ResearchRequest.submit(
                    requester, query, parameters, idempotencyKey, properties.sagaTimeout(parameters.mode()), now);
            // Сбор начинается здесь, потому что здесь уходит команда на него. Без этого перехода
            // запрос оставался в PENDING, и первое же событие от ingestion разбивалось о машину
            // состояний: «Недопустимый переход PENDING → ANALYZING». Сага так и не начиналась ни
            // разу — до запуска полного стека это было не видно, потому что все тесты саги
            // выставляют COLLECTING руками, а метод `startCollecting` в рабочем пути не звал никто.
            //
            // Замысел, судя по `ResearchSaga.onCollectionStarted`, был другим: ingestion
            // подтверждает получение команды отдельным событием, и переход делает сага. События
            // такого нет ни в контракте, ни в коде ingestion, а лишний круг по брокеру ради
            // подтверждения того, что и так записано в одной транзакции с командой, ничего не
            // добавляет: если ingestion не ответит, запрос закроет таймаут саги.
            request.startCollecting(now);
            var saved = requests.save(request);

            events.publish(saved.drainEvents());
            commands.send(CommandSender.OutboundCommand.of(
                    "horizon.ingestion.CollectDomainCorpus",
                    Topics.INGESTION_COMMANDS,
                    saved.id().toString(),
                    collectCommandPayload(saved, now)));

            log.info("Принят запрос {} по направлению '{}'", saved.id(), query.normalized());
            return new Result(saved, Result.Outcome.ACCEPTED);
        } catch (RuntimeException e) {
            // The refund is guarded here rather than trusted to be fail-open in the implementation:
            // if it threw, its exception would take the place of the one the analyst actually needs
            // — "a counter could not be restored" instead of "your request did not go through".
            try {
                quotas.refund(requester.userId(), requester.organizationId(), now);
            } catch (RuntimeException refundFailure) {
                log.warn("Не удалось вернуть квоту после несостоявшегося запроса", refundFailure);
            }
            // Rethrown, not swallowed: the transaction must still roll back, and the caller must
            // learn that the request did not happen.
            throw e;
        }
    }

    /** Payload shape of {@code contracts/schemas/collect-domain-corpus.command.json}. */
    private CollectDomainCorpusPayload collectCommandPayload(ResearchRequest request, java.time.Instant now) {
        LocalDate windowTo = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        LocalDate windowFrom = windowTo.minusYears(request.parameters().yearsWindow());
        return new CollectDomainCorpusPayload(
                request.id().toString(),
                request.attempt(),
                request.query().raw(),
                request.query().normalized(),
                request.query().language(),
                windowFrom,
                windowTo,
                request.parameters().sourceClasses().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList(),
                properties.maxDocuments(),
                // Коды направления добываются здесь, а не в сборе: словарь — знание о направлениях,
                // и его копия у коннекторов разошлась бы с той, по которой идёт отбор тем. Пустой
                // список допустим и означает «искать словами самого запроса», как было раньше.
                crosswalk.targetsFor(request.query().raw()),
                request.parameters().mode().wireName());
    }

    public record CollectDomainCorpusPayload(
            String researchRequestId,
            int attempt,
            String query,
            String normalizedQuery,
            String queryLanguage,
            LocalDate windowFrom,
            LocalDate windowTo,
            List<String> sourceClasses,
            int maxDocuments,
            /**
             * Предметные коды корпуса для этого направления.
             *
             * <p>Поле необязательное: команда без него — та же команда, и сбор в этом случае идёт
             * по словам запроса. Иначе выкатывать эту правку пришлось бы двумя сервисами разом.
             */
            List<String> subjectTargets,
            /**
             * Режим анализа: {@code fast} | {@code quality}.
             *
             * <p>Сбор выбирает по нему бюджеты источников — прежде всего глубокого исследования, — и
             * без него качественный запрос получил бы быстрый корпус под сорокаминутным сроком.
             * Необязательно по контракту: команда без поля читается как {@code fast}.
             */
            String mode) {}

    /** Convenience for callers that only have raw identifiers. */
    public static RequesterRef requester(UUID userId, UUID organizationId) {
        return new RequesterRef(userId, organizationId);
    }
}
