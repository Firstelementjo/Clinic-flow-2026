package com.firstelement.inbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The legacy behaviour comparison fixtures.
 *
 * <h2>What a fixture is</h2>
 *
 * <p>A JSON file under {@code src/test/resources/fixtures/legacy/<name>.json} holding the
 * <b>request and the legacy response</b> of one representative CRM call:
 *
 * <pre>{@code
 * {
 *   "name": "...",
 *   "source": "apps/api/src/... (the legacy reference)",
 *   "seed":  "...what the comparison test seeded...",
 *   "request":  { ... },
 *   "response": { ... },        // what the NestJS runtime returned
 *   "volatile": ["id", ...]    // fields replaced by a marker before comparison
 * }
 * }</pre>
 *
 * <p>The legacy shapes come from the TypeScript response builders named in {@code source} — they
 * are pure projections with no I/O, so the field names, the transformations and the orderings are
 * all visible in the code. What a fixture cannot prove is that the legacy database agreed with its
 * own projection; that is covered by the container-backed suites.
 *
 * <h2>Why the fixtures assert literals, not shapes only</h2>
 *
 * <p>The inbox fixture seeds <em>fixed</em> uuids and <em>fixed</em> timestamps, so a fixture can
 * assert the exact rendered timestamp, the exact ticket number and the exact 140-character preview
 * truncation. A fixture that marked everything volatile would pass no matter what the Spring
 * services returned.
 *
 * <h2>Comparison rules</h2>
 *
 * <ol>
 *   <li><b>Shape</b>: the Spring response must have exactly the fixture's key set, recursively. A
 *       renamed or dropped field fails, which is the whole point of a strangler migration.</li>
 *   <li><b>Volatile values</b> are replaced by a marker, so a fixture can still cover rows whose
 *       ids are generated at runtime.</li>
 *   <li><b>Fixed values</b> are asserted as literals.</li>
 * </ol>
 *
 * <p>The first divergence is reported with its full JSON path, because "objects differ" is useless
 * when the object is a sixteen-field inbox row.
 */
public final class LegacyFixtures {

    private static final Path ROOT = Path.of("src", "test", "resources", "fixtures", "legacy");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String fileName;
    private final String name;
    private final String source;
    private final JsonNode response;
    private final List<String> volatileFields;

    private LegacyFixtures(String fileName, String name, String source, JsonNode response,
                           List<String> volatileFields) {
        this.fileName = fileName;
        this.name = name;
        this.source = source;
        this.response = response;
        this.volatileFields = List.copyOf(volatileFields);
    }

    public static LegacyFixtures load(String fileName) {
        Path path = ROOT.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Legacy fixture is missing: " + path.toAbsolutePath());
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            List<String> volatileFields = new ArrayList<>();
            root.path("volatile").forEach(node -> volatileFields.add(node.asText()));
            return new LegacyFixtures(
                fileName,
                root.path("name").asText(fileName),
                root.path("source").asText("unknown"),
                root.path("response"),
                volatileFields);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read legacy fixture " + path, e);
        }
    }

    public String fileName() {
        return fileName;
    }

    public String name() {
        return name;
    }

    public String source() {
        return source;
    }

    public List<String> volatileFields() {
        return volatileFields;
    }

    /** The legacy response, unmodified, for assertions about literal values. */
    public JsonNode raw() {
        return response.deepCopy();
    }

    /**
     * Asserts that {@code actual} has the fixture's shape and its volatile markers.
     *
     * @throws AssertionError with the JSON path of the first divergence
     */
    public void assertMatches(JsonNode actual) {
        JsonNode expected = replaceVolatile(response.deepCopy());
        JsonNode normalised = replaceVolatile(actual.deepCopy());
        compare("$", expected, normalised);
    }

    /** Reads one path out of the legacy response, for a literal-value assertion. */
    public JsonNode at(String pointer) {
        return response.at(pointer);
    }

    // ------------------------------------------------------------------

    private JsonNode replaceVolatile(JsonNode node) {
        if (node.isObject()) {
            for (String field : fieldNames(node)) {
                JsonNode child = node.get(field);
                if (volatileFields.contains(field)) {
                    if (!child.isNull()) {
                        ((ObjectNode) node).put(field, "<volatile>");
                    }
                } else {
                    replaceVolatile(child);
                }
            }
            return node;
        }
        if (node.isArray()) {
            node.forEach(this::replaceVolatile);
        }
        return node;
    }

    private static void compare(String path, JsonNode expected, JsonNode actual) {
        if (expected.isObject()) {
            if (!actual.isObject()) {
                throw new AssertionError(path + ": expected an object but got "
                    + actual.getNodeType());
            }
            // Member order is compared as a SET, not a sequence. A JSON object's member order
            // carries no meaning to a client, and a `jsonb` column does not preserve insertion
            // order at all — so demanding it would be asserting something the storage engine
            // deliberately does not promise. The set is what is a contract: a missing or extra
            // key is a breaking change, and it fails here.
            List<String> expectedFields = new ArrayList<>(fieldNames(expected));
            List<String> actualFields = new ArrayList<>(fieldNames(actual));
            if (!new java.util.TreeSet<>(expectedFields).equals(new java.util.TreeSet<>(actualFields))) {
                throw new AssertionError(path + ": field set differs."
                    + "\n  legacy: " + sorted(expectedFields)
                    + "\n  spring: " + sorted(actualFields));
            }
            for (String field : expectedFields) {
                compare(path + "." + field, expected.get(field), actual.get(field));
            }
            return;
        }
        if (expected.isArray()) {
            if (!actual.isArray()) {
                throw new AssertionError(path + ": expected an array but got "
                    + actual.getNodeType());
            }
            if (expected.size() != actual.size()) {
                throw new AssertionError(path + ": expected " + expected.size()
                    + " elements but got " + actual.size() + " -> " + actual);
            }
            for (int i = 0; i < expected.size(); i++) {
                compare(path + "[" + i + "]", expected.get(i), actual.get(i));
            }
            return;
        }
        if (!expected.equals(actual)) {
            throw new AssertionError(path + ": expected " + expected + " but got " + actual);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    private static List<String> sorted(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        java.util.Collections.sort(copy);
        return copy;
    }

    /** Every fixture file in the directory, for a "none is stale or orphaned" assertion. */
    public static List<String> all() {
        if (!Files.isDirectory(ROOT)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(ROOT)) {
            return files.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".json"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list legacy fixtures", e);
        }
    }
}