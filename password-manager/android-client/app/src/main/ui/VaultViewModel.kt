package com.vaultmanager.app.ui

import android.os.CountDownTimer
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vaultmanager.app.VaultState
import com.vaultmanager.app.crypto.KeystoreHelper
import com.vaultmanager.app.crypto.VaultCrypto
import com.vaultmanager.app.repository.ApiException
import com.vaultmanager.app.repository.PushResult
import com.vaultmanager.app.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Vault ViewModel — manages encryption key lifecycle, vault operations,
 * and idle timer.
 *
 * Security:
 *   - encKey is private var ByteArray? — NEVER in any StateFlow
 *   - Zeroed with .fill(0) on lock or process death
 *   - Never serialized to SavedStateHandle
 *   - Idle timer: 5 minutes → auto-lock
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository
) : ViewModel() {

    private val gson = Gson()

    // ── Encryption Key (never exposed via StateFlow) ────────────────
    private var encKey: ByteArray? = null
    private var accessToken: String? = null

    // ── UI State ────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow<VaultUiState>(VaultUiState.Locked)
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    // ── Events (one-shot) ───────────────────────────────────────────
    private val _events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    // ── Vault Items ─────────────────────────────────────────────────
    private val _vaultItems = MutableStateFlow<List<VaultItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultItem>> = _vaultItems.asStateFlow()

    // ── Sync Status ─────────────────────────────────────────────────
    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    // ── Vector Clock ────────────────────────────────────────────────
    private var vectorClock: MutableMap<String, Int> = mutableMapOf()

    // ── Idle Timer (5 minutes) ──────────────────────────────────────
    private var idleTimer: CountDownTimer? = null
    private val idleTimeoutMs = 300_000L // 5 minutes

    companion object {
        private const val WRAP_KEY_ALIAS = "vault_wrap_key"
        private const val BIO_KEY_ALIAS = "vault_bio_key"
        private const val VAULT_VERSION = 1
    }

    // ── Login ───────────────────────────────────────────────────────

    /**
     * Login flow: prelogin → derive keys → login → pull → decrypt.
     *
     * @param email    User's email address
     * @param password Master password as CharArray (never String)
     */
    fun login(email: String, password: CharArray) {
        viewModelScope.launch {
            _uiState.value = VaultUiState.Loading

            var derivedEncKey: ByteArray? = null
            var derivedAuthKey: ByteArray? = null

            try {
                // Step 1: Prelogin — get KDF salt
                val kdfSaltB64 = repository.prelogin(email)
                val kdfSalt = Base64.decode(kdfSaltB64, Base64.NO_WRAP)

                // Step 2: Derive keys
                val (ek, ak) = VaultCrypto.deriveKeys(password, kdfSalt)
                derivedEncKey = ek
                derivedAuthKey = ak

                // Step 3: Login with auth_key
                val authKeyB64 = Base64.encodeToString(derivedAuthKey, Base64.NO_WRAP)
                val (at, _) = repository.login(email, authKeyB64)
                accessToken = at

                // Step 4: Set enc_key and wrap it for persistence
                encKey = derivedEncKey.copyOf()
                wrapAndStoreKey(derivedEncKey)
                VaultState.encKey = encKey?.copyOf()

                // Step 5: Pull and decrypt vault
                pullAndDecrypt()

                startIdleTimer()
                _uiState.value = VaultUiState.Unlocked

            } catch (e: ApiException) {
                _uiState.value = VaultUiState.Error(e.message ?: "Login failed")
            } catch (e: Exception) {
                _uiState.value = VaultUiState.Error(e.message ?: "Unexpected error")
            } finally {
                derivedAuthKey?.fill(0)
                // Don't zero derivedEncKey — it was copied to encKey
            }
        }
    }

    /**
     * Register and login flow.
     */
    fun registerAndLogin(email: String, password: CharArray) {
        viewModelScope.launch {
            _uiState.value = VaultUiState.Loading

            var derivedEncKey: ByteArray? = null
            var derivedAuthKey: ByteArray? = null

            try {
                // Step 1: Generate KDF salt
                val kdfSalt = VaultCrypto.generateKdfSalt()
                val kdfSaltB64 = Base64.encodeToString(kdfSalt, Base64.NO_WRAP)

                // Step 2: Derive keys
                val (ek, ak) = VaultCrypto.deriveKeys(password, kdfSalt)
                derivedEncKey = ek
                derivedAuthKey = ak

                // Step 3: Register
                val authKeyB64 = Base64.encodeToString(derivedAuthKey, Base64.NO_WRAP)
                repository.register(email, authKeyB64, kdfSaltB64)

                // Step 4: Login
                val (at, _) = repository.login(email, authKeyB64)
                accessToken = at

                // Step 5: Set enc_key
                encKey = derivedEncKey.copyOf()
                wrapAndStoreKey(derivedEncKey)
                VaultState.encKey = encKey?.copyOf()

                // Step 6: Push empty vault
                _vaultItems.value = emptyList()
                vectorClock = mutableMapOf()
                encryptAndPush()

                startIdleTimer()
                _uiState.value = VaultUiState.Unlocked

            } catch (e: ApiException) {
                _uiState.value = VaultUiState.Error(e.message ?: "Registration failed")
            } catch (e: Exception) {
                _uiState.value = VaultUiState.Error(e.message ?: "Unexpected error")
            } finally {
                derivedAuthKey?.fill(0)
            }
        }
    }

    /**
     * Biometric unlock: retrieve wrapped key, unwrap, decrypt cached vault.
     */
    fun biometricUnlock() {
        viewModelScope.launch {
            _uiState.value = VaultUiState.Loading

            try {
                val wrappedKey = repository.getWrappedKey()
                    ?: throw Exception("No wrapped key found — please login with password first")

                val unwrapped = KeystoreHelper.unwrapKey(WRAP_KEY_ALIAS, wrappedKey)
                encKey = unwrapped
                VaultState.encKey = encKey?.copyOf()

                // Try to refresh access token
                try {
                    accessToken = repository.refreshAccessToken()
                } catch (e: Exception) {
                    // Offline mode — use cached vault
                    _syncStatus.value = "Offline — using cached vault"
                }

                // Load from cache
                val cached = repository.getCachedVault()
                if (cached != null) {
                    val (blob, clock) = cached
                    decryptAndLoadVault(blob)
                    vectorClock = clock.toMutableMap()
                }

                startIdleTimer()
                _uiState.value = VaultUiState.Unlocked

            } catch (e: Exception) {
                _uiState.value = VaultUiState.Error(e.message ?: "Biometric unlock failed")
            }
        }
    }

    // ── Vault Operations ────────────────────────────────────────────

    fun saveItem(item: VaultItem) {
        viewModelScope.launch {
            val items = _vaultItems.value.toMutableList()
            val existingIdx = items.indexOfFirst { it.id == item.id }

            if (existingIdx >= 0) {
                items[existingIdx] = item.copy(
                    updatedAt = Instant.now().toString()
                )
            } else {
                items.add(item)
            }

            _vaultItems.value = items
            encryptAndPush()
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            _vaultItems.value = _vaultItems.value.filter { it.id != itemId }
            encryptAndPush()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncStatus.value = "Syncing..."
            try {
                encryptAndPush()
            } catch (e: Exception) {
                _syncStatus.value = "Sync failed: ${e.message}"
            }
        }
    }

    // ── Lock ────────────────────────────────────────────────────────

    fun lock() {
        encKey?.fill(0)
        encKey = null
        VaultState.clear()
        accessToken = null
        _vaultItems.value = emptyList()
        vectorClock.clear()
        cancelIdleTimer()
        _uiState.value = VaultUiState.Locked
        _events.tryEmit(VaultEvent.LockEvent)
    }

    // ── Idle Timer ──────────────────────────────────────────────────

    fun resetIdleTimer() {
        if (encKey != null) {
            startIdleTimer()
        }
    }

    private fun startIdleTimer() {
        cancelIdleTimer()
        idleTimer = object : CountDownTimer(idleTimeoutMs, idleTimeoutMs) {
            override fun onTick(millisUntilFinished: Long) {
                // Not used — single interval
            }

            override fun onFinish() {
                lock()
            }
        }.start()
    }

    private fun cancelIdleTimer() {
        idleTimer?.cancel()
        idleTimer = null
    }

    // ── Internal Helpers ────────────────────────────────────────────

    private suspend fun pullAndDecrypt() {
        val at = accessToken ?: return

        try {
            val (blob, clock) = repository.pullFromServer(at)
            if (blob != null) {
                decryptAndLoadVault(blob)
                vectorClock = clock.toMutableMap()
                repository.saveToCache(blob, clock)
            }
            _syncStatus.value = "Synced just now"
        } catch (e: Exception) {
            _syncStatus.value = "Sync failed: ${e.message}"
            // Try cached vault
            val cached = repository.getCachedVault()
            if (cached != null) {
                decryptAndLoadVault(cached.first)
                vectorClock = cached.second.toMutableMap()
            }
        }
    }

    private fun decryptAndLoadVault(encryptedBlob: String) {
        val key = encKey ?: return
        val plaintext = VaultCrypto.decrypt(key, encryptedBlob)
        val json = String(plaintext, Charsets.UTF_8)
        val vaultData = gson.fromJson(json, VaultData::class.java)
        _vaultItems.value = vaultData.items
        VaultState.vaultJson = json
    }

    private suspend fun encryptAndPush() {
        val key = encKey ?: return
        val at = accessToken ?: return
        val deviceId = repository.getDeviceId()

        // Increment device clock
        vectorClock[deviceId] = (vectorClock[deviceId] ?: 0) + 1

        // Encrypt vault
        val vaultData = VaultData(
            version = VAULT_VERSION,
            items = _vaultItems.value
        )
        val json = gson.toJson(vaultData)
        VaultState.vaultJson = json
        val blob = VaultCrypto.encrypt(key, json.toByteArray(Charsets.UTF_8))

        // Save to cache
        repository.saveToCache(blob, vectorClock)

        // Push to server
        try {
            when (val result = repository.pushToServer(at, blob, vectorClock)) {
                is PushResult.Success -> {
                    _syncStatus.value = "Synced just now"
                }
                is PushResult.Conflict -> {
                    _events.emit(
                        VaultEvent.ConflictEvent(result.serverBlob, result.serverClock)
                    )
                }
            }
        } catch (e: ApiException) {
            if (e.statusCode == 401) {
                // Try to refresh token
                try {
                    accessToken = repository.refreshAccessToken()
                    encryptAndPush() // Retry
                } catch (refreshError: Exception) {
                    _syncStatus.value = "Auth expired — please re-login"
                }
            } else {
                _syncStatus.value = "Sync failed: ${e.message}"
            }
        }
    }

    private fun wrapAndStoreKey(rawKey: ByteArray) {
        if (!KeystoreHelper.keyExists(WRAP_KEY_ALIAS)) {
            KeystoreHelper.generateWrappingKey(WRAP_KEY_ALIAS)
        }
        val wrapped = KeystoreHelper.wrapKey(WRAP_KEY_ALIAS, rawKey)
        repository.saveWrappedKey(wrapped)

        // Also generate biometric key if not exists
        if (!KeystoreHelper.keyExists(BIO_KEY_ALIAS)) {
            try {
                KeystoreHelper.generateBiometricKey(BIO_KEY_ALIAS)
                val bioWrapped = KeystoreHelper.wrapKey(BIO_KEY_ALIAS, rawKey)
                // Store biometric-wrapped key separately
                val encoded = Base64.encodeToString(bioWrapped, Base64.NO_WRAP)
                // Could store in prefs under a different key
            } catch (e: Exception) {
                // Biometric not available — skip
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        lock()
    }
}

// ── Data Classes ────────────────────────────────────────────────────────

/**
 * Vault JSON structure (plaintext inside encrypted blob).
 */
data class VaultData(
    val version: Int,
    val items: List<VaultItem>
)

/**
 * Single credential item in the vault.
 */
data class VaultItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val created_at: String = Instant.now().toString(),
    val updated_at: String = Instant.now().toString()
) {
    // Convenience copy for update
    fun withUpdatedTimestamp(): VaultItem = copy(updated_at = Instant.now().toString())
}

/**
 * UI state for the vault screen.
 */
sealed class VaultUiState {
    data object Loading : VaultUiState()
    data object Unlocked : VaultUiState()
    data object Locked : VaultUiState()
    data class Error(val message: String) : VaultUiState()
}

/**
 * One-shot events emitted by the ViewModel.
 */
sealed class VaultEvent {
    data object LockEvent : VaultEvent()
    data class ConflictEvent(
        val serverBlob: String,
        val serverClock: Map<String, Int>
    ) : VaultEvent()
}
