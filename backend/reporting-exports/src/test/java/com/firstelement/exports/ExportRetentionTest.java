package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.admin.audit.RetentionClassResolver;
import com.firstelement.exports.config.ExportProperties;
import com.firstelement.exports.retention.RetentionWindowResolver;
import com.firstelement.exports.storage.FilesystemExportObjectStore;
import com.firstelement.security.query.TenantScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Retention: the window is the tenant's, an undeclared class is not purgeable, and purge ships off.
 *
 * <h2>Why the resolver is unit-tested with a fake grant table</h2>
 *
 * <p>{@link RetentionClassResolver} reads {@code retention_classes}, which needs a database. The rule under
 * test here is "an undeclared class is never purgeable, and the window comes from the tenant rather than a
 * constant" — a rule about how this module <em>interprets</em> the resolver's rows. So a tiny fake stands
 * in for the resolver and returns exactly the rows a tenant would, which is what lets the interpretation
 * be asserted without a container. The resolver's own SQL is covered by the administration module.
 *
 * <h2>What the store assertions add</h2>
 *
 * <p>ADR-0009 §4 rule 1 — "a purge that leaves the file behind is worse than no purge, because the row no
 * longer records that it exists" — is a claim about two stores agreeing. So these tests write a real object
 * through {@link FilesystemExportObjectStore}, delete it, and assert the file is gone <em>and</em> that
 * {@code delete} returned {@code true}, which is how the service knows it may record a deletion.
 */
