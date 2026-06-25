package com.vaultmanager.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore helper for key wrapping and biometric-bound keys.
 *
 * Two Keystore aliases are used:
 *   - "vault_wrap_key":  AES-256 wrapping key (NOT biometric-bound)
 *     Used to wrap/unwrap enc_key for session persistence.
 *   - "vault_bio_key":   AES-256 biometric key (biometric-bound)
 *     Used for biometric unlock — requires user authentication.
 *
 * Key wrapping approach (NOT direct Keystore import):
 *   wrapKey():   Keystore AES-GCM encrypts enc_key → wrapped blob
 *   unwrapKey(): Keystore AES-GCM decrypts wrapped blob → enc_key
 */
object KeystoreHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_LEN = 12

    /**
     * Generate an AES-256 wrapping key in Android Keystore.
     * This key is NOT biometric-bound — used for session key wrapping.
     *
     * @param alias Keystore alias (e.g., "vault_wrap_key")
     */
    fun generateWrappingKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    /**
     * Generate an AES-256 biometric-bound key in Android Keystore.
     * Requires user authentication (biometric) for every use.
     * Invalidated if biometric enrollment changes.
     *
     * @param alias Keystore alias (e.g., "vault_bio_key")
     */
    fun generateBiometricKey(alias: String) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    /**
     * Wrap (encrypt) a raw key using a Keystore AES-GCM key.
     *
     * Returns iv[12] + ciphertext + tag[16], which can be stored
     * in EncryptedSharedPreferences.
     *
     * @param keystoreAlias Keystore alias for the wrapping key
     * @param rawKey        Raw key bytes to wrap (e.g., enc_key)
     * @return Wrapped blob: iv[12] || ciphertext || tag[16]
     */
    fun wrapKey(keystoreAlias: String, rawKey: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val secretKey = keyStore.getKey(keystoreAlias, null) as SecretKey
        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val iv = cipher.iv // GCM generates its own IV
        val ciphertextAndTag = cipher.doFinal(rawKey)

        // Combine: iv[12] + ciphertextAndTag
        val result = ByteArray(iv.size + ciphertextAndTag.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertextAndTag, 0, result, iv.size, ciphertextAndTag.size)

        return result
    }

    /**
     * Unwrap (decrypt) a wrapped key using a Keystore AES-GCM key.
     *
     * @param keystoreAlias Keystore alias for the wrapping key
     * @param wrappedBlob   Blob from wrapKey(): iv[12] || ciphertext || tag[16]
     * @return Unwrapped raw key bytes
     * @throws java.security.KeyStoreException on authentication failure
     */
    fun unwrapKey(keystoreAlias: String, wrappedBlob: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val secretKey = keyStore.getKey(keystoreAlias, null) as SecretKey

        val iv = wrappedBlob.copyOfRange(0, GCM_IV_LEN)
        val ciphertextAndTag = wrappedBlob.copyOfRange(GCM_IV_LEN, wrappedBlob.size)

        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Unwrap a key using a pre-authenticated Cipher from BiometricPrompt.
     *
     * Called inside BiometricPrompt.AuthenticationCallback.onAuthenticationSucceeded
     * with the Cipher from BiometricPrompt.CryptoObject.
     *
     * @param cipher       Authenticated Cipher from BiometricPrompt.CryptoObject
     * @param wrappedBlob  Blob from wrapKey() — but only the ciphertext+tag part
     *                     (IV must have been used to init the cipher already)
     * @return Unwrapped raw key bytes
     */
    fun unwrapKeyWithCipher(cipher: Cipher, wrappedBlob: ByteArray): ByteArray {
        // The caller must have initialized the cipher with the IV from wrappedBlob
        // before passing it to BiometricPrompt. Here we just do the decryption.
        val ciphertextAndTag = wrappedBlob.copyOfRange(GCM_IV_LEN, wrappedBlob.size)
        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Get a BiometricPrompt.CryptoObject for biometric unlock.
     *
     * Initializes a Cipher in DECRYPT_MODE with the biometric-bound Keystore key
     * and the IV from the wrapped blob. The resulting CryptoObject is passed to
     * BiometricPrompt.authenticate().
     *
     * @param keystoreAlias Keystore alias for the biometric key
     * @param wrappedBlob   Wrapped blob containing IV in the first 12 bytes
     * @return CryptoObject wrapping the initialized Cipher
     */
    fun getBiometricCryptoObject(
        keystoreAlias: String,
        wrappedBlob: ByteArray
    ): BiometricPrompt.CryptoObject {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val secretKey = keyStore.getKey(keystoreAlias, null) as SecretKey

        val iv = wrappedBlob.copyOfRange(0, GCM_IV_LEN)

        val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return BiometricPrompt.CryptoObject(cipher)
    }

    /**
     * Check if a Keystore alias exists.
     *
     * @param alias Keystore alias to check
     * @return true if the alias exists in the Keystore
     */
    fun keyExists(alias: String): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.containsAlias(alias)
    }

    /**
     * Delete a key from the Keystore.
     *
     * @param alias Keystore alias to delete
     */
    fun deleteKey(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }
}
