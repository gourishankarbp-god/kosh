package com.prash.kosh.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prash.kosh.KoshApp
import com.prash.kosh.data.AccountEntity
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.AuditLogEntity
import com.prash.kosh.data.BudgetEntity
import com.prash.kosh.data.GoalContributionEntity
import com.prash.kosh.data.GoalEntity
import com.prash.kosh.data.InvestmentEntity
import com.prash.kosh.data.InvestmentEntryEntity
import com.prash.kosh.data.RecurringRuleEntity
import com.prash.kosh.data.CategoryEntity
import com.prash.kosh.data.KeywordRuleEntity
import com.prash.kosh.data.MerchantEntity
import com.prash.kosh.data.RawMessageEntity
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.security.KeyManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupData(
    val formatVersion: Int = 2,
    val schemaVersion: Int = 2,
    val createdAt: Long,
    val accounts: List<AccountEntity>,
    val categories: List<CategoryEntity>,
    val merchants: List<MerchantEntity>,
    val keywordRules: List<KeywordRuleEntity>,
    val rawMessages: List<RawMessageEntity>,
    val transactions: List<TransactionEntity>,
    val audit: List<AuditLogEntity>,
    // Added in version 2; empty when restoring an older backup.
    val budgets: List<BudgetEntity> = emptyList(),
    val recurring: List<RecurringRuleEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val goalContributions: List<GoalContributionEntity> = emptyList(),
    val investments: List<InvestmentEntity> = emptyList(),
    val investmentEntries: List<InvestmentEntryEntity> = emptyList(),
    // Added in 1.1; null in older backups (restoring one keeps the message formats already taught).
    val taughtPatterns: List<com.prash.kosh.data.TaughtPatternEntity>? = null,
) {
    fun summary() = "${transactions.size} transactions, ${accounts.size} accounts, ${categories.size} categories, " +
        "${merchants.count { it.source == "USER" }} merchant rules, ${budgets.size} budgets, ${recurring.size} recurring payments, " +
        "${goals.size} goals, ${investments.size} investments, ${rawMessages.size} messages"
}

/**
 * Password-based encryption so a backup can be restored on a new phone.
 * File = "KOSH" + version byte + 16-byte salt + 12-byte IV + AES-256-GCM(gzip(json)).
 * Key = PBKDF2-HMAC-SHA256(password, salt, 210,000 iterations).
 */
object BackupCrypto {
    private val MAGIC = "KOSH".toByteArray()
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 210_000

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(password, salt, ITERATIONS, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: ByteArray, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC + VERSION)
        return MAGIC + VERSION + salt + iv + c.doFinal(gzip(plain))
    }

    class WrongPasswordException : Exception("Wrong password or damaged backup file")

    fun decrypt(file: ByteArray, password: String): ByteArray {
        require(file.size > 33 && file.copyOfRange(0, 4).contentEquals(MAGIC)) { "Not a Kosh backup file" }
        require(file[4] == VERSION) { "Backup was made by a newer version of the app" }
        val salt = file.copyOfRange(5, 21)
        val iv = file.copyOfRange(21, 33)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC + VERSION)
        val plain = try { c.doFinal(file, 33, file.size - 33) } catch (e: javax.crypto.AEADBadTagException) { throw WrongPasswordException() }
        return gunzip(plain)
    }

    private fun gzip(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(b) }
        return out.toByteArray()
    }

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(b.inputStream()).use { it.readBytes() }
}

class BackupManager(private val context: Context, private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val localDir = File(context.filesDir, "backups").apply { mkdirs() }

    suspend fun snapshot(): BackupData = BackupData(
        createdAt = System.currentTimeMillis(),
        accounts = db.accounts().all(), categories = db.categories().all(), merchants = db.merchants().all(),
        keywordRules = db.keywordRules().all(), rawMessages = db.rawMessages().all(),
        transactions = db.transactions().all(), audit = db.audit().all(),
        budgets = db.budgets().all(), recurring = db.recurring().all(), goals = db.goals().all(),
        goalContributions = db.goals().allContributions(), investments = db.investments().all(),
        investmentEntries = db.investments().allEntries(), taughtPatterns = db.taught().all(),
    )

    /** Writes the encrypted backup to the chosen Drive/OneDrive file and keeps 7 local copies. */
    suspend fun backupNow(): String {
        val settings = KoshApp.get(context).settings
        val password = KeyManager.backupPassword(context) ?: error("Set a backup password first")
        val data = snapshot()
        val bytes = BackupCrypto.encrypt(json.encodeToString(BackupData.serializer(), data).toByteArray(), password)

        File(localDir, "kosh-${data.createdAt}.kosh").writeBytes(bytes)
        localDir.listFiles()?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }

        val uri = settings.backupUri
        if (uri != null) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Could not open the backup file. Choose the location again.")
        }
        settings.lastBackupAt = data.createdAt
        settings.lastBackupError = null
        return data.summary() + if (uri == null) " (saved on phone only; choose a Drive/OneDrive file)" else ""
    }

    fun readBackup(uri: Uri, password: String): BackupData {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read the file")
        return json.decodeFromString(BackupData.serializer(), String(BackupCrypto.decrypt(bytes, password)))
    }

    /** Replaces everything with the backup's contents, in one database transaction. */
    suspend fun restore(data: BackupData) {
        db.withTransaction {
            db.audit().clear(); db.rawMessages().clear(); db.transactions().clear()
            db.keywordRules().clear(); db.merchants().clear(); db.categories().clear(); db.accounts().clear()
            // Backups from before v0.2 have no "fixed" flags; apply the defaults.
            val cats = if (data.formatVersion < 2) data.categories.map { it.copy(isFixed = it.isFixed || it.id in com.prash.kosh.engine.SeedData.FIXED) } else data.categories
            db.accounts().insertAll(data.accounts); db.categories().insertAll(cats)
            db.merchants().insertAll(data.merchants); db.keywordRules().insertAll(data.keywordRules)
            db.transactions().insertAll(data.transactions); db.rawMessages().insertAll(data.rawMessages)
            db.audit().insertAll(data.audit)
            db.budgets().clear(); db.recurring().clear(); db.goals().clearContributions(); db.goals().clear()
            db.investments().clearEntries(); db.investments().clear()
            db.budgets().insertAll(data.budgets); db.recurring().insertAll(data.recurring)
            db.goals().insertAll(data.goals); db.goals().insertAllContributions(data.goalContributions)
            db.investments().insertAll(data.investments); db.investments().insertAllEntries(data.investmentEntries)
            data.taughtPatterns?.let { db.taught().clear(); db.taught().insertAll(it) }
            // Backups made before 1.1 have no Reimbursement category.
            if (db.categories().get(com.prash.kosh.engine.Taught.REIMBURSEMENT_CATEGORY) == null && db.categories().get("income") != null) {
                db.categories().upsert(CategoryEntity(com.prash.kosh.engine.Taught.REIMBURSEMENT_CATEGORY, "Reimbursement", "income", "INCOME", "payments", 960))
            }
        }
        KoshApp.get(context).repo.invalidateRules()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-backup", ExistingPeriodicWorkPolicy.KEEP, req)
            com.prash.kosh.notify.ReminderWorker.schedule(context)
        }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = KoshApp.get(applicationContext)
        if (KeyManager.backupPassword(applicationContext) == null) return Result.success()
        return try {
            app.backup.backupNow()
            Result.success()
        } catch (e: Exception) {
            app.settings.lastBackupError = e.message ?: e.javaClass.simpleName
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
