package dev.horizon.ingestion.domain.port;

import java.util.List;
import java.util.Optional;

import dev.horizon.ingestion.domain.source.Source;

/** Persistence port for the {@link Source} aggregate. */
public interface SourceRepository {

    List<Source> findAll();

    Optional<Source> findById(String id);

    Source save(Source source);
}
