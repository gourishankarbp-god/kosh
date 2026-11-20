package com.prash.kosh.data

import androidx.room.withTransaction
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.DuplicateDetector
import com.prash.kosh.engine.EngineResult
import com.prash.kosh.engine.PaymentMethod
import com.prash.kosh.engine.RawInput
import com.prash.kosh.engine.RecognitionEngine
import com.prash.kosh.engine.RuleSet
import com.prash.kosh.engine.SeedData
import com.prash.kosh.engine.TxnStatus
import com.prash.kosh.engine.TxnType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Outcome of feeding one message into the app, for toasts and the import summary. */
val COUNTED_STATUSES = setOf(TxnStatus.POSTED.name)

enum class IngestOutcome { ALREADY_SEEN, IGNORED, POSTED, PENDING, NEEDS_REVIEW, SUSPECTED_DUPLICATE, MERGED }

/** Receives budget alerts; the app posts Android notifications, tests use [None]. */
interface Notifier {
    fun budgetAlert(categoryName: String, percent: Int, spentPaise: Long, availablePaise: Long) {}
    companion object { val None = object : Notifier {} }
}

class Repository(val db: AppDatabase, var notifier: Notifier = Notifier.None) {

    /** Auto-save and review thresholds (percent); the app wires these to Settings. */
    var thresholds: () -> Pair<Int, Int> = { 95 to 80 }

    /** Called after any local change so the sync worker can schedule an upload. */
    var onLocalChange: () -> Unit = {}

    private val txns = db.transactions()
    private val raws = db.rawMessages()
    private val json = Json { encodeDefaults = true }
    private val ingestLock = Mutex()
    @Volatile private var engineCache: RecognitionEngine? = null

    // ------------------------------------------------------------------ seeding

    suspend fun seedIfEmpty() {
        if (db.categories().count() > 0) return
        db.withTransaction {
            db.categories().insertAll(SeedData.categories.mapIndexed { i, c ->
                CategoryEntity(c.id, c.name, c.parentId, c.kind.name, c.icon, i, isFixed = c.isFixed)
            })
            db.merchants().insertAll(SeedData.merchants.map {
                MerchantEntity(it.id, it.displayName, it.categoryId, "SEED", it.aliases.joinToString("|"))
            })
            db.keywordRules().insertAll(SeedData.keywordRules.mapIndexed { i, (k, c) -> KeywordRuleEntity(0, k, c, i) })
            db.accounts().insert(AccountEntity(name = "Cash", kind = "CASH"))
        }
    }

    // ------------------------------------------------------------------ rules

    fun invalidateRules() { engineCache = null }

    private suspend fun engine(): RecognitionEngine {
        engineCache?.let { return it }
        val merchants = db.merchants().all()
        val categories = db.categories().all()
        val rules = RuleSet(
            userMerchantRules = merchants.filter { it.source == "USER" && it.categoryId != null }.associate { it.key to it.categoryId!! },
            dictionary = merchants.filter { it.aliases.isNotBlank() }.map {
                SeedData.SeedMerchant(it.key, it.displayName, it.categoryId ?: SeedData.FALLBACK_EXPENSE, it.aliases.split('|'))
            },
            keywordRules = db.keywordRules().all().map { it.keyword to it.categoryId },
            categoryKinds = categories.associate { it.id to SeedData.Kind.valueOf(it.kind) },
        )
        return RecognitionEngine(rules).also { engineCache = it }
    }

    // ------------------------------------------------------------------ ingestion

