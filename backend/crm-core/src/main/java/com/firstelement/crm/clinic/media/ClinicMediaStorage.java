package com.firstelement.crm.clinic.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Encrypted private filesystem object store for clinical radiographs, photos, and medical documents.
 * 
 * Features:
 * 1. Path traversal defense: all paths verified to stay strictly inside configured root.
 * 2. AES-256-GCM encryption at rest with unique 96-bit IV per file.
 * 3. Atomic writes using .partial staging files.
 * 4. SHA-256 integrity checksum verification.
 */
@Component
public class ClinicMediaStorage {

    private static final String AES_ALGO = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12; // 96 bits
    private static final int GCM_TAG_LENGTH_BITS = 128; // 128 bits auth tag

    private final Path root;
    private final SecretKey encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ClinicMediaStorage(
            @Value("${clinic.media.storage.root:#{systemProperties['java.io.tmpdir'] + '/firstelement-clinic-media'}}") String storageRoot,
            @Value("${clinic.media.storage.encryption-key:firstelement-clinic-master-aes-key-2026-s7}") String keyString
    ) {
        this.root = Path.of(storageRoot).toAbsolutePath().normalize();
        this.encryptionKey = deriveKey(keyString);
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize clinic media storage root: " + this.root, e);
        }
    }

    public Path getRoot() {
        return root;
    }

    /**
     * Allocates an opaque storage key: {orgId}/{patientId}/{randomUUID}.{ext}
     */
    public String allocateKey(UUID organizationId, UUID patientId, String fileExtension) {
        String cleanExt = (fileExtension != null && fileExtension.startsWith("."))
                ? fileExtension.substring(1)
                : (fileExtension != null ? fileExtension : "bin");
        return organizationId + "/" + patientId + "/" + UUID.randomUUID() + "." + cleanExt;
    }

    /**
     * Encrypts and writes bytes atomically to target storage key.
     * Returns SHA-256 hex digest of the original unencrypted bytes.
     */
    public String writeEncrypted(String storageKey, byte[] plainBytes) throws IOException {
        String sha256 = computeSha256(plainBytes);
        byte[] encryptedPayload = encrypt(plainBytes);

        Path partial = resolvePartialPath(storageKey);
        Path target = resolvePath(storageKey);

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.write(partial, encryptedPayload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return sha256;
    }

    /**
     * Reads and decrypts stored file at storage key.
     */
    public byte[] readDecrypted(String storageKey) throws IOException {
        Path target = resolvePath(storageKey);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            throw new FileNotFoundException("Media file not found at key: " + storageKey);
        }

        byte[] cipherBytes = Files.readAllBytes(target);
        return decrypt(cipherBytes);
    }

    /**
     * Checks if file exists on disk.
     */
    public boolean exists(String storageKey) {
        Path target = resolvePath(storageKey);
        return Files.exists(target) && !Files.isDirectory(target);
    }

    /**
     * Returns physical file size on disk in bytes.
     */
    public long size(String storageKey) {
        Path target = resolvePath(storageKey);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return -1L;
        }
        try {
            return Files.size(target);
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * Deletes physical file and any stray .partial file.
     */
    public boolean deletePhysical(String storageKey) {
        try {
            Path target = resolvePath(storageKey);
            Files.deleteIfExists(target);
        } catch (IOException ignored) {}
        try {
            Path partial = resolvePartialPath(storageKey);
            Files.deleteIfExists(partial);
        } catch (IOException ignored) {}
        return !exists(storageKey);
    }

    /**
     * Path traversal verification.
     */
    public Path resolvePath(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path traversal attempt in storage key: " + storageKey);
        }
        return resolved;
    }

    private Path resolvePartialPath(String storageKey) {
        return resolvePath(storageKey + ".partial");
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, spec);

            byte[] cipherText = cipher.doFinal(plain);

            // Prepend 12-byte IV to ciphertext
            ByteArrayOutputStream out = new ByteArrayOutputStream(iv.length + cipherText.length);
            out.write(iv);
            out.write(cipherText);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("AES-256-GCM encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] payload) {
        try {
            if (payload.length < GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Payload too short for AES-GCM ciphertext");
            }
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(payload, 0, iv, 0, GCM_IV_LENGTH_BYTES);

            int cipherLength = payload.length - GCM_IV_LENGTH_BYTES;
            byte[] cipherText = new byte[cipherLength];
            System.arraycopy(payload, GCM_IV_LENGTH_BYTES, cipherText, 0, cipherLength);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, spec);

            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new IllegalStateException("AES-256-GCM decryption failed", e);
        }
    }

    private static SecretKey deriveKey(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = md.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, AES_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available for key derivation", e);
        }
    }
}