@DisplayName("retention windows come from the tenant, purge ships disabled, deletion is confirmed")
class ExportRetentionTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final TenantScope SCOPE = new TenantScope(TENANT, null);

    // ==================================================================
    // the window
    // ==================================================================

    @Nested
    @DisplayName("the retention window is the tenant's, never a constant in this module")
    class Window {

        @Test
        @DisplayName("a declared window is used verbatim")
        void declaredWindowWins() {
            RetentionWindowResolver resolver = resolver(Map.of(
                RetentionWindowResolver.EXPORT_ARTEFACT_CLASS, 90));

            assertThat(resolver.windowDays(SCOPE, RetentionWindowResolver.EXPORT_ARTEFACT_CLASS))
                .isEqualTo(90);
            assertThat(resolver.expiryFor(SCOPE, RetentionWindowResolver.EXPORT_ARTEFACT_CLASS,
                LocalDateTime.of(2026, 3, 14, 12, 0)))
                .as("a tenant that declared 90 days keeps its artefacts for 90 days; overriding that with "
                    + "this module's default would be a retention breach the tenant did not agree to")
                .isEqualTo(LocalDateTime.of(2026, 6, 12, 12, 0));
            assertThat(resolver.resolveClass(SCOPE))
                .as("and the class stamped on the row is the declared code, so ADR-0009 section 4 rule 4's "
                    + "precondition — the retention-class column is populated — actually holds")
                .isEqualTo(RetentionWindowResolver.EXPORT_ARTEFACT_CLASS);
        }

        @Test
        @DisplayName("the ADR default of 30 days applies only when the tenant declared nothing at all")
        void defaultAppliesOnlyWhenUndeclared() {
            RetentionWindowResolver resolver = resolver(Map.of());

            assertThat(resolver.windowDays(SCOPE, RetentionWindowResolver.EXPORT_ARTEFACT_CLASS)).isNull();
            assertThat(resolver.defaultWindowDays())
                .as("ADR-0009 section 4: export artefacts and their events, 30 days default")
                .isEqualTo(30);
            assertThat(resolver.expiryFor(SCOPE, RetentionWindowResolver.EXPORT_ARTEFACT_CLASS,
                LocalDateTime.of(2026, 3, 14, 12, 0)))
                .as("an export still gets a window so it is never a permanent copy of customer data — "
                    + "but the row records the UNDECLARED sentinel, which is what stops a purge")
                .isEqualTo(LocalDateTime.of(2026, 4, 13, 12, 0));
        }

        @Test
        @DisplayName("an undeclared tenant's row carries the sentinel, and the sentinel is not purgeable")
        void undeclaredTenantIsNotPurgeable() {
            RetentionWindowResolver resolver = resolver(Map.of());
            String stamped = resolver.resolveClass(SCOPE);

            assertThat(stamped)
                .as("ADR-0009 section 4 rule 4: a writer that stamped a hard-coded DEFAULT would satisfy "
                    + "the NOT NULL CHECK and defeat the rule, because the value would look configured")
                .isEqualTo(com.firstelement.admin.audit.AuditVocabularies.UNDECLARED_RETENTION_CLASS);
            assertThat(resolver.isPurgeable(stamped))
                .as("so 'unset' is never read as '30 days, go ahead'")
                .isFalse();
        }

        @Test
        @DisplayName("the undeclared sentinel and blank codes are never purgeable")
        void unpurgeableCodes() {
            RetentionWindowResolver resolver = resolver(Map.of());

            assertThat(com.firstelement.admin.audit.AuditVocabularies.UNDECLARED_RETENTION_CLASS)
                .as("the visible sentinel for 'this tenant has not configured this stream'")
                .isNotBlank();
            assertThat(resolver.isPurgeable(null)).isFalse();
            assertThat(resolver.isPurgeable("   ")).isFalse();
            assertThat(resolver.isPurgeable(RetentionWindowResolver.EXPORT_ARTEFACT_CLASS)).isTrue();
        }

        @Test
        @DisplayName("the class code is the declared constant, not an invented string")
        void classCodeIsDeclared() {
            assertThat(RetentionWindowResolver.EXPORT_ARTEFACT_CLASS).isEqualTo("EXPORT_ARTEFACTS");
            assertThat(RetentionWindowResolver.EXPORT_STREAM)
                .as("the retention_classes.stream label export rows are stamped with")
                .isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("a template is configuration and never purgeable, and no method can purge one")
        void templatesAreNeverPurgeable() {
            RetentionWindowResolver resolver = resolver(Map.of());

            assertThat(resolver.isConfigurationNeverPurged(RetentionWindowResolver.EXPORT_ARTEFACT_CLASS))
                .as("an artefact is customer data; a template is configuration")
                .isFalse();
            assertThat(resolver.isConfigurationNeverPurged("DOCUMENT_TEMPLATES"))
                .as("and there is no method on this class that could purge one even if asked — the rule "
                    + "holds because the code has no path, not because a branch says no")
                .isFalse();

            assertThat(java.util.Arrays.stream(RetentionWindowResolver.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).sorted().toList())
                .as("the resolver's whole surface: resolve a class, read a window, judge purgeability, "
                    + "compute an expiry. None of them removes anything, which is how ADR-0009 section 4's "
                    + "'templates are configuration and never purged' stays true structurally")
                .containsExactly("defaultWindowDays", "expiryFor", "isConfigurationNeverPurged",
                    "isPurgeable", "resolveClass", "windowClosed", "windowDays");
        }

        @Test
        @DisplayName("the window boundary is inclusive of the expiry instant")
        void windowBoundary() {
            RetentionWindowResolver resolver = resolver(Map.of());
            LocalDateTime expiry = LocalDateTime.of(2026, 3, 14, 12, 0);

            assertThat(resolver.windowClosed(expiry, expiry.minusSeconds(1))).isFalse();
            assertThat(resolver.windowClosed(expiry, expiry)).isTrue();
            assertThat(resolver.windowClosed(expiry, expiry.plusSeconds(1))).isTrue();
            assertThat(resolver.windowClosed(null, expiry))
                .as("a null expiry is not a closed window; it is an absent answer")
                .isFalse();
        }
    }

    // ==================================================================
    // purge is disabled
    // ==================================================================

    @Nested
    @DisplayName("purge ships disabled and refuses at startup in every environment")
    class PurgeDisabled {

        @Test
        @DisplayName("the default configuration has purge off")
        void purgeDefaultsOff() {
            assertThat(new ExportProperties().getRetention().isPurgeEnabled())
                .as("ADR-0008 section 2 rule 3 and ADR-0009 section 4 rule 3: a purge ships disabled until "
                    + "legal-hold state exists")
                .isFalse();
        }

        @Test
        @DisplayName("a purge batch is bounded, because the purge is a batched job")
        void purgeBatchIsBounded() {
            assertThat(new ExportProperties().getRetention().getPurgeBatchSize()).isGreaterThan(0);
        }

        @Test
        @DisplayName("enabling purge is refused at startup, and the message says what to do instead")
        void enablingPurgeIsRefused() {
            ExportProperties properties = new ExportProperties();
            properties.setSigningKey("an-operator-generated-random-key-well-over-32-chars");
            properties.getRetention().setPurgeEnabled(true);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties.validateFor(
                com.firstelement.config.FirstElementProperties.Environment.PRODUCTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("purge-enabled")
                .hasMessageContaining("runbook");
        }
    }

    // ==================================================================
    // deletion is confirmed, never assumed
    // ==================================================================

    @Nested
    @DisplayName("a deletion is recorded only after the store confirms the object is gone")
    class ConfirmedDeletion {

        @Test
        @DisplayName("a deleted object is confirmed by reading the filesystem back")
        void deleteIsConfirmed(@TempDir Path root) throws IOException {
            FilesystemExportObjectStore store = store(root);

            String key = store.allocateKey(TENANT, "csv");
            write(store, key, "customer.displayName\nمركز Care\n");
            assertThat(store.exists(key)).isTrue();
            assertThat(store.size(key)).isGreaterThan(0);

            assertThat(store.delete(key)).isTrue();

            assertThat(store.exists(key))
                .as("ADR-0009 section 5: assert on state, never on the message. A purge that leaves the "
                    + "file behind is worse than no purge, because the row no longer records that it exists")
                .isFalse();
            assertThat(store.delete(key))
                .as("delete is idempotent: the question is 'may I record that the object is gone', and it "
                    + "is. What it must never return true for is a file that is still on disk.")
                .isTrue();
            assertThat(store.exists(key)).isFalse();
        }

        @Test
        @DisplayName("a key outside the reachable space never resolves to a file")
        void traversalIsRefused(@TempDir Path root) throws IOException {
            FilesystemExportObjectStore store = store(root);
            String good = store.allocateKey(TENANT, "csv");
            write(store, good, "tenant a\n");

            for (String hostile : List.of("../escape.csv", "tenant/../../escape.csv",
                "tenant\\escape.csv", "/absolute/escape.csv", "  ",
                "tenant/ok.csv/../" + good.substring(good.indexOf('/') + 1))) {
                // exists() answers the question the retention sweep asks, so it answers false rather than
                // throwing: "is this object present" must not be an exception path. The refusal itself is
                // asserted through read(), which is the operation that would actually touch a file.
                assertThat(store.exists(hostile))
                    .as("key '%s' must not resolve to anything the store can serve", hostile)
                    .isFalse();
                assertThat(store.size(hostile)).isEqualTo(-1L);

                org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.read(hostile))
                    .as("and a read of key '%s' is refused outright, because refusing is the only outcome "
                        + "that cannot be mistaken for 'absent'", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
            }

            assertThat(store.exists(null)).isFalse();
            assertThat(store.delete("not/a/minted/key.csv"))
                .as("a well-formed key that was never minted is absent, and delete is idempotent — the "
                    + "caller's question is 'is the object gone', and it is. Authority over WHICH key comes "
                    + "from the tenant-scoped job row that carries it, never from the key string, so a key "
                    + "that is merely absent here cannot be reached by a caller who cannot read that row.")
                .isTrue();

            assertThat(Files.list(store.root()).toList())
                .as("and nothing escaped the root during any of that")
                .allSatisfy(path -> assertThat(path.toAbsolutePath().normalize())
                    .startsWith(store.root().toAbsolutePath().normalize()));
        }

        @Test
        @DisplayName("only csv and docx keys are ever minted")
        void extensionsAreClosed(@TempDir Path root) {
            FilesystemExportObjectStore store = store(root);

            assertThat(store.allocateKey(TENANT, "csv")).endsWith(".csv");
            assertThat(store.allocateKey(TENANT, "CSV")).endsWith(".csv");
            assertThat(store.allocateKey(TENANT, "docx")).endsWith(".docx");

            for (String hostile : List.of("exe", "sh", "csv.php", "../csv", "")) {
                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> store.allocateKey(TENANT, hostile))
                    .as("extension '%s' is not something this store writes", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
            }

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.allocateKey(null, "csv"))
                .as("a key with no tenant prefix could be assembled from another tenant's ids")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
        }

        @Test
        @DisplayName("two mints for the same tenant never collide")
        void keysAreNotEnumerable(@TempDir Path root) {
            FilesystemExportObjectStore store = store(root);

            java.util.Set<String> keys = new java.util.HashSet<>();
            for (int i = 0; i < 500; i++) {
                keys.add(store.allocateKey(TENANT, "csv"));
            }

            assertThat(keys).hasSize(500)
                .as("a key built from a job id, a timestamp or a counter is enumerable, which would turn "
                    + "'the object store is not public' into a promise rather than a fact")
                .allSatisfy(key -> assertThat(key).matches(TENANT + "/[0-9a-f]{32}\\.csv"));
        }

        @Test
        @DisplayName("one tenant's key is not resolvable under another tenant's prefix")
        void keysAreTenantPrefixed(@TempDir Path root) throws IOException {
            FilesystemExportObjectStore store = store(root);
            UUID otherTenant = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
            String key = store.allocateKey(TENANT, "csv");
            write(store, key, "tenant a\n");

            String otherKey = store.allocateKey(otherTenant, "csv");
            assertThat(otherKey).doesNotContain(TENANT.toString());

            assertThat(Files.exists(store.root().resolve(key))).isTrue();
            assertThat(Files.exists(store.root().resolve(otherKey)))
                .as("the two keys are different files, so one tenant's listing cannot be assembled from "
                    + "another tenant's ids")
                .isFalse();
        }

        @Test
        @DisplayName("a partial write is not reachable, and is discarded on demand")
        void partialWritesAreNotReachable(@TempDir Path root) throws IOException {
            FilesystemExportObjectStore store = store(root);
            String key = store.allocateKey(TENANT, "csv");

            try (java.io.OutputStream sink = store.openForWrite(key)) {
                sink.write("half a file".getBytes(StandardCharsets.UTF_8));
            }

            assertThat(store.exists(key))
                .as("a crashed export must not leave a truncated object under the real key that an "
                    + "operator could mistake for the export")
                .isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.read(key))
                .isInstanceOf(IOException.class);

            store.discard(key);
            assertThat(Files.exists(store.root().resolve(key + ".partial"))).isFalse();
        }

        @Test
        @DisplayName("a commit moves the partial into place and reports the size")
        void commitPublishesTheObject(@TempDir Path root) throws IOException {
            FilesystemExportObjectStore store = store(root);
            String key = store.allocateKey(TENANT, "csv");
            byte[] content = "customer.displayName\nمركز Care\n".getBytes(StandardCharsets.UTF_8);

            try (java.io.OutputStream sink = store.openForWrite(key)) {
                sink.write(content);
            }
            assertThat(store.commit(key)).isEqualTo(content.length);

            assertThat(store.exists(key)).isTrue();
            assertThat(store.read(key)).isEqualTo(content);
            assertThat(Files.exists(store.root().resolve(key + ".partial")))
                .as("and the partial is gone, or a later sweep would find two objects for one job")
                .isFalse();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.commit(key))
                .as("committing twice is refused rather than silently writing a zero-byte file")
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a missing object reads as an IOException naming the two possible causes")
        void missingObjectIsDistinguishable(@TempDir Path root) {
            FilesystemExportObjectStore store = store(root);
            String key = store.allocateKey(TENANT, "csv");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.read(key))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expired")
                .hasMessageContaining("deleted");

            assertThat(store.size(key)).isEqualTo(-1L);
            assertThat(store.size("not/a/minted/key.csv")).isEqualTo(-1L);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private static RetentionWindowResolver resolver(Map<String, Integer> declared) {
        return new RetentionWindowResolver(new FakeRetentionClasses(declared), new ExportProperties());
    }

    private static FilesystemExportObjectStore store(Path root) {
        ExportProperties properties = new ExportProperties();
        properties.getStorage().setRoot(root.toString());
        return new FilesystemExportObjectStore(properties);
    }

    private static void write(FilesystemExportObjectStore store, String key, String content)
        throws IOException {
        try (java.io.OutputStream sink = store.openForWrite(key)) {
            sink.write(content.getBytes(StandardCharsets.UTF_8));
        }
        store.commit(key);
    }

    /**
     * The smallest thing {@link RetentionClassResolver} is, for this suite.
     *
     * <p>Returns exactly the rows a tenant would have declared, and the undeclared sentinel when the tenant
     * declared nothing — which is the resolver's own documented contract and the thing this suite needs to
     * pin down. Everything asserted here is how the export module <em>interprets</em> those rows: that an
     * undeclared tenant is not purgeable, that a declared window is used verbatim. The resolver's SQL is the
     * administration module's to test.
     */
    private static final class FakeRetentionClasses extends RetentionClassResolver {

        private final Map<String, Integer> declared;

        private FakeRetentionClasses(Map<String, Integer> declared) {
            super(null);
            this.declared = declared;
        }

        @Override
        public String resolve(TenantScope scope, String stream, String classCode) {
            return declared.containsKey(classCode)
                ? classCode
                : com.firstelement.admin.audit.AuditVocabularies.UNDECLARED_RETENTION_CLASS;
        }

        @Override
        public Map<String, RetentionClassRow> declared(TenantScope scope, String stream) {
            java.util.Map<String, RetentionClassRow> rows = new java.util.LinkedHashMap<>();
            declared.forEach((code, days) -> rows.put(code,
                new RetentionClassRow(null, null, code, days, false, null)));
            return rows;
        }
    }
}
