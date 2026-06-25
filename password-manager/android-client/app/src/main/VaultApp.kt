package com.vaultmanager.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application subclass for Hilt dependency injection.
 *
 * @HiltAndroidApp triggers code generation for Hilt's component hierarchy,
 * providing dependency injection throughout the application.
 */
@HiltAndroidApp
class VaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Hilt initialization is handled by @HiltAndroidApp annotation
    }
}

/**
 * Singleton vault state accessible from AutofillService.
 *
 * The ViewModel updates this when enc_key is available or cleared.
 * AutofillService reads this to determine if the vault is unlocked.
 *
 * WARNING: enc_key is sensitive — zero it immediately when locking.
 */
object VaultState {
    @Volatile
    var encKey: ByteArray? = null
        set(value) {
            // Zero the old key if being replaced
            field?.fill(0)
            field = value
        }

    @Volatile
    var vaultJson: String? = null

    fun clear() {
        encKey = null  // setter zeros the old value
        vaultJson = null
    }
}
