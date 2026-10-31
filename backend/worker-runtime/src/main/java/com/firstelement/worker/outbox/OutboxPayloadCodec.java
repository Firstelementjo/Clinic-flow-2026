package com.firstelement.worker.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code jsonb} ⇄ Java for the queue's own columns.
 *
 * <h2>Why this duplicates {@code crm-core}'s {@code JsonCodec}</h2>
 *
 * <p>Because {@code :backend:worker-runtime} must not depend on {@code :backend:crm-core}: the
 * worker runtime is the durable engine, and pointing it at a CRM domain module would make the
 * queue depend on customers. The failure policy is the same and deliberately so — reading is
 * lenient (an unparseable payload becomes an empty map rather than a 500 on the dispatcher loop),
 * writing never fails silently.
 */
@Component
public class OutboxPayloadCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper mapper;

    public OutboxPayloadCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Serializes a value for a {@code jsonb} parameter. */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Value is not serializable as jsonb", e);
        }
    }

    /** Reads a {@code jsonb} object column; {@code null}/unparseable yields an empty map. */
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Reads a {@code jsonb} array column. */
    public List<Object> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(mapper.readValue(json,
                new TypeReference<List<Object>>() { }));
        } catch (Exception e) {
            return List.of();
        }
    }
}