    private fun hashOf(input: RawInput): String {
        val md = MessageDigest.getInstance("SHA-256")
        // Sender + body is the identity: the same SMS seen live and again in an inbox import
        // carries different timestamps, and apps re-post notifications when they update them.
        val sender = input.sender.uppercase().replace(Regex("-[A-Z]$"), "")
        val key = "${if (input.source == CaptureSource.SHARED) CaptureSource.SMS else input.source}|$sender|${input.body.trim()}"
        return md.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    suspend fun ingest(input: RawInput): IngestOutcome = ingestLock.withLock { ingestLocked(input) }

    private suspend fun ingestLocked(input: RawInput): IngestOutcome {
        val hash = hashOf(input)
        if (raws.exists(hash)) return IngestOutcome.ALREADY_SEEN
        val engine = engine()
        val result = engine.recognise(input)
        return db.withTransaction {
            val rawId = raws.insert(
                RawMessageEntity(source = input.source.name, sender = input.sender, body = input.body,
                    receivedAt = input.receivedAtMillis, hash = hash, outcome = "PENDING")
            )
            if (rawId == -1L) return@withTransaction IngestOutcome.ALREADY_SEEN
            val raw = raws.get(rawId)!!
            when (result) {
                is EngineResult.Ignored -> {
                    raws.update(raw.copy(outcome = "IGNORED", ignoreReason = result.reason.name))
                    IngestOutcome.IGNORED
                }
                is EngineResult.Recognised -> post(result, input, raw)
            }
        }
    }

    private suspend fun post(result: EngineResult.Recognised, input: RawInput, raw: RawMessageEntity): IngestOutcome {
        val r = result.recognition
        val f = r.fields
        val now = System.currentTimeMillis()
        val accountId = f.accountLast4?.let { resolveAccount(f.bank, it, f.method) }

        val window = DuplicateDetector.WINDOW_MILLIS
        val candidates = (txns.nearby(f.amountPaise, r.txnDateMillis - window, r.txnDateMillis + window) +
            (f.reference?.let { txns.byReference(it) } ?: emptyList())).distinctBy { it.id }
        val verdict = DuplicateDetector.check(
            f.amountPaise, r.type, r.txnDateMillis, f.accountLast4, f.reference, r.merchant?.key, input.source,
            candidates.map {
                DuplicateDetector.Existing(it.id, it.amountPaise, TxnType.valueOf(it.type), it.dateMillis, it.accountLast4,
                    it.reference, it.merchantKey, it.sources.split(',').mapNotNull { s -> runCatching { CaptureSource.valueOf(s) }.getOrNull() }.toSet())
            },
        )

        if (verdict is DuplicateDetector.Verdict.Merge) {
            val existing = txns.get(verdict.existingId)!!
            val merged = existing.copy(
                sources = (existing.sources.split(',') + input.source.name).distinct().joinToString(","),
                reference = existing.reference ?: f.reference,
                accountId = existing.accountId ?: accountId,
                accountLast4 = existing.accountLast4 ?: f.accountLast4,
                merchantKey = existing.merchantKey ?: r.merchant?.key,
                merchantName = existing.merchantName ?: r.merchant?.displayName,
                confidence = maxOf(existing.confidence, r.confidence),
                updatedAt = now,
            )
            txns.update(merged)
            audit(existing.id, "MERGED", existing, merged)
            raws.update(raw.copy(outcome = "MERGED", confidence = r.confidence, transactionId = existing.id))
            return IngestOutcome.MERGED
        }

        val (auto, review) = thresholds()
        val status = if (verdict is DuplicateDetector.Verdict.Suspected) TxnStatus.SUSPECTED_DUPLICATE else r.statusFor(auto, review)
        // ATM withdrawal = bank -> cash; cash deposit = cash -> bank. Neither is income or spending.
        val toAccount = if (r.categoryId == "transfers.cash" || r.categoryId == "transfers.cashdep") cashAccountId() else null
        var linked = (verdict as? DuplicateDetector.Verdict.Suspected)?.existingId
        if (r.type == TxnType.REFUND && r.merchant != null) {
            linked = txns.refundCandidate(r.merchant!!.key, f.amountPaise, r.txnDateMillis - 90L * 86_400_000, r.txnDateMillis)?.id
        }
        val entity = TransactionEntity(
            type = r.type.name, direction = f.direction.name, status = status.name, amountPaise = f.amountPaise,
            dateMillis = r.txnDateMillis, accountId = accountId, accountLast4 = f.accountLast4,
            merchantKey = r.merchant?.key, merchantName = r.merchant?.displayName, categoryId = r.categoryId,
            method = f.method.name, reference = f.reference, confidence = r.confidence, sources = input.source.name,
            linkedTxnId = linked, createdAt = now, updatedAt = now, toAccountId = toAccount,
        )
        val id = txns.insert(entity)
        audit(id, "CREATED", null, entity.copy(id = id))
        raws.update(raw.copy(outcome = "RECOGNISED", confidence = r.confidence, transactionId = id))
        if (status == TxnStatus.POSTED) afterCounted(entity.copy(id = id))
        return when (status) {
            TxnStatus.POSTED -> IngestOutcome.POSTED
            TxnStatus.PENDING_VERIFICATION -> IngestOutcome.PENDING
            TxnStatus.SUSPECTED_DUPLICATE -> IngestOutcome.SUSPECTED_DUPLICATE
            else -> IngestOutcome.NEEDS_REVIEW
        }
    }

    /** Finds the account for "HDFC ••4821", creating it the first time it is seen. */
    private suspend fun resolveAccount(bank: String?, last4: String, method: PaymentMethod): Long {
        db.accounts().find(bank, last4)?.let { return it.id }
        val kind = if (method == PaymentMethod.CARD) "CARD" else "BANK"
        val name = listOfNotNull(bank, if (kind == "CARD") "Card" else null).joinToString(" ").ifBlank { "Account" } + " ••$last4"
        return db.accounts().insert(AccountEntity(name = name, kind = kind, bank = bank, last4 = last4))
    }

    // ------------------------------------------------------------------ edits (always audited)

    internal suspend fun audit(txnId: Long, action: String, before: TransactionEntity?, after: TransactionEntity?) {
        db.audit().insert(
            AuditLogEntity(transactionId = txnId, at = System.currentTimeMillis(), action = action,
                before = before?.let { json.encodeToString(it) }, after = after?.let { json.encodeToString(it) })
        )
    }

    /** Imports statement rows through the normal pipeline (rules, duplicates, merging with SMS already captured). */
    suspend fun importStatement(rows: List<com.prash.kosh.engine.StatementParser.Row>, accountId: Long?): Map<IngestOutcome, Int> {
        val acct = accountId?.let { db.accounts().get(it) }
        val out = HashMap<IngestOutcome, Int>()
        for (r in rows) {
            val body = com.prash.kosh.engine.StatementParser.toMessage(r, acct?.bank, acct?.last4)
            val o = ingest(RawInput(CaptureSource.IMPORT, "STATEMENT", body, r.dateMillis))
            out[o] = (out[o] ?: 0) + 1
        }
        return out
    }

    /** Saves something that must be checked by the user first (AI-read messages): Review queue, never counted until approved. */
    suspend fun addForReview(t: TransactionEntity, source: String): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val e = t.copy(id = 0, status = TxnStatus.NEEDS_REVIEW.name, sources = source, createdAt = now, updatedAt = now)
        val id = txns.insert(e)
        audit(id, "CREATED", null, e.copy(id = id))
        id
    }

