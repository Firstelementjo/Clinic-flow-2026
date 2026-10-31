package com.firstelement.exports.download;

import com.firstelement.exports.config.ExportProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies the short-lived signature on a download link.
 *
 * <h2>Why a signed URL is not an authorization decision</h2>
 *
 * <p>FR-8.7 says "reauthorize at job start and download", and the reason is that a signature is a
 * <em>bearer credential for a URL</em>, not a decision about a person. Three failure modes follow from
 * that, and each is closed here:
 *
 * <ol>
 *   <li><b>It does not expire with the permission.</b> A person who loses {@code analytics:read-project}
 *       still holds a live link. {@link #verify} therefore proves the signature is intact; it says nothing
 *       about whether the caller may still have the bytes. That check lives in
 *       {@code ExportDownloadService}, against the authenticated principal.</li>
 *   <li><b>It does not know the tenant.</b> The signature is bound to the job id and the tenant, and
 *       {@link #verify} refuses a token whose embedded tenant differs from the caller's resolved tenant —
 *       so a tenant A link cannot be redeemed by tenant B even if the id is guessed.</li>
 *   <li><b>It outlives the artefact.</b> The effective expiry is {@code min(configured TTL, artefact
 *       expiresAt - now)}, because {@code export_download_events_signature_expiry_check} requires
 *       {@code signatureExpiresAt <= exportExpiresAt} and a signature that outlived its window would be a
 *       second expiry to get wrong.</li>
 * </ol>
 *
 * <h2>What is inside the token, and what is NOT</h2>
 *
 * <p>Inside: the job id, the tenant id, the expiry as an epoch second, and a random signature id. Outside:
 * the storage key, the checksum, the file name, the row count, and the signing key itself. The storage key
 * in particular is the thing that must not travel — it is a locator for a file of customer data, and a
 * URL carrying it is a URL that can be logged, cached in a proxy, pasted into a ticket, or read off a
 * shoulder. The redemption endpoint resolves the key from the row <em>after</em> authorizing the caller.
 *
 * <h2>Constant-time comparison</h2>
 *
 * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}, so a wrong signature does not fail
 * faster on a prefix mismatch and the endpoint cannot be used as an oracle for a partial guess.
 */
@Component
public class ExportSignatureService {

    private static final Logger log = LoggerFactory.getLogger(ExportSignatureService.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final Duration configuredTtl;

    public ExportSignatureService(ExportProperties properties) {
        this.key = properties.getSigningKey().getBytes(StandardCharsets.UTF_8);
        this.configuredTtl = properties.getSignatureTtl();
    }

    /**
     * A minted link.
     *
     * @param signatureId the id recorded on the download event. Never the URL, because a URL in a database
     *                    row is a bearer credential with an expiry nobody manages.
     * @param token       the value the caller must present. Opaque, signed, and carrying nothing about
     *                    the artefact's location.
     * @param expiresAt   the effective expiry, already capped at the artefact's own window
     */
    public record SignedLink(String signatureId, String token, Instant expiresAt, UUID jobId,
                             UUID organizationId) {
    }

    /** The verification outcome. A failure carries a reason and never the expected value. */
    public record Verification(boolean valid, String reason, UUID jobId, UUID organizationId,
                               Instant expiresAt, String signatureId) {

        static Verification invalid(String reason) {
            return new Verification(false, reason, null, null, null, null);
        }
    }

    /**
     * Mints a link.
     *
     * @param artefactExpiresAt the job's own {@code expiresAt}; the signature never outlives it
     */
    public SignedLink sign(UUID jobId, UUID organizationId, Instant artefactExpiresAt, Instant now) {
        Instant expiry = effectiveExpiry(artefactExpiresAt, now);
        String signatureId = UUID.randomUUID().toString();
        String token = buildToken(jobId, organizationId, expiry, signatureId);
        return new SignedLink(signatureId, token, expiry, jobId, organizationId);
    }

    /**
     * The signature's lifetime, capped at the artefact's remaining window.
     *
     * <p>When the artefact expires within the configured TTL the signature expires with it, which is the
     * schema's requirement rather than a nicety: a link that outlives its file is a link whose only
     * remaining function is to tell an attacker when the file was generated.
     */
    public Instant effectiveExpiry(Instant artefactExpiresAt, Instant now) {
        Instant byConfiguration = now.plus(configuredTtl);
        if (artefactExpiresAt == null) {
            return byConfiguration;
        }
        return artefactExpiresAt.isBefore(byConfiguration) ? artefactExpiresAt : byConfiguration;
    }

    /**
     * Verifies a presented token.
     *
     * <p>Order matters and is checked in this sequence: shape, then signature, then expiry, then tenant
     * binding. Verifying the signature before the expiry keeps a stale-but-authentic token from being
     * reported as merely "expired", and reporting <em>which</em> of the two is true is what lets the
     * download event record an accurate reason.
     */
    public Verification verify(String token, UUID expectedJobId, UUID callerOrganizationId, Instant now) {
        if (token == null || token.isBlank()) {
            return Verification.invalid("missing-signature");
        }
        int separator = token.lastIndexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return Verification.invalid("malformed-signature");
        }
        String payload = token.substring(0, separator);
        String presented = token.substring(separator + 1);
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) {
            return Verification.invalid("malformed-signature");
        }
        String jobIdText = parts[0];
        String organizationText = parts[1];
        String expiryText = parts[2];
        String signatureId = parts[3];

        UUID jobId;
        UUID organizationId;
        long expiresAtEpoch;
        try {
            jobId = UUID.fromString(jobIdText);
            organizationId = UUID.fromString(organizationText);
            expiresAtEpoch = Long.parseLong(expiryText);
        } catch (IllegalArgumentException e) {
            return Verification.invalid("malformed-signature");
        }

        String expected = mac(payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            presented.getBytes(StandardCharsets.US_ASCII))) {
            // No distinguishing detail in the message: a caller must not learn whether the job id was
            // right, the tenant was right, or the signature was simply forged.
            return Verification.invalid("signature-mismatch");
        }
        Instant expiresAt = Instant.ofEpochSecond(expiresAtEpoch);
        if (!now.isBefore(expiresAt)) {
            return new Verification(false, "signature-expired", jobId, organizationId, expiresAt,
                signatureId);
        }
        if (expectedJobId != null && !expectedJobId.equals(jobId)) {
            return Verification.invalid("job-mismatch");
        }
        if (callerOrganizationId != null && !callerOrganizationId.equals(organizationId)) {
            // A tenant A link presented by tenant B. Recorded rather than logged with the ids, and the
            // reason deliberately does not say which of the two did not match.
            return Verification.invalid("tenant-mismatch");
        }
        return new Verification(true, "ok", jobId, organizationId, expiresAt, signatureId);
    }

    /** The configured TTL, for the controller's response body. */
    public Duration configuredTtl() {
        return configuredTtl;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private String buildToken(UUID jobId, UUID organizationId, Instant expiresAt, String signatureId) {
        String payload = jobId + "|" + organizationId + "|" + expiresAt.getEpochSecond() + "|"
            + signatureId;
        return payload + "." + mac(payload);
    }

    private String mac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            // A JVM without HMAC-SHA256 cannot sign a download link, and silently degrading to an
            // unsigned one would be exactly the failure this class exists to prevent.
            throw new IllegalStateException("HmacSHA256 is required to sign an export download link", e);
        }
    }

    /**
     * The SHA-256 hex digest of bytes, used for the artefact checksum.
     *
     * <p>Spelled out here rather than reusing the download signature, because they are different
     * questions: the checksum answers "are these the bytes we produced", the signature answers "may this
     * caller have them now". A checksum must be reproducible by a third party with no key.
     */
    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    /**
     * The checksum form the schema accepts.
     *
     * <p>{@code export_jobs_checksum_format_check} is {@code ^sha256:[0-9a-f]{64}$}, so the prefix is
     * part of the value rather than a presentation choice: without it the column could hold any string and
     * "the export is the one we produced" would have nothing to compare against.
     */
    public static String checksumOf(byte[] content) {
        return sha256(content);
    }

    /** Logs the mint without the token. A signed link in a log line is a credential in a log line. */
    public void logMinted(SignedLink link) {
        log.info("export download signature minted signatureId={} job={} tenant={} expiresAt={}",
            link.signatureId(), link.jobId(), link.organizationId(), link.expiresAt());
    }
}