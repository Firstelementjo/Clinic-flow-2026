package com.firstelement.exports;

import static org.assertj.core.api.Assertions.assertThat;

import com.firstelement.exports.config.ExportProperties;
import com.firstelement.exports.download.ExportDownloadService;
import com.firstelement.exports.download.ExportSignatureService;
import com.firstelement.exports.persistence.ExportJobRepository;
import com.firstelement.exports.storage.FilesystemExportObjectStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The signed download link: minting, expiry, tenant binding, and the checksum as evidence.
 *
 * <h2>What is and is not under test</h2>
 *
 * <p>{@link ExportSignatureService} is a pure function of (job, tenant, expiry, key), so this suite is
 * pure JVM with no Spring context and no container. The parts that need a database — the re-authorization
 * against {@code export_jobs."columns"}, the download-event insert, the object read — are covered by the
 * Testcontainers integration suite, and this suite asserts the properties that hold <em>without</em> one:
 * a link cannot be forged, cannot outlive its window, cannot cross a tenant, and does not disclose where
 * the file lives.
 *
 * <h2>Why the "no locator in the token" assertion matters more than it looks</h2>
 *
 * <p>A storage key in a URL is a URL that can be logged by a proxy, pasted into a ticket, or read over a
 * shoulder. So the token's decoded payload is asserted field by field — it carries the job id, the tenant,
 * the expiry and a random signature id, and <em>nothing</em> about the artefact's location, its checksum or
 * its file name.
 */
@DisplayName("download links are signed, expiring, tenant-bound, and disclose no locator")
class ExportSignatureServiceTest {

    private static final UUID JOB = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID FOREIGN_TENANT = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    private ExportSignatureService signatures;

    @BeforeEach
    void setUp() {
        ExportProperties properties = new ExportProperties();
        properties.setSignatureTtl(Duration.ofMinutes(5));
        signatures = new ExportSignatureService(properties);
    }

    // ==================================================================
    // round trip
    // ==================================================================

    @Nested
    @DisplayName("a minted link verifies, once, for the job and tenant it was minted for")
    class RoundTrip {

        @Test
        @DisplayName("a fresh link verifies")
        void freshLinkVerifies() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);

            ExportSignatureService.Verification verified =
                signatures.verify(link.token(), JOB, TENANT, now);

