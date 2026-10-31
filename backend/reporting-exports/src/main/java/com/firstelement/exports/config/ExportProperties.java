package com.firstelement.exports.config;

import com.firstelement.config.FirstElementProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code firstelement.exports.*} — the export module's configuration.
 *
 * <h2>Three switches, and two of them are switches, not settings</h2>
 *
 * <ul>
 *   <li><b>{@code signing-key}</b> signs the download links. It is a secret, it comes from the
 *       environment, and {@link ExportConfiguration} refuses to start in PRODUCTION with the
 *       development default. A default that is only refused in production is the correct shape: local
 *       development must run without an operator generating a key, and a production deployment must
 *       not be able to run with a value that is in git.</li>
 *   <li><b>{@code retention.purge-enabled}</b> defaults to {@code false} and
 *       {@link #validateFor} refuses it in PRODUCTION outright. ADR-0009 §4 rule 3: purge "ships
 *       disabled until Phase 7 exposes legal-hold state", and rule 4: no table is purged by a
 *       scheduled job until its retention-class column is populated. Phase 7 now exposes hold state,
 *       so the gate is answerable; the second half is a per-tenant data question, and flipping a
 *       property is not how an operator answers it. The capability therefore ships <b>disabled in
 *       every environment</b>, and turning it on is a deployment change with a rehearsed runbook.</li>
 *   <li><b>{@code storage.root}</b> is where the reference object store writes. The default is a
 *       process-local directory and is documented as such: the bytes are only reachable through the
 *       authenticated, re-authorized redemption endpoint, never through a static file mapping.</li>
 * </ul>
 *
 * <h2>Why no secrets are ever read here</h2>
 *
 * <p>This class never reads a file and never logs a value. The signing key is held in memory for the
 * lifetime of the process and appears in no projection, no audit metadata and no log line — the
 * download event records the signature <em>id</em> and its expiry, which is what "this URL was live
 * until then" needs, and a URL in a database row is a bearer credential with an expiry nobody
 * manages.
 */
@Validated
@ConfigurationProperties(prefix = "firstelement.exports")
public class ExportProperties {

    /**
     * The development signing key. Long enough to satisfy the length rule and obviously not a secret.
     *
     * <p>{@link #validateFor} refuses it in PRODUCTION, so it can never be the key that signs a real
     * download link.
     */
    public static final String DEVELOPMENT_SIGNING_KEY =
        "change-me-to-a-long-random-export-signing-key-min-32-chars";

    /** HMAC key for download-link signatures. Never logged, never persisted. */
    @NotBlank
    @jakarta.validation.constraints.Size(min = 32,
        message = "firstelement.exports.signing-key must be at least 32 characters")
    private String signingKey = DEVELOPMENT_SIGNING_KEY;

    /**
     * How long a minted download link stays live.
     *
     * <p>Short by design: the signature is a bearer credential for a file containing customer data, so
     * its lifetime is bounded and the effective expiry is additionally capped at the artefact's own
     * {@code expiresAt} — the schema requires {@code signatureExpiresAt <= exportExpiresAt} because "a
     * signature outliving the window it was minted for is a second expiry to get wrong".
     *
     * <p>{@code @NotNull}, and that is worth saying because the obvious annotation is wrong here: this is
     * a {@link Duration}, not a string, and {@code @NotBlank} has no validator for a non-{@code CharSequence}.
     * Spring turns that into an {@code UnexpectedTypeException} at <em>context startup</em>, so a module
     * whose unit tests never load a Spring context can carry this defect straight into a deployment that
     * will not start. The same shape was caught in {@code ReportingExportsModule}'s own field.
     */
    @NotNull
    private Duration signatureTtl = Duration.ofMinutes(5);

    /** Object storage for produced artefacts. */
    private final Storage storage = new Storage();

    /** Artefact retention and purge. Ships disabled. */
    private final Retention retention = new Retention();

    /** Bounds on generation. */
    private final Generation generation = new Generation();

    /**
     * Refuses an unsafe configuration at startup.
     *
     * @throws IllegalStateException when a production deployment is left on the development signing
     *         key, or when artefact purge is enabled
     */
    public void validateFor(FirstElementProperties.Environment environment) {
        if (environment == FirstElementProperties.Environment.PRODUCTION
            && DEVELOPMENT_SIGNING_KEY.equals(signingKey)) {
            throw new IllegalStateException(
                "firstelement.exports.signing-key is still the development default while "
                    + "firstelement.environment=PRODUCTION. A download-link signature is a bearer "
                    + "credential for a file of customer data; a published default is not a key.");
        }
        if (retention.isPurgeEnabled()) {
            throw new IllegalStateException(
                "firstelement.exports.retention.purge-enabled must not be true in any environment. "
                    + "ADR-0009 section 4 rule 3 ships purge disabled until legal-hold state exists "
                    + "(it does, from Phase 7) and rule 4 refuses a purge until every artefact carries a "
                    + "declared retention class. Enabling it is a deployment change with a rehearsed "
                    + "runbook, not a property flip.");
        }
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    public Duration getSignatureTtl() {
        return signatureTtl;
    }

    public void setSignatureTtl(Duration signatureTtl) {
        this.signatureTtl = signatureTtl;
    }

    public Storage getStorage() {
        return storage;
    }

    public Retention getRetention() {
        return retention;
    }

    public Generation getGeneration() {
        return generation;
    }

    /**
     * Where produced artefacts are written.
     *
     * <p>The reference implementation is a filesystem store because this migration has no object-store
     * dependency and a local volume is the one that is certainly present. The security properties do
     * not depend on the choice: the key is opaque and tenant-prefixed, nothing serves the directory
     * statically, and the only path to the bytes is a re-authorized redemption. Replacing this with an
     * S3/GCS adapter means implementing {@code ExportObjectStore} and nothing else.
     */
    public static class Storage {

        /**
         * Root directory. A per-tenant subdirectory is created beneath it; a tenant prefix is part of
         * the key so one tenant's listing cannot be assembled from another tenant's ids.
         */
        private String root = Path.of(System.getProperty("java.io.tmpdir"), "firstelement-exports")
            .toString();

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root;
        }
    }

    /**
     * Artefact retention.
     *
     * <p>ADR-0009 §4: export artefacts and their events are retained <b>30 days</b> by default,
     * configurable per tenant. The per-tenant value comes from the tenant's own
     * {@code retention_classes} row through {@code RetentionClassResolver}; this class only carries
     * the fallback used when a tenant has declared nothing, and the switch that keeps the purge off.
     */
    public static class Retention {

        /**
         * The ADR-0009 §4 default window, in days, used only when the tenant's retention class does
         * not state one.
         */
        @Min(1)
        private int defaultWindowDays = 30;

        /**
         * Whether the artefact/object purge may run. Ships {@code false} in every environment and is
         * refused at startup in PRODUCTION by {@link ExportProperties#validateFor}.
         *
         * <p>Not a preference: ADR-0008 §2 rule 3 and ADR-0009 §4 rule 3 both say the purge ships
         * disabled, and a purge that removes a customer's export is not a feature that should be one
         * boolean away from production.
         */
        private boolean purgeEnabled = false;

        /** Artefacts considered by one purge batch. Bounded, because the purge is a batched job. */
        @Min(1)
        private int purgeBatchSize = 50;

        public int getDefaultWindowDays() {
            return defaultWindowDays;
        }

        public void setDefaultWindowDays(int defaultWindowDays) {
            this.defaultWindowDays = defaultWindowDays;
        }

        public boolean isPurgeEnabled() {
            return purgeEnabled;
        }

        public void setPurgeEnabled(boolean purgeEnabled) {
            this.purgeEnabled = purgeEnabled;
        }

        public int getPurgeBatchSize() {
            return purgeBatchSize;
        }

        public void setPurgeBatchSize(int purgeBatchSize) {
            this.purgeBatchSize = purgeBatchSize;
        }
    }

    /**
     * Generation bounds.
     *
     * <p>{@code rowCap} exists because an export with no cap is an unbounded heap risk if a future
     * subject is added that cannot stream. It is a refusal, not a truncation: an export that hit the
     * cap fails with a reason an operator can act on, because a silently truncated financial or
     * clinical extract is worse than no extract.
     */
    public static class Generation {

        /** JDBC fetch size for the streaming read. */
        @Min(1)
        private int fetchSize = 500;

        /** Statement timeout in seconds for the streaming read. */
        @Min(1)
        private int statementTimeoutSeconds = 600;

        /** Hard row ceiling for one export. */
        @Min(1)
        private int rowCap = 2_000_000;

        /** Whether the CSV carries a UTF-8 BOM. Documented in {@code docs/migration/phases/phase-09.md}. */
        private boolean csvBom = true;

        /**
         * The largest DOCX template body an authoring request may carry.
         *
         * <p>A template is a document with placeholders in it, not a data extract, so this is orders of
         * magnitude below {@link #rowCap}: 8 MiB is far larger than any real bilingual cover sheet and far
         * smaller than anything a caller could use to turn an upload endpoint into a memory sink. The refusal
         * names the bound, because an upload that fails with "request too large" and no number is a support
         * ticket rather than a fix.
         */
        @Min(1)
        private int maxTemplateBytes = 8 * 1024 * 1024;

        public int getFetchSize() {
            return fetchSize;
        }

        public void setFetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
        }

        public int getStatementTimeoutSeconds() {
            return statementTimeoutSeconds;
        }

        public void setStatementTimeoutSeconds(int statementTimeoutSeconds) {
            this.statementTimeoutSeconds = statementTimeoutSeconds;
        }

        public int getRowCap() {
            return rowCap;
        }

        public void setRowCap(int rowCap) {
            this.rowCap = rowCap;
        }

        public boolean isCsvBom() {
            return csvBom;
        }

        public void setCsvBom(boolean csvBom) {
            this.csvBom = csvBom;
        }

        public int getMaxTemplateBytes() {
            return maxTemplateBytes;
        }

        public void setMaxTemplateBytes(int maxTemplateBytes) {
            this.maxTemplateBytes = maxTemplateBytes;
        }
    }
}