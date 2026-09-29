package dev.horizon.ingestion.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.RateLimiters;
import dev.horizon.ingestion.domain.port.SourceRepository;
import dev.horizon.ingestion.domain.source.Source;

/**
 * Where deployed connectors meet their configured policy.
 *
 * <p>A connector is a class; a {@link Source} is a row. Both must agree before a collection may use
 * one: the class must {@code supports()} the request and report itself available, and the row must
 * be enabled. Adding a connector therefore requires no change here — Spring injects it, the seed
 * migration configures it (NFR-M2).
 *
 * <p>Connectors are always returned in a stable order (by id). That is not cosmetic: with a shared
 * document budget the order determines which sources fill it, and a reproducible corpus needs a
 * reproducible order (ADR-0015).
 */
@Service
public class ConnectorCatalog {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCatalog.class);

    private final Map<String, NormalizingSourceConnector> connectorsById;
    private final SourceRepository sources;
    /** Ids already reported as missing a row; keeps a per-collection check from flooding the log. */
    private final Set<String> warnedMissingRows = ConcurrentHashMap.newKeySet();

    private final RateLimiters rateLimiters;

    public ConnectorCatalog(
            List<NormalizingSourceConnector> connectors, SourceRepository sources, RateLimiters rateLimiters) {
        Map<String, NormalizingSourceConnector> byId = new LinkedHashMap<>();
        connectors.stream()
                .sorted(Comparator.comparing(connector -> connector.descriptor().id()))
                .forEach(connector -> byId.put(connector.descriptor().id(), connector));
        this.connectorsById = Map.copyOf(byId);
        this.sources = sources;
        this.rateLimiters = rateLimiters;
        log.info("Registered {} source connectors: {}", byId.size(), byId.keySet());
    }

    public List<NormalizingSourceConnector> all() {
        return connectorsById.values().stream()
                .sorted(Comparator.comparing(connector -> connector.descriptor().id()))
                .toList();
    }

    public Optional<NormalizingSourceConnector> byId(String sourceId) {
        return Optional.ofNullable(connectorsById.get(sourceId));
    }

    /**
     * Connectors that may take part in this collection, in stable order.
     *
     * <p>Источник, включённый, но сейчас неработоспособный (нет ключа, не настроены ленты),
     * <em>включается</em> намеренно: сбор обязан сообщить о нём в {@code unavailableSources}, а не
     * сделать вид, что его нет. Это пробел в покрытии, и аналитик о нём узнаёт.
     *
     * <p>Источник, выключенный настройкой развёртывания, наоборот — не включается вовсе. Его
     * отсутствие не пробел, а состав установки: на стенде включён только эталонный корпус, и
     * оговорка «часть источников была недоступна» с перечнем из шести имён висела над каждым
     * отчётом, ничего не сообщая. Предупреждение, которое печатается всегда, перестают читать —
     * вместе с теми, которые что-то значат.
     */
    public List<NormalizingSourceConnector> connectorsFor(CollectionRequest request) {
        var selected = new ArrayList<NormalizingSourceConnector>();
        warnAboutConnectorsWithoutASourceRow();
        for (Source source : sources.findAll()) {
            if (!source.isEnabled()) {
                continue;
            }
            NormalizingSourceConnector connector = connectorsById.get(source.id());
            if (connector == null) {
                log.warn("Source {} is enabled but no connector is deployed for it", source.id());
                continue;
            }
            // Keep the runtime budget in step with the row an operator may have just edited.
            rateLimiters.forSource(source.id(), source.rateLimitPerMinute());
            if (!request.acceptsAnyOf(connector.descriptor().providedClasses())) {
                continue; // the caller asked for other source classes — not an availability problem
            }
            if (connector.descriptor().switchedOff()) {
                continue;
            }
            if (!connector.descriptor().available() || connector.supports(request)) {
                selected.add(connector);
            }
        }
        selected.sort(Comparator.comparing(connector -> connector.descriptor().id()));
        return List.copyOf(selected);
    }

    /**
     * The mirror of the "row without a connector" warning above, and the more dangerous half.
     *
     * <p>Collection iterates {@code sources}, not beans, so a connector deployed without its row is
     * never called at all. Nothing throws and nothing is missing from a log: the source simply
     * contributes no documents, which reads as "that source found nothing" rather than as a
     * forgotten migration. Whoever adds a source under the final requirements will hit this, so it
     * says so out loud — once per id, because this runs on every collection.
     */
    private void warnAboutConnectorsWithoutASourceRow() {
        var known = sources.findAll().stream().map(Source::id).collect(Collectors.toSet());
        for (String id : connectorsById.keySet()) {
            if (!known.contains(id) && warnedMissingRows.add(id)) {
                log.warn(
                        "Коннектор {} задеплоен, но строки в таблице sources для него нет — "
                                + "источник не будет вызван ни разу; добавьте строку миграцией",
                        id);
            }
        }
    }
}
