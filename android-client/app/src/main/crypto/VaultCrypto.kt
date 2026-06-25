package com.vaultmanager.app.crypto

import android.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-knowledge vault cryptography for the Android client.
 *
 * Key derivation: Argon2id → HKDF-SHA256 (two-key design)
 * Encryption:     AES-256-GCM with 12-byte IV and 128-bit auth tag
 *
 * Argon2id parameters (MUST match across all clients):
 *   m = 65536 KiB (64 MiB)
 *   t = 3 iterations
 *   p = 4 parallelism
 *   hash_len = 32 bytes
 *
 * All keys are ByteArray. Caller MUST call .fill(0) when done.
 */
object VaultCrypto {

    // ── Constants (must match across all clients) ────────────────────

    private const val ARGON2_MEMORY_COST = 65536   // KiB (64 MiB)
    private const val ARGON2_TIME_COST = 3
    private const val ARGON2_PARALLELISM = 4
    private const val ARGON2_HASH_LEN = 32         // bytes

    private val HKDF_ENC_KEY_INFO = "enc_key".toByteArray(Charsets.UTF_8)
    private val HKDF_AUTH_KEY_INFO = "auth_key".toByteArray(Charsets.UTF_8)
    private const val HKDF_KEY_LEN = 32  // bytes

    private const val AES_IV_LEN = 12    // bytes — GCM standard nonce
    private const val AES_TAG_BITS = 128 // bits — 16 bytes
    private const val KDF_SALT_LEN = 16  // bytes

    private const val AES_ALGORITHM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    /**
     * Derive enc_key and auth_key from a master password and KDF salt.
     *
     * Steps:
     *   1. Argon2id(password, kdf_salt) → master_key (32 bytes)
     *   2. HKDF-SHA256(master_key, info=b"enc_key") → enc_key (32 bytes)
     *   3. HKDF-SHA256(master_key, info=b"auth_key") → auth_key (32 bytes)
     *   4. Zero master_key immediately
     *
     * @param password  The user's master password as CharArray (never String)
     * @param kdfSalt   16-byte KDF salt from prelogin
     * @return Pair(encKey, authKey) — caller MUST .fill(0) both when done
     */
    fun deriveKeys(password: CharArray, kdfSalt: ByteArray): Pair<ByteArray, ByteArray> {
        // Convert CharArray to ByteArray (UTF-8)
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        var masterKey = ByteArray(ARGON2_HASH_LEN)

        try {
            // Step 1: Argon2id — derive master_key
            val argon2 = Argon2Kt()
            val hashResult = argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = passwordBytes,
                salt = kdfSalt,
                tCostInIterations = ARGON2_TIME_COST,
                mCostInKibibyte = ARGON2_MEMORY_COST,
                parallelism = ARGON2_PARALLELISM,
                hashLengthInBytes = ARGON2_HASH_LEN
            )
            masterKey = hashResult.rawHashAsByteArray()

            // Step 2: HKDF-SHA256 — derive enc_key
            val encKey = hkdfDerive(masterKey, HKDF_ENC_KEY_INFO, HKDF_KEY_LEN)

            // Step 3: HKDF-SHA256 — derive auth_key
            val authKey = hkdfDerive(masterKey, HKDF_AUTH_KEY_INFO, HKDF_KEY_LEN)

            return Pair(encKey, authKey)
        } finally {
            // Always zero master_key and password bytes
            masterKey.fill(0)
            passwordBytes.fill(0)
        }
    }

    /**
     * HKDF-SHA256 key derivation using BouncyCastle.
     *
     * @param ikm    Input keying material
     * @param info   Context/application-specific info
     * @param length Output key length in bytes
     * @return Derived key bytes
     */
    private fun hkdfDerive(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        // Empty salt per spec
        hkdf.init(HKDFParameters(ikm, ByteArray(0), info))
        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     *
     * Generates a fresh 12-byte IV for every call (NEVER reuse an IV).
     * Returns base64(iv[12] + ciphertext[N] + tag[16]).
     *
     * @param encKey    32-byte encryption key
     * @param plaintext Data to encrypt
     * @return Base64-encoded blob: iv[12] || ciphertext[N] || tag[16]
     */
    fun encrypt(encKey: ByteArray, plaintext: ByteArray): String {
        // Generate fresh random IV — NEVER reuse with the same key
        val iv = ByteArray(AES_IV_LEN)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        val keySpec = SecretKeySpec(encKey, "AES")
        val gcmSpec = GCMParameterSpec(AES_TAG_BITS, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertextAndTag = cipher.doFinal(plaintext)

        // Wire format: iv[12] + ciphertext[N] + tag[16]
        // Note: Java's GCM appends the tag to the ciphertext automatically
        val blob = ByteArray(AES_IV_LEN + ciphertextAndTag.size)
        System.arraycopy(iv, 0, blob, 0, AES_IV_LEN)
        System.arraycopy(ciphertextAndTag, 0, blob, AES_IV_LEN, ciphertextAndTag.size)

        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    /**
     * Decrypt an AES-256-GCM encrypted blob.
     *
     * Parses base64(iv[12] + ciphertext[N] + tag[16]).
     * ALWAYS verifies the auth tag — throws AEADBadTagException on failure.
     *
     * @param encKey  32-byte encryption key
     * @param blob    Base64-encoded blob from encrypt()
     * @return Decrypted plaintext bytes
     * @throws javax.crypto.AEADBadTagException if auth tag verification fails
     * @throws IllegalArgumentException if blob is too short
     */
    fun decrypt(encKey: ByteArray, blob: String): ByteArray {
        val blobBytes = Base64.decode(blob, Base64.NO_WRAP)

        require(blobBytes.size >= AES_IV_LEN + AES_TAG_BITS / 8) {
            "Encrypted blob too short: ${blobBytes.size} bytes " +
                "(minimum ${AES_IV_LEN + AES_TAG_BITS / 8} for IV + tag)"
        }

        // Split: iv[12] + ciphertextAndTag[N+16]
        val iv = blobBytes.copyOfRange(0, AES_IV_LEN)
        val ciphertextAndTag = blobBytes.copyOfRange(AES_IV_LEN, blobBytes.size)

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        val keySpec = SecretKeySpec(encKey, "AES")
        val gcmSpec = GCMParameterSpec(AES_TAG_BITS, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        // doFinal verifies the tag and throws AEADBadTagException on failure
        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Generate a 16-byte KDF salt using SecureRandom (CSPRNG).
     *
     * @return 16 random bytes for use as Argon2id salt
     */
    fun generateKdfSalt(): ByteArray {
        val salt = ByteArray(KDF_SALT_LEN)
        secureRandom.nextBytes(salt)
        return salt
    }
}
