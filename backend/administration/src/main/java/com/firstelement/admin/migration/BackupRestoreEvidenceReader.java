package com.firstelement.admin.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Backup and restore evidence, read from the artefacts
 * {@code ops/backup-restore/restore-rehearsal.ps1} writes — never by running it.
 *
 * <h2>Why this reads files and not a table</h2>
 *
 * <p>The Phase-2/7-10a tooling proves a backup can be restored by restoring it into a
 * <b>disposable</b> database and comparing row counts, checksums and a schema fingerprint, then
 * writing the result to an evidence directory ({@code restore-checks.json},
 * {@code source/summary.json}, {@code restored/summary.json}). Those artefacts are the evidence. A
 * table recording "a backup was taken on Tuesday" would be a claim; the directory holds the
 * comparison.
 *
 * <p>So this component is a reader, and the hard rules mirror the script's own:
 *
 * <ul>
 *   <li><b>No shell.</b> There is no process execution anywhere in this class. A restore rehearsal
 *       is an operator action with a disposable target and a rehearsed rollback; an HTTP endpoint
 *       that could start one would be an arbitrary-shell surface with a database behind it.</li>
 *   <li><b>No path from the request.</b> The evidence root is configuration. A request can name a
 *       <em>subdirectory name</em> to look at, and that name is matched against a strict pattern and
 *       then resolved and checked to be inside the root, so {@code ..} and an absolute path are both
 *       refused.</li>
 *   <li><b>Bounded.</b> Each file is read at most {@value #MAX_FILE_BYTES} bytes and only the
 *       three names the script writes are ever opened.</li>
 *   <li><b>Absent is not zero.</b> An unconfigured root, a missing directory and a rehearsal that
 *       failed are three distinct answers, because ADR-0009 §5's rule about asserting on state rather
 *       than on a message applies to a read model too.</li>
 * </ul>
 */
@Component
public class BackupRestoreEvidenceReader {

    private static final Logger log = LoggerFactory.getLogger(BackupRestoreEvidenceReader.class);

    /** The only file names the rehearsal script writes. */
    private static final String CHECKS_FILE = "restore-checks.json";
    private static final String SOURCE_SUMMARY = "source/summary.json";
    private static final String RESTORED_SUMMARY = "restored/summary.json";

    /** Refuse anything larger. An evidence file is a summary, not a dump. */
    private static final long MAX_FILE_BYTES = 512 * 1024L;

    /**
     * A subdirectory name must start with a letter or digit and contain only safe characters.
     *
     * <p>The leading-character requirement is the load-bearing part. A pattern of
     * {@code [A-Za-z0-9._-]+} alone accepts {@code ".."}, because both characters are in the class —
     * so the traversal name walked straight through the name check and was only stopped by the
     * resolve-and-contains assertion below. That is defence in depth doing the work the first check
     * was supposed to do. Requiring an alphanumeric first character rejects {@code ".."} and
     * {@code ".hidden"} outright, and it costs nothing: every rehearsal directory the script writes is
     * named {@code <timestamp>Z} or {@code restore-check}.
     */
    private static final String SAFE_NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private final Path evidenceRoot;
    private final ObjectMapper mapper = new ObjectMapper();

    public BackupRestoreEvidenceReader(
        @Value("${firstelement.admin.evidence-dir:}") String evidenceDir) {
        this.evidenceRoot = evidenceDir == null || evidenceDir.isBlank()
            ? null
            : Path.of(evidenceDir).toAbsolutePath().normalize();
        log.info("backup/restore evidence root: {}", evidenceRoot == null
            ? "NOT CONFIGURED (firstelement.admin.evidence-dir is empty)"
            : evidenceRoot);
    }

    /** Whether an evidence directory has been configured at all. */
    public boolean configured() {
        return evidenceRoot != null;
    }

    /**
     * One rehearsal's evidence.
     *
     * @param status CONFIGURED, NO_EVIDENCE, UNREADABLE or UNSAFE_PATH — never a boolean, because
     *               "we could not read it" and "there is none" are the two answers an operator must
     *               not confuse
     * @param checks the parsed {@code restore-checks.json}, or empty
     * @param found  which of the three artefacts were actually present
     */
    public record RehearsalEvidence(String status,
                                    String name,
                                    Map<String, Object> checks,
                                    Map<String, Object> sourceSummary,
                                    Map<String, Object> restoredSummary,
                                    List<String> found,
                                    String message) {
    }

    /**
     * Lists the rehearsal directories present, newest name first.
     *
     * <p>Directory names only. Nothing inside is opened until an operator asks for one by name, so
     * listing cannot become a directory traversal primitive.
     */
    public List<String> listRehearsals() {
        if (evidenceRoot == null) {
            return List.of();
        }
        if (!Files.isDirectory(evidenceRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(evidenceRoot)) {
            List<String> names = new ArrayList<>();
            entries.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.matches(SAFE_NAME))
                .sorted()
                .toList().stream()
                .map(String::toString)
                .forEach(names::add);
            names.sort(java.util.Collections.reverseOrder());
            return List.copyOf(names);
        } catch (IOException e) {
            log.warn("Could not list the backup/restore evidence directory: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Reads one rehearsal's evidence.
     *
     * @param name a directory name from {@link #listRehearsals()}; anything else is refused before a
     *             filesystem call
     */
    public RehearsalEvidence read(String name) {
        // The name is validated BEFORE the root is consulted, and that order is deliberate. Checking
        // the root first would mean an unsafe path is only rejected in deployments that happen to have
        // an evidence root, which makes "no path from the request" a property of configuration rather
        // than a property of this method. Validating the shape first means the traversal rule holds
        // unconditionally, and the unconfigured-root answer is then still reported — for a name that
        // could never have been read anyway.
        if (name == null || !name.matches(SAFE_NAME)) {
            return new RehearsalEvidence("UNSAFE_PATH", name, Map.of(), Map.of(), Map.of(), List.of(),
                "A rehearsal name must start with a letter or digit and match " + SAFE_NAME + ". A "
                    + "path, a traversal segment, a hidden name and a slash are all refused before "
                    + "any filesystem call.");
        }
        if (evidenceRoot == null) {
            return new RehearsalEvidence("NO_EVIDENCE_ROOT", null, Map.of(), Map.of(), Map.of(),
                List.of(),
                "firstelement.admin.evidence-dir is not configured, so no restore evidence can be "
                    + "read from here. This is a configuration gap, not a failed rehearsal.");
        }
        Path directory = evidenceRoot.resolve(name).normalize();
        // Belt and braces: the pattern already excludes separators, so this can only fail if the
        // resolved path somehow escaped. Asserted rather than assumed.
        if (!directory.startsWith(evidenceRoot)) {
            return new RehearsalEvidence("UNSAFE_PATH", name, Map.of(), Map.of(), Map.of(), List.of(),
                "The resolved path is outside the configured evidence root; refusing.");
        }
        if (!Files.isDirectory(directory)) {
            return new RehearsalEvidence("NO_EVIDENCE", name, Map.of(), Map.of(), Map.of(), List.of(),
                "No evidence directory with that name exists. That is not the same as a rehearsal "
                    + "that passed.");
        }

        List<String> found = new ArrayList<>();
        Map<String, Object> checks = readOne(directory, CHECKS_FILE, found);
        Map<String, Object> source = readOne(directory, SOURCE_SUMMARY, found);
        Map<String, Object> restored = readOne(directory, RESTORED_SUMMARY, found);
        String status = found.isEmpty() ? "NO_EVIDENCE" : "CONFIGURED";
        String message = found.isEmpty()
            ? "The directory exists but holds none of the three artefacts the rehearsal script "
                + "writes, so no comparison can be shown."
            : "Read " + found.size() + " artefact(s) read-only from the evidence directory.";
        return new RehearsalEvidence(status, name, checks, source, restored, List.copyOf(found),
            message);
    }

    /** The most recent rehearsal's evidence, for a dashboard that wants one answer. */
    public RehearsalEvidence latest() {
        if (evidenceRoot == null) {
            return new RehearsalEvidence("NO_EVIDENCE_ROOT", null, Map.of(), Map.of(), Map.of(),
                List.of(),
                "firstelement.admin.evidence-dir is not configured, so no restore evidence can be "
                    + "read from here. This is a configuration gap, not a failed rehearsal and not "
                    + "an absence of rehearsals.");
        }
        List<String> names = listRehearsals();
        // A configured root with no rehearsal directory is its own answer. Returning
        // NO_EVIDENCE_ROOT here — which is what this did — would tell an operator their evidence
        // directory is unset when it is set and empty, which is the opposite of the same problem.
        return names.isEmpty()
            ? new RehearsalEvidence("NO_REHEARSALS", null, Map.of(), Map.of(), Map.of(), List.of(),
                "The evidence root is configured and contains no rehearsal directory. That is a "
                    + "different situation from an unconfigured root: the tooling has run here, or "
                    + "has not, and the directory is where the answer would be.")
            : read(names.get(0));
    }

    /**
     * When the evidence root itself was last modified.
     *
     * <p>Used by the overview to answer "how fresh is the proof". Returned as an epoch-milli value
     * rather than a formatted string so the caller decides the timezone, which matters because every
     * stored timestamp in this system is UTC.
     */
    public Long rootLastModifiedEpochMilli() {
        if (evidenceRoot == null || !Files.exists(evidenceRoot)) {
            return null;
        }
        try {
            return Files.getLastModifiedTime(evidenceRoot).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    /** ISO-8601 rendering of the root's last modification, for a human-readable read model. */
    public String rootLastModified() {
        Long millis = rootLastModifiedEpochMilli();
        return millis == null ? null : Instant.ofEpochMilli(millis).toString();
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private Map<String, Object> readOne(Path directory, String relative, List<String> found) {
        Path file = directory.resolve(relative).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                log.warn("Refusing to read oversized evidence file {} ({} bytes)", file, size);
                return Map.of();
            }
            JsonNode parsed = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            found.add(relative);
            return toMap(parsed);
        } catch (IOException e) {
            log.warn("Could not read the restore-evidence file {}: {}", file, e.toString());
            return Map.of();
        }
    }

    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                out.put(entry.getKey(), null);
            } else if (value.isArray()) {
                List<Object> items = new ArrayList<>();
                value.forEach(item -> items.add(item.isNull() ? null
                    : item.isValueNode() ? item.asText() : item.toString()));
                out.put(entry.getKey(), items);
            } else if (value.isValueNode()) {
                out.put(entry.getKey(), value.asText());
            } else {
                out.put(entry.getKey(), value.toString());
            }
        });
        return out;
    }
}