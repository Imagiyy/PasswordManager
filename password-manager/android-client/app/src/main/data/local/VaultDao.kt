package com.vaultmanager.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for vault CRUD operations.
 *
 * The vault table has at most one row (id = 1) containing the
 * user's encrypted vault blob and vector clock.
 */
@Dao
interface VaultDao {

    /**
     * Get the cached vault (always id = 1).
     *
     * @return VaultEntity or null if no vault is cached
     */
    @Query("SELECT * FROM vaults WHERE id = 1")
    suspend fun getVault(): VaultEntity?

    /**
     * Insert or update the vault (UPSERT).
     * Since the PK is always 1, this replaces the existing row.
     *
     * @param vault VaultEntity to insert/update
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVault(vault: VaultEntity)

    /**
     * Clear the cached vault (delete all rows).
     */
    @Query("DELETE FROM vaults")
    suspend fun clearVault()
}
