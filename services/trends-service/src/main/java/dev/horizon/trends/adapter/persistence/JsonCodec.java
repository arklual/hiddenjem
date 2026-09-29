package dev.horizon.trends.adapter.persistence;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin Jackson facade for the {@code jsonb} columns.
 *
 * <p>Wrapping the checked {@link JsonProcessingException} is deliberate: a value object that cannot
 * be serialised is a programming error, not a runtime condition a caller could recover from, and
 * forcing every call site to catch it would bury the mapping code in noise. A malformed value read
 * back from the database is equally unrecoverable — it means the row was written by something that
 * does not respect the schema.
 */
@Component
public class JsonCodec {

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать значение в jsonb: " + value.getClass(), e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Некорректный jsonb в базе данных: " + json, e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Некорректный jsonb в базе данных: " + json, e);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
