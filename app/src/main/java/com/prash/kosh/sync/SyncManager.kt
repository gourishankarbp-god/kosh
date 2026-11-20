package com.prash.kosh.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.prash.kosh.KoshApp
import com.prash.kosh.security.KeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Sync state kept on the phone; the derived key is wrapped by the Android Keystore. */
class AndroidSyncState(private val context: Context) : SyncState {
    private val p = context.getSharedPreferences("kosh_sync", Context.MODE_PRIVATE)
    override var deviceId: String
        get() = p.getString("device", null) ?: ("p" + UUID.randomUUID().toString().replace("-", "").take(10)).also { id -> p.edit { putString("device", id) } }
        set(v) = p.edit { putString("device", v) }
    override var nextSeq: Long
        get() = p.getLong("seq", 1); set(v) = p.edit { putLong("seq", v) }
    override var cursors: Map<String, Long>
        get() = p.getString("cursors", "").orEmpty().split(';').filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':').toLong() }
        set(v) = p.edit { putString("cursors", v.entries.joinToString(";") { "${it.key}:${it.value}" }) }
    override var hlcMillis: Long
        get() = p.getLong("hlcm", 0); set(v) = p.edit { putLong("hlcm", v) }
    override var hlcCounter: Int
        get() = p.getInt("hlcc", 0); set(v) = p.edit { putInt("hlcc", v) }
    override var key: ByteArray?
        get() = p.getString("key", null)?.let { KeyManager.unwrap(it) }
        set(v) = p.edit { if (v == null) remove("key") else putString("key", KeyManager.wrap(v)) }
    override var uploadedDocs: Set<String>
        get() = p.getStringSet("docs", emptySet()) ?: emptySet()
        set(v) = p.edit { putStringSet("docs", v) }

    var account: String?
        get() = p.getString("account", null); set(v) = p.edit { putString("account", v) }
    var lastSyncAt: Long
        get() = p.getLong("last", 0); set(v) = p.edit { putLong("last", v) }
    var lastError: String?
        get() = p.getString("error", null); set(v) = p.edit { putString("error", v) }
    val enabled get() = p.contains("key")

    fun reset() { p.edit { clear() } }
}

/** Google sign-in for the Drive app folder (only Kosh's own hidden files; no access to the rest of Drive). */
object GoogleAuth {
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()

    /** Token without any UI; throws [AuthRequired] when the user must approve again. */
    suspend fun token(context: Context): String {
        val r = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (r.hasResolution()) throw AuthRequired()
        return r.accessToken ?: throw AuthRequired()
    }

    /** Interactive: returns a token, or a PendingIntent the screen must launch for consent. */
    suspend fun begin(activity: Activity): Pair<String?, PendingIntent?> {
        val r = Identity.getAuthorizationClient(activity).authorize(request()).await()
        return if (r.hasResolution()) null to r.pendingIntent else r.accessToken to null
    }

    fun tokenFromResult(activity: Activity, data: android.content.Intent?): String? =
        runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
}

/**
 * Glue between the app and [SyncEngine]. Battery notes: nothing polls. Uploads run once, a short
 * while after the last change, and only with a network; downloads run when the app opens and every
 * 6 hours when the battery is not low. Archiving runs at most weekly, only while charging.
 */
class SyncManager(private val app: KoshApp) {
    val state = AndroidSyncState(app)
    val status = MutableStateFlow(Status(state.enabled, state.lastSyncAt, state.lastError, false))
    private val lock = Mutex()
    @Volatile private var tokenCache: Pair<String, Long>? = null

    data class Status(val enabled: Boolean, val lastSyncAt: Long, val error: String?, val running: Boolean)

    fun useToken(token: String) { tokenCache = token to System.currentTimeMillis() + 50 * 60_000 }

    private suspend fun token(): String {
        tokenCache?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        return GoogleAuth.token(app).also(::useToken)
    }

    private suspend fun engine(): SyncEngine { val t = token(); return SyncEngine(app.db, DriveRemoteStore { t }, state) }

    private fun publish(running: Boolean) { status.value = Status(state.enabled, state.lastSyncAt, state.lastError, running) }

    suspend fun setup(passphrase: String): SyncEngine.SetupResult = lock.withLock {
        publish(true)
        try {
            engine().setup(passphrase).also { state.lastSyncAt = System.currentTimeMillis(); state.lastError = null; schedule(app) }
        } finally { publish(false) }
    }

    suspend fun syncNow(): SyncEngine.Report? = lock.withLock {
        if (!state.enabled) return null
        publish(true)
        try {
            engine().syncNow(File(app.filesDir, "documents")).also {
                state.lastSyncAt = System.currentTimeMillis(); state.lastError = null
                if (it.pulled > 0) app.repo.invalidateRules()
            }
        } catch (e: AuthRequired) { state.lastError = "Sign in to Google again"; throw e
        } catch (e: Exception) { state.lastError = e.message ?: e.javaClass.simpleName; throw e
        } finally { publish(false) }
    }

    suspend fun archive(): List<String> = lock.withLock { if (state.enabled) engine().archiveOldMonths() else emptyList() }

    suspend fun fetchArchive(month: String) = lock.withLock { engine().fetchArchive(month) }

    /** Stops syncing on this phone. Drive data stays; local data stays. */
    suspend fun disconnect() = lock.withLock {
        state.reset(); app.db.sync().clearAllOutbox(); app.db.sync().clearAllMeta(); publish(false)
        WorkManager.getInstance(app).cancelUniqueWork("sync-periodic"); WorkManager.getInstance(app).cancelUniqueWork("sync-soon")
    }

    /** Called once from Application.onCreate: watches for local changes and queues an upload. */
    fun start() {
        app.appScope.launch {
            app.db.sync().observeOutboxCount().distinctUntilChanged().collect { n -> if (n > 0 && state.enabled) soon(app) }
        }
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Debounced upload: the first change starts a 2-minute timer; later changes ride along. */
        fun soon(context: Context, delaySeconds: Long = 120) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).setInitialDelay(delaySeconds, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-soon", ExistingWorkPolicy.KEEP, req)
        }

        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        }

        fun scheduleMaintenance(context: Context) {
            val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = KoshApp.get(applicationContext)
        return try {
            app.sync.syncNow(); Result.success()
        } catch (_: AuthRequired) {
            Result.success() // shown on the Sync screen; retrying would not help
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Weekly while charging: drops old SMS text per the retention setting, archives old months, tidies the outbox. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = KoshApp.get(applicationContext)
        val months = app.settings.smsRetentionMonths
        if (months > 0) {
            val cutoff = java.time.ZonedDateTime.now(SyncEngine.IST).minusMonths(months.toLong()).toInstant().toEpochMilli()
            app.db.rawMessages().purgeBodiesBefore(cutoff)
        }
        if (!app.sync.state.enabled) { app.db.sync().clearAllOutbox(); return Result.success() }
        return try { app.sync.syncNow(); app.sync.archive(); Result.success() } catch (_: Exception) { Result.retry() }
    }
}
