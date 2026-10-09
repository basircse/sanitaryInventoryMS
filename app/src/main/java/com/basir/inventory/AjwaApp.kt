package com.basir.inventory

import android.app.Application
import android.content.Context
import com.basir.inventory.backup.BackupManager
import com.basir.inventory.data.InventoryRepository
import com.basir.inventory.data.SettingsRepository
import com.basir.inventory.data.db.AppDatabase
import com.basir.inventory.license.LicenseManager
import com.basir.inventory.security.KeystoreSecrets
import com.basir.inventory.ui.Notifications
import com.basir.inventory.util.Fmt
import com.basir.inventory.util.LocalePrefs

class AjwaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Fmt.bangla = LocalePrefs.isBangla(this)
        container = AppContainer(this)
        Notifications.createChannel(this)
    }
}

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppContainer(context: Context) {
    private val secrets = KeystoreSecrets(context)
    val db: AppDatabase = AppDatabase.open(context, secrets)
    val settings = SettingsRepository(db)
    val inventory = InventoryRepository(db)
    val license = LicenseManager(context, db, settings)
    val backupManager = BackupManager(context, db, settings, license, secrets)
}
