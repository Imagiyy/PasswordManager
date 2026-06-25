package com.vaultmanager.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing the cached vault in the local database.
 *
 * Only stores encrypted data — never plaintext or keys.
 *
 * @property id              Auto-generated primary key (always 1 — single vault per user)
 * @property encryptedBlob   Base64(iv[12] + ciphertext + tag[16]) — opaque encrypted vault
 * @property vectorClockJson JSON string of vector clock: { "device_id": counter }
 * @property updatedAt       ISO 8601 timestamp of last update
 */
@Entity(tableName = "vaults")
data class VaultEntity(
    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "encrypted_blob")
    val encryptedBlob: String,

    @ColumnInfo(name = "vector_clock_json")
    val vectorClockJson: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String
)
