package com.krztechnologies.attend.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.stereotype.Service;

/**
 * Application-level encryption (security §6), shared byte format with the worker ({@code crypto.py}), verified by
 * {@code docs/test-vectors/aes-gcm.json}:
 *
 * <pre>"v1" (2 ASCII bytes) | keyId length (1 byte) | keyId (ASCII) | nonce (12 bytes) | ciphertext + 16-byte tag</pre>
 *
 * AES-256-GCM with a random 96-bit nonce per value; the header ("v1", length, keyId) is the additional authenticated
 * data, so it cannot be altered. Keys come from {@code ENCRYPTION_KEY_<ID>} variables (base64 of 32 bytes); the
 * active one is {@code ENCRYPTION_ACTIVE_KEY_ID}. Old keys stay configured for decryption (rotation, RUNBOOK).
 */
@Service
public class CryptoService {

    static final byte[] VERSION = "v1".getBytes(StandardCharsets.US_ASCII);
    static final int NONCE_LENGTH = 12;
    static final int TAG_BITS = 128;
    private static final String KEY_PREFIX = "ENCRYPTION_KEY_";
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private final Map<String, SecretKey> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public CryptoService(
            ConfigurableEnvironment environment, @Value("${ENCRYPTION_ACTIVE_KEY_ID:k1}") String activeKeyId) {
        this(loadKeys(environment), activeKeyId);
    }

    CryptoService(Map<String, SecretKey> keys, String activeKeyId) {
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("ENCRYPTION_ACTIVE_KEY_ID is '" + activeKeyId + "' but " + KEY_PREFIX
                    + activeKeyId + " is not set (run deploy/scripts/init-env).");
        }
        this.keys = Map.copyOf(keys);
        this.activeKeyId = activeKeyId;
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public byte[] encrypt(byte[] plaintext) {
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        return encrypt(plaintext, activeKeyId, nonce);
    }

    /** Deterministic variant for the shared test vectors only. */
    byte[] encrypt(byte[] plaintext, String keyId, byte[] nonce) {
        byte[] header = header(keyId);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(keyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(header.length + nonce.length + sealed.length)
                    .put(header)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] blob) {
        if (blob == null || blob.length < VERSION.length + 2 || !Arrays.equals(blob, 0, 2, VERSION, 0, 2)) {
            throw new CryptoException("Not a v1 encrypted value");
        }
        String keyId = keyIdOf(blob);
        int headerLength = VERSION.length + 1 + keyId.length();
        if (blob.length < headerLength + NONCE_LENGTH + TAG_BITS / 8) {
            throw new CryptoException("Encrypted value is truncated");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, key(keyId), new GCMParameterSpec(TAG_BITS, blob, headerLength, NONCE_LENGTH));
            cipher.updateAAD(blob, 0, headerLength);
            int offset = headerLength + NONCE_LENGTH;
            return cipher.doFinal(blob, offset, blob.length - offset);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Decryption failed (wrong key or tampered data)", e);
        }
    }

    /** The key ID a value was encrypted with (for re-encryption after key rotation). */
    public String keyIdOf(byte[] blob) {
        int length = Byte.toUnsignedInt(blob[VERSION.length]);
        if (blob.length < VERSION.length + 1 + length) {
            throw new CryptoException("Encrypted value is truncated");
        }
        return new String(blob, VERSION.length + 1, length, StandardCharsets.US_ASCII);
    }

    private SecretKey key(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new CryptoException("Unknown encryption key ID '" + keyId + "'");
        }
        return key;
    }

    private static byte[] header(String keyId) {
        byte[] id = keyId.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(VERSION.length + 1 + id.length)
                .put(VERSION)
                .put((byte) id.length)
                .put(id)
                .array();
    }

    /** Every ENCRYPTION_KEY_<ID> from the environment and the imported deploy/.env. */
    static Map<String, SecretKey> loadKeys(ConfigurableEnvironment environment) {
        Map<String, SecretKey> keys = new TreeMap<>();
        for (var source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(KEY_PREFIX) && !keys.containsKey(name.substring(KEY_PREFIX.length()))) {
                        String keyId = name.substring(KEY_PREFIX.length());
                        String value = environment.getProperty(name);
                        if (value != null && !value.isBlank()) {
                            keys.put(keyId, parseKey(name, keyId, value.trim()));
                        }
                    }
                }
            }
        }
        return keys;
    }

    static SecretKey parseKey(String variable, String keyId, String base64) {
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalStateException(variable + ": the key ID must match " + KEY_ID.pattern());
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(variable + " is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(variable + " must be the base64 of exactly 32 bytes");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** Encryption or decryption failed; never carries key material or plaintext. */
    public static final class CryptoException extends RuntimeException {
        CryptoException(String message) {
            super(message);
        }

        CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
