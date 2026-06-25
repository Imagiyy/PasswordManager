package com.vaultmanager.app.di

import android.content.Context
import com.vaultmanager.app.data.local.VaultDao
import com.vaultmanager.app.data.local.VaultDatabase
import com.vaultmanager.app.data.remote.SyncApi
import com.vaultmanager.app.data.remote.SyncApiClient
import com.vaultmanager.app.repository.VaultRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt dependency injection module.
 *
 * Provides singleton instances of:
 *   - VaultDatabase (Room)
 *   - VaultDao
 *   - SyncApi (Retrofit)
 *   - VaultRepository
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provide Room database singleton.
     */
    @Provides
    @Singleton
    fun provideVaultDatabase(
        @ApplicationContext context: Context
    ): VaultDatabase {
        return VaultDatabase.getInstance(context)
    }

    /**
     * Provide VaultDao from the database.
     */
    @Provides
    @Singleton
    fun provideVaultDao(database: VaultDatabase): VaultDao {
        return database.vaultDao()
    }

    /**
     * Provide Retrofit SyncApi singleton.
     */
    @Provides
    @Singleton
    fun provideSyncApi(): SyncApi {
        return SyncApiClient.create()
    }

    /**
     * Provide VaultRepository singleton.
     */
    @Provides
    @Singleton
    fun provideVaultRepository(
        vaultDao: VaultDao,
        syncApi: SyncApi,
        @ApplicationContext context: Context
    ): VaultRepository {
        return VaultRepository(vaultDao, syncApi, context)
    }
}
