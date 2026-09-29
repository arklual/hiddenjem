package dev.horizon.ingestion.domain.event;

/**
 * Topic and event-type names, verbatim from {@code contracts/asyncapi/horizon-events.yaml}.
 *
 * <p>Constants rather than literals scattered through the code: the topic name is a published
 * contract, and a typo would produce a silently-created topic nobody consumes.
 */
public final class IngestionTopics {

    /** Commands addressed to this service. */
    public static final String COMMANDS = "horizon.ingestion.commands.v1";

    /** Outcomes of a corpus collection, consumed by the research saga. */
    public static final String EVENTS = "horizon.ingestion.events.v1";

    /** High-volume stream of canonical documents. */
    public static final String DOCUMENTS = "horizon.documents.v1";

    public static final String TYPE_DOCUMENT_INGESTED = "horizon.ingestion.DocumentIngested";
    public static final String TYPE_CORPUS_COLLECTED = "horizon.ingestion.CorpusCollected";
    public static final String TYPE_CORPUS_COLLECTION_FAILED = "horizon.ingestion.CorpusCollectionFailed";
    public static final String TYPE_CORPUS_COLLECTION_PROGRESSED = "horizon.ingestion.CorpusCollectionProgressed";

    private IngestionTopics() {}
}
