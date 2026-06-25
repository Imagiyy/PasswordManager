package com.vaultmanager.app.repository

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vaultmanager.app.crypto.VaultCrypto
import com.vaultmanager.app.data.local.VaultDao
import com.vaultmanager.app.data.local.VaultEntity
import com.vaultmanager.app.data.remote.*
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vault repository — offline-first with server sync.
 *
 * Reads from Room (local cache), syncs with server on connect.
 * Handles 409 conflict detection and reports conflicts to the ViewModel.
 *
 * Stores device_id and refresh_token in EncryptedSharedPreferences.
 */
@Singleton
class VaultRepository @Inject constructor(
    private val vaultDao: VaultDao,
    private val syncApi: SyncApi,
    private val context: Context
) {
    private val gson = Gson()

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "vault_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Device ID ───────────────────────────────────────────────────

    /**
     * Get or generate a unique device ID (UUID v4).
     * Stored in EncryptedSharedPreferences.
     */
    fun getDeviceId(): String {
        val existing = encryptedPrefs.getString("device_id", null)
        if (existing != null) return existing

        val deviceId = UUID.randomUUID().toString()
        encryptedPrefs.edit().putString("device_id", deviceId).apply()
        return deviceId
    }

    // ── Token Management ────────────────────────────────────────────

    fun saveRefreshToken(token: String) {
        encryptedPrefs.edit().putString("refresh_token", token).apply()
    }

    fun getRefreshToken(): String? {
        return encryptedPrefs.getString("refresh_token", null)
    }

    fun clearRefreshToken() {
        encryptedPrefs.edit().remove("refresh_token").apply()
    }

    /**
     * Save the wrapped enc_key blob in EncryptedSharedPreferences.
     * This is the Keystore-wrapped key, not the raw key.
     */
    fun saveWrappedKey(wrappedKey: ByteArray) {
        val encoded = Base64.encodeToString(wrappedKey, Base64.NO_WRAP)
        encryptedPrefs.edit().putString("wrapped_enc_key", encoded).apply()
    }

    fun getWrappedKey(): ByteArray? {
        val encoded = encryptedPrefs.getString("wrapped_enc_key", null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    fun clearWrappedKey() {
        encryptedPrefs.edit().remove("wrapped_enc_key").apply()
    }

    // ── Local Vault Operations ──────────────────────────────────────

    /**
     * Get the cached vault from Room.
     *
     * @return Pair(encryptedBlob, vectorClock) or null if no cache
     */
    suspend fun getCachedVault(): Pair<String, Map<String, Int>>? {
        val entity = vaultDao.getVault() ?: return null
        if (entity.encryptedBlob.isEmpty()) return null

        val clockType = object : TypeToken<Map<String, Int>>() {}.type
        val clock: Map<String, Int> = gson.fromJson(entity.vectorClockJson, clockType)
        return Pair(entity.encryptedBlob, clock)
    }

    /**
     * Save encrypted vault to Room cache.
     */
    suspend fun saveToCache(encryptedBlob: String, vectorClock: Map<String, Int>) {
        val entity = VaultEntity(
            id = 1,
            encryptedBlob = encryptedBlob,
            vectorClockJson = gson.toJson(vectorClock),
            updatedAt = Instant.now().toString()
        )
        vaultDao.upsertVault(entity)
    }

    /**
     * Clear the local vault cache.
     */
    suspend fun clearCache() {
        vaultDao.clearVault()
    }

    // ── Remote API Operations ───────────────────────────────────────

    /**
     * Call prelogin to get KDF salt for the given email.
     *
     * @return Base64-encoded kdf_salt
     * @throws ApiException on failure
     */
    suspend fun prelogin(email: String): String {
        val response = syncApi.prelogin(PreloginRequest(email))
        if (response.isSuccessful) {
            return response.body()!!.kdf_salt
        }
        throw parseApiError(response)
    }

    /**
     * Register a new account.
     *
     * @return User UUID
     * @throws ApiException on failure
     */
    suspend fun register(email: String, authKeyB64: String, kdfSaltB64: String): String {
        val response = syncApi.register(RegisterRequest(email, authKeyB64, kdfSaltB64))
        if (response.isSuccessful) {
            return response.body()!!.user_id
        }
        throw parseApiError(response)
    }

    /**
     * Login with email and auth_key.
     *
     * @return Pair(accessToken, refreshToken)
     * @throws ApiException on failure
     */
    suspend fun login(email: String, authKeyB64: String): Pair<String, String> {
        val response = syncApi.login(LoginRequest(email, authKeyB64))
        if (response.isSuccessful) {
            val body = response.body()!!
            saveRefreshToken(body.refresh_token)
            return Pair(body.access_token, body.refresh_token)
        }
        throw parseApiError(response)
    }

    /**
     * Refresh access token using stored refresh token.
     *
     * @return New access token
     * @throws ApiException on failure
     */
    suspend fun refreshAccessToken(): String {
        val refreshToken = getRefreshToken()
            ?: throw ApiException("No refresh token available", "INVALID_REFRESH_TOKEN", 401)

        val response = syncApi.refresh(RefreshRequest(refreshToken))
        if (response.isSuccessful) {
            return response.body()!!.access_token
        }

        // Clear stored token if it's invalid
        if (response.code() == 401) {
            clearRefreshToken()
        }
        throw parseApiError(response)
    }

    /**
     * Pull encrypted vault from server.
     *
     * @return Pair(encryptedBlob, vectorClock) — blob is null if no vault exists
     * @throws ApiException on failure
     */
    suspend fun pullFromServer(accessToken: String): Pair<String?, Map<String, Int>> {
        val response = syncApi.pull("Bearer $accessToken")
        if (response.isSuccessful) {
            val body = response.body()!!
            return Pair(body.encrypted_blob, body.vector_clock)
        }
        throw parseApiError(response)
    }

    /**
     * Push encrypted vault to server.
     *
     * @return PushResult: Success or Conflict with server state
     * @throws ApiException on failure
     */
    suspend fun pushToServer(
        accessToken: String,
        encryptedBlob: String,
        vectorClock: Map<String, Int>
    ): PushResult {
        val response = syncApi.push(
            "Bearer $accessToken",
            PushRequest(encryptedBlob, vectorClock)
        )

        return when (response.code()) {
            200 -> PushResult.Success
            409 -> {
                // Parse conflict response
                val errorBody = response.errorBody()?.string()
                if (errorBody != null) {
                    val conflictData = gson.fromJson(errorBody, PushResponse::class.java)
                    PushResult.Conflict(
                        serverBlob = conflictData.encrypted_blob ?: "",
                        serverClock = conflictData.vector_clock ?: emptyMap()
                    )
                } else {
                    throw ApiException("Sync conflict with no data", "SYNC_CONFLICT", 409)
                }
            }
            else -> throw parseApiError(response)
        }
    }

    /**
     * Logout — revoke refresh token on server.
     */
    suspend fun logout(accessToken: String) {
        val refreshToken = getRefreshToken() ?: return
        try {
            syncApi.logout("Bearer $accessToken", LogoutRequest(refreshToken))
        } finally {
            clearRefreshToken()
        }
    }

    // ── Error Handling ──────────────────────────────────────────────

    private fun parseApiError(response: retrofit2.Response<*>): ApiException {
        val errorBody = response.errorBody()?.string()
        return if (errorBody != null) {
            try {
                val error = gson.fromJson(errorBody, ErrorResponse::class.java)
                ApiException(error.message, error.error, response.code())
            } catch (e: Exception) {
                ApiException(errorBody, "UNKNOWN", response.code())
            }
        } else {
            ApiException("HTTP ${response.code()}", "UNKNOWN", response.code())
        }
    }
}

/**
 * Result of a push operation.
 */
sealed class PushResult {
    data object Success : PushResult()
    data class Conflict(
        val serverBlob: String,
        val serverClock: Map<String, Int>
    ) : PushResult()
}

/**
 * API error exception with error code and HTTP status.
 */
class ApiException(
    message: String,
    val errorCode: String,
    val statusCode: Int
) : Exception(message)
