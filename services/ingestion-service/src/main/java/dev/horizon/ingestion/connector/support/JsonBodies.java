package dev.horizon.ingestion.connector.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parsing helper shared by the JSON connectors.
 *
 * <p>Turns a parse failure into a {@link ConnectorException.Permanent}: a body we cannot read will
 * not become readable on a retry, and the distinction is what keeps the retry budget for real
 * outages.
 */
public final class JsonBodies {

    private JsonBodies() {}

    public static <T> T parse(ObjectMapper mapper, String sourceId, String body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new ConnectorException.Permanent(
                    sourceId,
                    200,
                    "%s returned a body that does not match its contract: %s"
                            .formatted(sourceId, e.getOriginalMessage()),
                    e);
        }
    }
}
