package dev.horizon.ingestion.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.port.IngestionRunRepository;
import dev.horizon.ingestion.domain.port.PageResult;
import dev.horizon.ingestion.domain.run.IngestionRun;

/** UC-11: history of ingestion runs, optionally narrowed to one source. */
@Service
public class ListIngestionRunsUseCase {

    private static final int MAX_PAGE_SIZE = 100;

    private final IngestionRunRepository runs;

    public ListIngestionRunsUseCase(IngestionRunRepository runs) {
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public PageResult<IngestionRun> list(String sourceId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return runs.findPage(sourceId, safePage, safeSize);
    }
}