    suspend fun addManual(t: TransactionEntity): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val e = t.copy(id = 0, status = TxnStatus.POSTED.name, sources = CaptureSource.MANUAL.name, confidence = 100, createdAt = now, updatedAt = now)
        val id = txns.insert(e)
        audit(id, "CREATED", null, e.copy(id = id))
        afterCounted(e.copy(id = id))
        id
    }

    /** Saves an edit. Editing a transaction that was waiting for review also approves it. */
    suspend fun saveEdit(updated: TransactionEntity) = db.withTransaction {
        val before = txns.get(updated.id) ?: return@withTransaction
        val status = if (before.status == TxnStatus.REJECTED.name) before.status else TxnStatus.POSTED.name
        val after = updated.copy(status = status, updatedAt = System.currentTimeMillis())
        txns.update(after)
        audit(after.id, "EDITED", before, after)
        if (before.status !in COUNTED_STATUSES && after.status in COUNTED_STATUSES) afterCounted(after)
    }

    suspend fun setStatus(id: Long, status: TxnStatus) = db.withTransaction {
        val before = txns.get(id) ?: return@withTransaction
        val after = before.copy(status = status.name, updatedAt = System.currentTimeMillis())
        txns.update(after)
        audit(id, if (status == TxnStatus.REJECTED) "REJECTED" else "APPROVED", before, after)
        if (before.status !in COUNTED_STATUSES && after.status in COUNTED_STATUSES) afterCounted(after)
    }

    /** "Yes, it's a duplicate": reject this one and attach its source to the original. */
    suspend fun confirmDuplicate(id: Long) = db.withTransaction {
        val dup = txns.get(id) ?: return@withTransaction
        dup.linkedTxnId?.let { origId ->
            txns.get(origId)?.let { orig ->
                val merged = orig.copy(sources = (orig.sources.split(',') + dup.sources.split(',')).distinct().joinToString(","),
                    reference = orig.reference ?: dup.reference, updatedAt = System.currentTimeMillis())
                txns.update(merged)
                audit(orig.id, "MERGED", orig, merged)
            }
        }
        val after = dup.copy(status = TxnStatus.REJECTED.name, updatedAt = System.currentTimeMillis())
        txns.update(after)
        audit(id, "REJECTED", dup, after)
    }

    /** Learn from a correction: "Always put <merchant> in <category>". */
    suspend fun rememberMerchantCategory(merchantKey: String, displayName: String, categoryId: String, applyToPast: Boolean): Int {
        val count = db.withTransaction {
            val existing = db.merchants().get(merchantKey)
            db.merchants().upsert(
                (existing ?: MerchantEntity(merchantKey, displayName, categoryId, "USER")).copy(categoryId = categoryId, source = "USER")
            )
            if (!applyToPast) return@withTransaction 0
            val targets = txns.byMerchantInCategories(merchantKey, listOf(SeedData.FALLBACK_EXPENSE, SeedData.FALLBACK_INCOME))
            for (t in targets) {
                val after = t.copy(categoryId = categoryId, updatedAt = System.currentTimeMillis())
                txns.update(after)
                audit(t.id, "RULE_APPLIED", t, after)
            }
            targets.size
        }
        invalidateRules()
        return count
    }

    suspend fun setMerchantCategory(m: MerchantEntity, categoryId: String?) {
        db.merchants().upsert(m.copy(categoryId = categoryId, source = "USER"))
        invalidateRules()
    }

    suspend fun saveKeywordRule(r: KeywordRuleEntity) { db.keywordRules().upsert(r); invalidateRules() }
    suspend fun deleteKeywordRule(id: Long) { db.keywordRules().delete(id); invalidateRules() }
    suspend fun saveCategory(c: CategoryEntity) { db.categories().upsert(c); invalidateRules() }
    // ------------------------------------------------------------------ automation after a transaction counts

    /** Links investment payments, matches recurring payments and checks budgets. */
    internal suspend fun afterCounted(t: TransactionEntity) {
        if (t.direction == "DEBIT" && (t.type == TxnType.INVESTMENT.name || t.categoryId?.startsWith("insurance") == true)) autoLinkInvestment(t)
        if (t.recurringId == null && t.type in setOf("EXPENSE", "INCOME", "TRANSFER")) matchRecurring(t)
        if (t.type == TxnType.EXPENSE.name) checkBudget(t)
    }

    /** The Cash wallet, created if missing. */
    suspend fun cashAccountId(): Long =
        db.accounts().all().firstOrNull { it.kind == "CASH" && !it.archived }?.id ?: db.accounts().insert(AccountEntity(name = "Cash", kind = "CASH"))

    /** Permanently deletes a transaction (its audit trail is kept). */
    suspend fun deleteTransaction(id: Long) = db.withTransaction {
        val before = txns.get(id) ?: return@withTransaction
        db.extras().clearSplits(id)
        db.investments().allEntries().filter { it.transactionId == id }.forEach { db.investments().deleteEntry(it.id) }
        txns.delete(id)
        audit(id, "DELETED", before, null)
    }

    /** Replaces a transaction's splits; amounts must add up to the transaction total. */
    suspend fun saveSplits(txnId: Long, parts: List<SplitEntity>): Boolean = db.withTransaction {
        val t = txns.get(txnId) ?: return@withTransaction false
        if (parts.isNotEmpty() && parts.sumOf { it.amountPaise } != t.amountPaise) return@withTransaction false
        db.extras().clearSplits(txnId)
        if (parts.size > 1) db.extras().insertSplits(parts.map { it.copy(id = 0, transactionId = txnId) })
        audit(txnId, "SPLIT", t, t)
        true
    }

    suspend fun saveAccount(a: AccountEntity) { if (a.id == 0L) db.accounts().insert(a) else db.accounts().update(a) }
}
