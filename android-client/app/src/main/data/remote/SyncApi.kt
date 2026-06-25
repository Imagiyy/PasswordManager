package com.vaultmanager.app.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit interface for the VaultManager backend API.
 *
 * All endpoints match the API contract exactly.
 * Authentication endpoints do not require Authorization header.
 * Sync and user endpoints require Bearer token.
 */
interface SyncApi {

    // ── Auth Endpoints ──────────────────────────────────────────────

    @POST("/api/auth/prelogin")
    suspend fun prelogin(
        @Body body: PreloginRequest
    ): Response<PreloginResponse>

    @POST("/api/auth/register")
    suspend fun register(
        @Body body: RegisterRequest
    ): Response<RegisterResponse>

    @POST("/api/auth/login")
    suspend fun login(
        @Body body: LoginRequest
    ): Response<LoginResponse>

    @POST("/api/auth/refresh")
    suspend fun refresh(
        @Body body: RefreshRequest
    ): Response<RefreshResponse>

    @POST("/api/auth/logout")
    suspend fun logout(
        @Header("Authorization") auth: String,
        @Body body: LogoutRequest
    ): Response<SuccessResponse>

    // ── Sync Endpoints ──────────────────────────────────────────────

    @GET("/api/sync/pull")
    suspend fun pull(
        @Header("Authorization") auth: String
    ): Response<PullResponse>

    @POST("/api/sync/push")
    suspend fun push(
        @Header("Authorization") auth: String,
        @Body body: PushRequest
    ): Response<PushResponse>

    // ── User Endpoints ──────────────────────────────────────────────

    @GET("/api/user/profile")
    suspend fun getProfile(
        @Header("Authorization") auth: String
    ): Response<ProfileResponse>

    @DELETE("/api/user/account")
    suspend fun deleteAccount(
        @Header("Authorization") auth: String,
        @Body body: DeleteAccountRequest
    ): Response<SuccessResponse>
}

// ── Request Models ──────────────────────────────────────────────────────

data class PreloginRequest(val email: String)

data class RegisterRequest(
    val email: String,
    val auth_key: String,
    val kdf_salt: String
)

data class LoginRequest(
    val email: String,
    val auth_key: String
)

data class RefreshRequest(val refresh_token: String)

data class LogoutRequest(val refresh_token: String)

data class PushRequest(
    val encrypted_blob: String,
    val vector_clock: Map<String, Int>
)

data class DeleteAccountRequest(val auth_key: String)

// ── Response Models ─────────────────────────────────────────────────────

data class PreloginResponse(val kdf_salt: String)

data class RegisterResponse(val user_id: String)

data class LoginResponse(
    val access_token: String,
    val refresh_token: String
)

data class RefreshResponse(val access_token: String)

data class PullResponse(
    val encrypted_blob: String?,
    val vector_clock: Map<String, Int>
)

data class PushResponse(
    val success: Boolean? = null,
    val error: String? = null,
    val message: String? = null,
    val encrypted_blob: String? = null,
    val vector_clock: Map<String, Int>? = null
)

data class ProfileResponse(
    val user_id: String,
    val email: String,
    val created_at: String
)

data class SuccessResponse(val success: Boolean)

data class ErrorResponse(
    val error: String,
    val message: String
)
