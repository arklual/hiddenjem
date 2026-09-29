package dev.horizon.ingestion.connector;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.domain.document.Provenance;

/** Общее для тестов источников, добавленных по исследованиям к кейсу: разбор записанного ответа. */
public final class ResearchSourcesTestSupport {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ResearchSourcesTestSupport() {}

    public static <T> T read(String resource, Class<T> type) throws IOException {
        try (InputStream stream = ResearchSourcesTestSupport.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("записанный ответ не найден: " + resource);
            }
            return MAPPER.readValue(stream, type);
        }
    }

    public static Provenance provenance(String sourceId) {
        return new Provenance(sourceId, Instant.parse("2026-09-18T10:00:00Z"), "https://example.test/", 200,
                "c".repeat(64), null);
    }
}
