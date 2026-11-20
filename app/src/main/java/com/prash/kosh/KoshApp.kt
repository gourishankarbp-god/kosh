package com.prash.kosh

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.prash.kosh.backup.BackupManager
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.Repository
import com.prash.kosh.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple manual dependency container; one user, one process, no DI framework needed. */
open class KoshApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    open val db: AppDatabase by lazy { AppDatabase.create(this) }
    val repo: Repository by lazy {
        Repository(db, com.prash.kosh.notify.Notifications(this)).also { r ->
            r.thresholds = { settings.autoSaveThreshold to minOf(settings.reviewThreshold, settings.autoSaveThreshold) }
        }
    }
    val settings: Settings by lazy { Settings(this) }
    val backup: BackupManager by lazy { BackupManager(this, db) }
    val sync: com.prash.kosh.sync.SyncManager by lazy { com.prash.kosh.sync.SyncManager(this) }
    val ai: com.prash.kosh.ai.AiAgents by lazy { com.prash.kosh.ai.AiAgents(this) }

    /** Observed by the UI so a theme change applies instantly. */
    val themeMode by lazy { mutableStateOf(settings.themeMode) }

    val userName by lazy { mutableStateOf(settings.userName) }
    fun setUserName(name: String) { settings.userName = name; userName.value = name.trim() }

    fun setThemeMode(mode: String) {
        settings.themeMode = mode
        themeMode.value = mode
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { repo.seedIfEmpty() }
        com.prash.kosh.notify.Notifications.createChannels(this)
        BackupManager.schedule(this)
        if (!isTestHost()) {
            sync.start()
            com.prash.kosh.sync.SyncManager.scheduleMaintenance(this)
        }
    }

    /** Robolectric/screenshot hosts override this to keep background work quiet. */
    open fun isTestHost() = false

    companion object {
        fun get(context: Context): KoshApp = context.applicationContext as KoshApp
    }
}
