package com.firstelement.crm.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * jsonb ⇄ Java for the CRM's {@code jsonb} columns.
 *
 * <h2>Why an explicit codec instead of JPA/JDBC json mapping</h2>
 *
 * <p>The physical schema stores {@code options}, {@code answers}, {@code payload},
 * {@code matchAnswers}, {@code filters}, {@code sort}, {@code columnConfig} and
 * {@code metadata} as {@code jsonb}. There is no ORM in this runtime (ADR-0002: explicit
 * quoted-column JDBC), so this component is the only place that decides how a
 * {@code jsonb} column becomes a typed value.
 *
 * <h2>Failure policy</h2>
 *
 * <p>Reading is lenient in one specific, deliberate way: a column that is not valid JSON, or
 * whose shape changed, becomes an empty structure rather than a 500. A corrupt
 * {@code conversation_events.payload} must not make an inbox thread unreadable; the
 * timeline entry still renders with its type and timestamp. Writing never fails silently —
 * a value that cannot be serialized is a programming error and propagates.
 */
@Component
public class JsonCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() { };

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
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
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Reads a {@code jsonb} array column; {@code null}/unparseable yields an empty list. */
    public List<Object> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Reads a {@code text[]} column as written by the PostgreSQL array driver. */
    public List<String> readStringArray(String[] values) {
        return values == null ? List.of() : List.of(values);
    }
}