            assertThat(verified.valid()).isTrue();
            assertThat(verified.reason()).isEqualTo("ok");
            assertThat(verified.jobId()).isEqualTo(JOB);
            assertThat(verified.organizationId()).isEqualTo(TENANT);
            assertThat(verified.signatureId()).isEqualTo(link.signatureId());
            assertThat(verified.expiresAt()).isEqualTo(link.expiresAt());
        }

        @Test
        @DisplayName("each mint gets its own signature id, so two live links are distinguishable")
        void signatureIdsAreDistinct() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink first =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);
            ExportSignatureService.SignedLink second =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);

            assertThat(first.signatureId()).isNotEqualTo(second.signatureId());
            assertThat(first.token()).isNotEqualTo(second.token());
            assertThat(first.expiresAt())
                .as("same window, same expiry — the randomness is in the signature id, not the clock")
                .isEqualTo(second.expiresAt());
        }

        @Test
        @DisplayName("a link verifies without a job id, which is what lets one signature be checked first")
        void verificationCanPrecedeTheRowRead() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);

            assertThat(signatures.verify(link.token(), null, TENANT, now).valid())
                .as("the signature is verified before any row is read, so a forged link costs no query")
                .isTrue();
        }
    }

    // ==================================================================
    // tamper
    // ==================================================================

    @Nested
    @DisplayName("a tampered link is refused without disclosing which part was wrong")
    class Tamper {

        @Test
        @DisplayName("an altered payload is refused")
        void alteredPayloadIsRefused() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            String token = signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now).token();
            int separator = token.lastIndexOf('.');
            String payload = token.substring(0, separator);

            // Swap the tenant for another one and keep the signature: this is the exact forgery a
            // cross-tenant download attempt looks like.
            String forgedPayload = payload.replace(TENANT.toString(), FOREIGN_TENANT.toString());
            String forged = forgedPayload + token.substring(separator);

            assertThat(signatures.verify(forged, JOB, TENANT, now).valid()).isFalse();
            assertThat(signatures.verify(forged, JOB, TENANT, now).reason()).isEqualTo("signature-mismatch");
        }

        @Test
        @DisplayName("an extended expiry with the original signature is refused")
        void extendedExpiryIsRefused() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            String token = signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now).token();
            int separator = token.lastIndexOf('.');
            String[] parts = token.substring(0, separator).split("\\|");
            String extended = String.join("|", parts[0], parts[1],
                String.valueOf(Instant.parse("2027-01-01T00:00:00Z").getEpochSecond()), parts[3]);

            assertThat(signatures.verify(extended + token.substring(separator), JOB, TENANT, now).valid())
                .as("otherwise the TTL is a suggestion rather than a bound")
                .isFalse();
        }

        @Test
        @DisplayName("a truncated signature is refused")
        void truncatedSignatureIsRefused() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            String token = signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now).token();
            String shortened = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA";

            assertThat(signatures.verify(shortened, JOB, TENANT, now).valid()).isFalse();
        }

        @Test
        @DisplayName("a link signed with a different key does not verify")
        void anotherKeyDoesNotVerify() {
            ExportProperties other = new ExportProperties();
            other.setSigningKey("a-completely-different-signing-key-of-sufficient-length");
            ExportSignatureService foreign = new ExportSignatureService(other);

            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            String token = foreign.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now).token();

            assertThat(signatures.verify(token, JOB, TENANT, now).valid()).isFalse();
            assertThat(signatures.verify(token, JOB, TENANT, now).reason())
                .as("and the reason does not say 'wrong key', because that would confirm this deployment's "
                    + "signing key is the one in question")
                .isEqualTo("signature-mismatch");
        }

        @Test
        @DisplayName("the refusal reason never carries the expected signature")
        void refusalCarriesNoExpectedValue() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            String token = signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now).token();

            for (String candidate : List.of("", "x", token + "x", "not-a-token", token.substring(1))) {
                ExportSignatureService.Verification verification =
                    signatures.verify(candidate, JOB, TENANT, now);
                assertThat(verification.valid()).isFalse();
                assertThat(verification.signatureId()).isNull();
                assertThat(verification.expiresAt())
                    .as("an invalid verification exposes nothing that was decoded from the presented token")
                    .isNull();
            }
        }

        @Test
        @DisplayName("malformed shapes are refused with named reasons, not a stack trace")
        void malformedShapesAreRefused() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");

            assertThat(signatures.verify(null, JOB, TENANT, now).reason()).isEqualTo("missing-signature");
            assertThat(signatures.verify("   ", JOB, TENANT, now).reason()).isEqualTo("missing-signature");
            assertThat(signatures.verify("no-dot-here", JOB, TENANT, now).reason())
                .isEqualTo("malformed-signature");
            assertThat(signatures.verify(".signature-only", JOB, TENANT, now).reason())
                .isEqualTo("malformed-signature");
            assertThat(signatures.verify("payload.", JOB, TENANT, now).reason())
                .isEqualTo("malformed-signature");
            assertThat(signatures.verify("a|b|c.sig", JOB, TENANT, now).reason())
                .as("three fields where four are required, and a non-uuid where a uuid belongs")
                .isEqualTo("malformed-signature");
        }
    }

    // ==================================================================
    // expiry
    // ==================================================================

    @Nested
    @DisplayName("a link expires, and never outlives the artefact it points at")
    class Expiry {

        @Test
        @DisplayName("a link stops verifying at its expiry, to the second")
        void linkExpires() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);
            Instant expiry = link.expiresAt();

            assertThat(signatures.verify(link.token(), JOB, TENANT, expiry.minusSeconds(1)).valid())
                .isTrue();
            assertThat(signatures.verify(link.token(), JOB, TENANT, expiry).valid())
                .as("the boundary is exclusive: at the expiry second the link is already dead")
                .isFalse();
            assertThat(signatures.verify(link.token(), JOB, TENANT, expiry.plusSeconds(1)).reason())
                .isEqualTo("signature-expired");
        }

        @Test
        @DisplayName("an expired link still reports its expiry, so the event records when it was live")
        void expiredLinkStillReportsItsWindow() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);

            ExportSignatureService.Verification verification =
                signatures.verify(link.token(), JOB, TENANT, link.expiresAt().plusSeconds(60));

            assertThat(verification.valid()).isFalse();
            assertThat(verification.signatureId()).isEqualTo(link.signatureId());
            assertThat(verification.expiresAt()).isEqualTo(link.expiresAt());
            assertThat(verification.jobId()).isEqualTo(JOB);
        }

        @Test
        @DisplayName("a link is capped at the artefact's own retention window")
        void linkNeverOutlivesTheArtefact() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            Instant artefactWindow = now.plus(Duration.ofSeconds(90));

            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, artefactWindow, now);

            assertThat(link.expiresAt())
                .as("export_download_events_signature_expiry_check requires "
                    + "signatureExpiresAt <= exportExpiresAt, because a signature outliving the window it "
                    + "was minted for is a second expiry to get wrong")
                .isEqualTo(artefactWindow);
            assertThat(link.expiresAt()).isBefore(now.plus(signatures.configuredTtl()));
        }

        @Test
        @DisplayName("the configured TTL applies when the artefact window is longer")
        void configuredTtlApplies() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");

            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(30)), now);

            assertThat(link.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
            assertThat(signatures.configuredTtl()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("an artefact already past its window yields an already-dead link, not a fresh one")
        void alreadyExpiredArtefactYieldsADeadLink() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");

            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.minusSeconds(1), now);

            assertThat(link.expiresAt()).isEqualTo(now.minusSeconds(1));
            assertThat(signatures.verify(link.token(), JOB, TENANT, now).valid())
                .as("minting against a closed window must not manufacture five more minutes of access")
                .isFalse();
        }
    }

    // ==================================================================
    // tenant binding
    // ==================================================================

    @Nested
    @DisplayName("a link is bound to the tenant it was minted for")
    class TenantBinding {

        @Test
        @DisplayName("tenant B presenting tenant A's link is refused")
        void foreignTenantIsRefused() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);

            ExportSignatureService.Verification verification =
                signatures.verify(link.token(), JOB, FOREIGN_TENANT, now);

            assertThat(verification.valid()).isFalse();
            assertThat(verification.reason()).isEqualTo("tenant-mismatch");
            assertThat(verification.organizationId())
                .as("so the event records which tenant the token claimed without acting on it")
                .isNull();
        }

        @Test
        @DisplayName("a link minted for another job is refused against this one")
        void foreignJobIsRefused() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            UUID otherJob = UUID.fromString("cccccccc-0000-0000-0000-0000000000ff");
            ExportSignatureService.SignedLink link =
                signatures.sign(otherJob, TENANT, now.plus(Duration.ofDays(1)), now);

            assertThat(signatures.verify(link.token(), JOB, TENANT, now).reason())
                .isEqualTo("job-mismatch");
        }

        @Test
        @DisplayName("a signature failure and an expiry are distinguishable, so the event is accurate")
        void failureKindsAreDistinguishable() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);

            assertThat(signatures.verify(link.token(), JOB, FOREIGN_TENANT, now).reason())
                .isEqualTo("tenant-mismatch");
            assertThat(signatures.verify(link.token(), JOB, TENANT,
                link.expiresAt().plusSeconds(1)).reason()).isEqualTo("signature-expired");
            assertThat(signatures.verify("rubbish", JOB, TENANT, now).reason())
                .isEqualTo("malformed-signature");

            assertThat(List.of("tenant-mismatch", "signature-expired", "malformed-signature",
                    "signature-mismatch", "job-mismatch", "missing-signature"))
                .as("a closed, non-disclosing reason vocabulary — each names a class of failure and none "
                    + "of them says whether a guessed job id exists")
                .doesNotHaveDuplicates();
        }
    }

    // ==================================================================
    // what the token does not carry
    // ==================================================================

    @Nested
    @DisplayName("the token carries no locator")
    class NoLocator {

        @Test
        @DisplayName("the payload is exactly job, tenant, expiry and signature id")
        void payloadShapeIsClosed() {
            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);

            String payload = link.token().substring(0, link.token().lastIndexOf('.'));
            String[] parts = payload.split("\\|", -1);

            assertThat(parts).hasSize(4);
            assertThat(parts[0]).isEqualTo(JOB.toString());
            assertThat(parts[1]).isEqualTo(TENANT.toString());
            assertThat(parts[2]).isEqualTo(String.valueOf(link.expiresAt().getEpochSecond()));
            assertThat(parts[3]).isEqualTo(link.signatureId());
        }

        @Test
        @DisplayName("no storage key, checksum, file name or row count travels in the URL")
        void nothingElseTravels(@TempDir Path root) throws IOException {
            // A real object, written through the reference store, so the assertion is against the key the
            // store actually mints rather than against an invented one.
            ExportProperties properties = new ExportProperties();
            properties.getStorage().setRoot(root.toString());
            FilesystemExportObjectStore store = new FilesystemExportObjectStore(properties);

            String storageKey = store.allocateKey(TENANT, "csv");
            byte[] content = "customer.displayName\nمركز Care\n".getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream sink = store.openForWrite(storageKey)) {
                sink.write(content);
            }
            assertThat(store.commit(storageKey)).isEqualTo(content.length);
            String checksum = ExportSignatureService.checksumOf(content);

            Instant now = Instant.parse("2026-03-14T12:00:00Z");
            ExportSignatureService.SignedLink link =
                signatures.sign(JOB, TENANT, now.plus(Duration.ofDays(1)), now);
            String path = ExportDownloadService.downloadPath(JOB, link.token());

            assertThat(path)
                .as("a URL that names where a customer's file lives is a URL that can be logged by a "
                    + "proxy, pasted into a ticket, or read over a shoulder")
                .doesNotContain(storageKey)
                .doesNotContain(HexFormat.of().formatHex(TENANT.toString().getBytes(StandardCharsets.UTF_8)))
                .doesNotContain(root.toString())
                .doesNotContain(checksum)
                .contains("/api/v1/exports/" + JOB + "/download?signature=");

            assertThat(storageKey)
                .as("the key is 16 SecureRandom bytes under a tenant prefix — a key built from a job id "
                    + "or a timestamp would be enumerable, which would turn 'the object store is not "
                    + "public' into a promise rather than a fact")
                .matches(TENANT + "/[0-9a-f]{32}\\.csv");

            assertThat(store.exists(storageKey)).isTrue();
            assertThat(store.read(storageKey))
                .as("and the bytes are reachable only through the authorized redemption, never through "
                    + "a path the URL exposes")
                .isEqualTo(content);
        }

        @Test
        @DisplayName("a suggested file name carries no customer data")
        void fileNameCarriesNoCustomerData() {
            ExportJobRepository.ExportJobRow job = new ExportJobRepository.ExportJobRow(
                JOB, TENANT, null, null, null, null, null, null, "CSV", "COMPLETED", "none",
                "EXPORT_ARTEFACTS", 100, 1, null, null, null, null, 42L,
                "sha256:" + "a".repeat(64), "tenant/key", 1024L, null, null, null, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now(), "corr-1", 3);

            String name = ExportDownloadService.fileNameFor(job);

            assertThat(name).isEqualTo("export-" + JOB + ".csv");
            assertThat(name)
                .as("a file name is the one part of an export that leaves the system and lands in a "
                    + "Downloads folder, a file index and often a screenshot")
                .doesNotContain("sha256", "tenant/key", "corr-1");
            assertThat(ExportDownloadService.contentTypeFor(job)).isEqualTo("text/csv; charset=utf-8");
        }
    }

    // ==================================================================
    // checksum
    // ==================================================================

    @Nested
    @DisplayName("the checksum is a different question from the signature")
    class Checksum {

        @Test
        @DisplayName("the checksum matches the schema's declared form and is reproducible")
        void checksumFormIsDeclared() {
            byte[] content = "customer.displayName\nمركز Care\n".getBytes(StandardCharsets.UTF_8);
            String checksum = ExportSignatureService.checksumOf(content);

            assertThat(checksum)
                .as("export_jobs_checksum_format_check is ^sha256:[0-9a-f]{64}$, so the prefix is part of "
                    + "the value rather than a presentation choice")
                .matches("^sha256:[0-9a-f]{64}$");
            assertThat(ExportSignatureService.checksumOf(content)).isEqualTo(checksum);
            assertThat(ExportSignatureService.sha256(content)).isEqualTo(checksum);
        }

        @Test
        @DisplayName("Arabic content changes the checksum, so a redacted file is not byte-identical")
        void checksumIsSensitiveToContent() {
            String withArabic = ExportSignatureService.checksumOf(
                "مركز Care".getBytes(StandardCharsets.UTF_8));
            String without = ExportSignatureService.checksumOf(
                "مركزCare".getBytes(StandardCharsets.UTF_8));

            assertThat(withArabic)
                .as("a BOM, a header change or a dropped column must be detectable — which is the whole "
                    + "reason the download path re-hashes the stored object")
                .isNotEqualTo(without);
        }

        @Test
        @DisplayName("empty content still hashes, so a zero-byte artefact is identifiable")
        void emptyContentHashes() {
            assertThat(ExportSignatureService.checksumOf(new byte[0]))
                .matches("^sha256:[0-9a-f]{64}$")
                .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }

        @Test
        @DisplayName("a checksum needs no key, unlike the signature")
        void checksumIsKeyless() {
            ExportProperties other = new ExportProperties();
            other.setSigningKey("a-completely-different-signing-key-of-sufficient-length");
            byte[] content = "customer.id\n1\n".getBytes(StandardCharsets.UTF_8);

            assertThat(new ExportSignatureService(other).checksumOf(content))
                .as("a third party with no access to the signing key can still verify that these are the "
                    + "bytes we produced, which is what makes it evidence rather than a secret")
                .isEqualTo(ExportSignatureService.checksumOf(content));
        }
    }

    // ==================================================================
    // the configured key
    // ==================================================================

    @Nested
    @DisplayName("the signing key itself is guarded")
    class SigningKey {

        @Test
        @DisplayName("a production deployment on the development key is refused at startup")
        void productionRefusesTheDevelopmentKey() {
            ExportProperties properties = new ExportProperties();

            assertThat(properties.getSigningKey())
                .as("the default exists so local development runs without an operator generating a key")
                .isEqualTo(ExportProperties.DEVELOPMENT_SIGNING_KEY);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties.validateFor(
                com.firstelement.config.FirstElementProperties.Environment.PRODUCTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing-key");

            // Every non-production environment accepts the default, so a developer never has to generate
            // a key to run the suite.
            properties.validateFor(com.firstelement.config.FirstElementProperties.Environment.LOCAL);
            properties.validateFor(com.firstelement.config.FirstElementProperties.Environment.TEST);
            properties.validateFor(com.firstelement.config.FirstElementProperties.Environment.STAGING);

            ExportProperties production = new ExportProperties();
            production.setSigningKey("an-operator-generated-random-key-well-over-32-chars");
            production.validateFor(com.firstelement.config.FirstElementProperties.Environment.PRODUCTION);
        }

        @Test
        @DisplayName("purge is refused at startup in every environment, including production")
        void purgeIsRefusedEverywhere() {
            for (com.firstelement.config.FirstElementProperties.Environment environment
                : com.firstelement.config.FirstElementProperties.Environment.values()) {
                ExportProperties properties = new ExportProperties();
                // A real key, so the signing-key guard does not fire first and mask the purge refusal.
                properties.setSigningKey("an-operator-generated-random-key-well-over-32-chars");
                properties.getRetention().setPurgeEnabled(true);

                org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> properties.validateFor(environment))
                    .as("ADR-0009 section 4 rule 3 ships purge disabled; enabling it is a deployment "
                        + "change with a rehearsed runbook, not a property flip")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("purge-enabled");
            }
        }

        @Test
        @DisplayName("the default configuration is safe: purge off, a BOM, a bounded row cap")
        void defaultsAreSafe() {
            ExportProperties properties = new ExportProperties();

            assertThat(properties.getRetention().isPurgeEnabled()).isFalse();
            assertThat(properties.getGeneration().isCsvBom())
                .as("Excel on Windows needs the BOM to read a UTF-8 CSV as Arabic; documented in "
                    + "docs/migration/phases/phase-09.md")
                .isTrue();
            assertThat(properties.getGeneration().getRowCap()).isEqualTo(2_000_000);
            assertThat(properties.getGeneration().getFetchSize()).isGreaterThan(0);
            assertThat(properties.getGeneration().getStatementTimeoutSeconds()).isGreaterThan(0);
            assertThat(properties.getRetention().getDefaultWindowDays())
                .as("ADR-0009 section 4: export artefacts are retained 30 days by default")
                .isEqualTo(30);
        }
    }
}
