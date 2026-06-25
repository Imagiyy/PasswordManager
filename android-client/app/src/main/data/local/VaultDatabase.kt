package com.vaultmanager.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database singleton for the VaultManager app.
 *
 * Version 1 — initial schema with a single vaults table.
 * The database stores ONLY encrypted data — never plaintext or keys.
 */
@Database(
    entities = [VaultEntity::class],
    version = 1,
    exportSchema = true
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    companion object {
        private const val DATABASE_NAME = "vault_database"

        @Volatile
        private var INSTANCE: VaultDatabase? = null

        /**
         * Get or create the database singleton.
         *
         * @param context Application context
         * @return VaultDatabase singleton instance
         */
        fun getInstance(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
