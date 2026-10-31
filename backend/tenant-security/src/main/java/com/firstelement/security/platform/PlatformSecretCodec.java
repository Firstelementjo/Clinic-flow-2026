package com.firstelement.security.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM encryption for the platform operator's TOTP shared secret.
 *
 * <h2>Why the secret is encrypted rather than hashed</h2>
 *
 * <p>A TOTP secret must be <em>recoverable</em>: verifying a code recomputes an HMAC over it, so it
 * cannot be stored as a one-way hash the way a password is. It is therefore stored encrypted at rest
 * with the deployment's existing {@code ENCRYPTION_KEY}, and it is never projected by a read model,
 * never logged, and never returned by an endpoint.
 *
 * <h2>Fail-closed, deliberately</h2>
 *
 * <p>An absent or malformed key throws. It does not fall back to treating the stored value as
 * plaintext, because that fallback is exactly how an unconfigured deployment ends up authenticating
 * operators against a value anyone can read in the database. This mirrors the refusal already in
 * {@code ConnectorRegistry.CredentialDecryptor}: an unconfigured decryption path must not quietly
 * succeed.
 *
 * <p>Note the interaction with {@code .env.example}, which now ships {@code ENCRYPTION_KEY} empty.
 * That is intended: an empty key fails loudly here rather than silently encrypting operator secrets
 * under a value published in the repository.
 */
public final class PlatformSecretCodec {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String KEY_PROPERTY = "firstelement.security.encryption-key";

    private final byte[] key;

    public PlatformSecretCodec(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    KEY_PROPERTY + " is not configured. The platform operator TOTP secret cannot be "
                            + "stored without it, and this codec does not fall back to plaintext.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    KEY_PROPERTY + " is not valid base64.", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    KEY_PROPERTY + " must decode to exactly 32 bytes; got " + decoded.length + ".");
        }
        this.key = decoded;
    }

    /** Encrypts with a fresh random IV, returned as {@code base64(iv || ciphertext||tag)}. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the platform secret.", e);
        }
    }

    /** Decrypts a value produced by {@link #encrypt}. Throws rather than returning garbage. */
    public String decrypt(String stored) {
        try {
            byte[] raw = Base64.getDecoder().decode(stored);
            if (raw.length <= IV_BYTES) {
                throw new IllegalStateException("Stored platform secret is truncated.");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] opened = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(opened, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Could not decrypt the platform secret. Has " + KEY_PROPERTY + " changed?", e);
        }
    }
}