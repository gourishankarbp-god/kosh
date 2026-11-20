package com.prash.kosh.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.ArchiveIndexEntity
import com.prash.kosh.data.Migrations
import com.prash.kosh.data.SyncMetaEntity
import com.prash.kosh.engine.SeedData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Persistent sync state (SharedPreferences in the app, memory in tests). */
interface SyncState {
    var deviceId: String
    var nextSeq: Long
    var cursors: Map<String, Long>
    var hlcMillis: Long
    var hlcCounter: Int
    var key: ByteArray?
    var uploadedDocs: Set<String>
}

class MemorySyncState(override var deviceId: String = "a-" + UUID.randomUUID().toString().take(8)) : SyncState {
    override var nextSeq = 1L
    override var cursors: Map<String, Long> = emptyMap()
    override var hlcMillis = 0L
    override var hlcCounter = 0
    override var key: ByteArray? = null
    override var uploadedDocs: Set<String> = emptySet()
}

/**
 * Two-way sync between the local database and a [RemoteStore], shared with the web app.
 *
 * Remote files (all encrypted except kosh-meta.json):
 *  - kosh-meta.json                    salt + passphrase check
 *  - ops-<device>-<seq>.kosh            one batch of changes from one device; never rewritten
 *  - snap-<version>.kosh                full state, written occasionally so old ops can be removed
 *  - archive-<yyyy-MM>.kosh             transactions of a month older than 12 months
 *  - doc-<fileKey>.kosh                 an attached document
 *
 * Every record has a global id (uid) and a version (hybrid clock). The newest version wins; a
 * delete is a version too. Because every device writes only its own files, two devices can never
 * overwrite each other's uploads.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val store: RemoteStore,
    private val state: SyncState,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val hlc = Hlc(state.hlcMillis, state.hlcCounter, state.deviceId)
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    companion object {
        const val FORMAT = 1
        val TABLES = Migrations.SYNCED_TABLES
        /** Long-id references: table -> (column -> referenced table). */
        val REFS: Map<String, Map<String, String>> = mapOf(
            "transactions" to mapOf("accountId" to "accounts", "toAccountId" to "accounts", "linkedTxnId" to "transactions", "recurringId" to "recurring_rules"),
            "transaction_splits" to mapOf("transactionId" to "transactions"),
            "raw_messages" to mapOf("transactionId" to "transactions"),
            "audit_log" to mapOf("transactionId" to "transactions"),
            "investment_entries" to mapOf("investmentId" to "investments", "transactionId" to "transactions"),
            "goal_contributions" to mapOf("goalId" to "savings_goals"),
            "recurring_rules" to mapOf("accountId" to "accounts"),
            "documents" to mapOf("investmentId" to "investments"),
        )
        private val STRING_KEYED = setOf("categories", "merchants", "budgets", "archive_index")
        private val OPS = Regex("""^ops-([A-Za-z0-9-]+)-(\d{10})\.kosh$""")
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    }

    data class Report(val pushed: Int = 0, val pulled: Int = 0, val files: Int = 0)

    private fun saveClock() { val (m, c) = hlc.state(); state.hlcMillis = m; state.hlcCounter = c }
    private fun key(): ByteArray = state.key ?: error("Sync passphrase not set")

    // ------------------------------------------------------------------ setup

    sealed interface SetupResult {
        data object CreatedNew : SetupResult
        data object JoinedExisting : SetupResult
    }

    /**
     * Connects this device. With an empty Drive, creates the encrypted space and uploads everything.
     * With existing data, checks the passphrase, merges, and uploads anything only this device has.
     */
    suspend fun setup(passphrase: String): SetupResult {
        val metaFile = store.list("kosh-meta").firstOrNull { it.name == "kosh-meta.json" }
        if (metaFile == null) {
            val salt = SyncCrypto.newSalt()
            val k = SyncCrypto.deriveKey(passphrase, salt)
            val meta = buildJsonObject {
                put("format", FORMAT); put("salt", SyncCrypto.b64(salt)); put("iterations", SyncCrypto.ITERATIONS)
                put("check", SyncCrypto.b64(SyncCrypto.encrypt(k, SyncCrypto.CHECK_TEXT.toByteArray())))
            }
            store.upload("kosh-meta.json", meta.toString().toByteArray())
            state.key = k
            enqueueEverything()
            push()
            return SetupResult.CreatedNew
        }
        val meta = json.parseToJsonElement(String(store.download(metaFile.id))).jsonObject
        val k = SyncCrypto.deriveKey(passphrase, SyncCrypto.unb64(meta["salt"]!!.jsonPrimitive.content))
        val check = String(SyncCrypto.decrypt(k, SyncCrypto.unb64(meta["check"]!!.jsonPrimitive.content)))
        if (check != SyncCrypto.CHECK_TEXT) throw SyncCrypto.WrongPassphrase()
        state.key = k
        val hasOwnData = db.transactions().all().isNotEmpty()
        // A fresh install only has the built-in starter data, which Drive already holds.
        if (!hasOwnData) db.sync().clearAllOutbox()
        pull()
        if (hasOwnData) { enqueueEverything(); push() }
        return SetupResult.JoinedExisting
    }

    /** Puts every row of every synced table in the outbox (first upload, or merging a device that already has data). */
    suspend fun enqueueEverything() = db.withTransaction {
        TABLES.forEach { (t, k) ->
            sql.execSQL("INSERT INTO sync_outbox (tbl, localKey, op, at) SELECT '$t', CAST(`$k` AS TEXT), 'U', ${now()} FROM `$t`")
        }
    }

    // ------------------------------------------------------------------ uids

    private fun deterministicUid(tbl: String, row: JsonObject): String? = when (tbl) {
        "accounts" -> {
            val kind = row["kind"]?.jsonPrimitive?.contentOrNull
            val last4 = row["last4"]?.jsonPrimitive?.contentOrNull
            val bank = row["bank"]?.jsonPrimitive?.contentOrNull ?: ""
            when {
                kind == "CASH" && row["name"]?.jsonPrimitive?.contentOrNull == "Cash" -> "acct:cash"
                last4 != null -> "acct:${bank.lowercase()}:$last4:${kind?.lowercase()}"
                else -> null
            }
        }
        "keyword_rules" -> row["keyword"]?.jsonPrimitive?.contentOrNull?.let { "kw:$it" }
        else -> null
    }

    /** Global id for a local row, creating one if this is the first time it is synced. */
    private suspend fun uidFor(tbl: String, localKey: String, row: JsonObject? = null): String {
        if (tbl in STRING_KEYED) return localKey
        db.sync().metaByLocal(tbl, localKey)?.let { return it.uid }
        val r = row ?: readRow(tbl, localKey)
        var uid = r?.let { deterministicUid(tbl, it) } ?: UUID.randomUUID().toString()
        if (db.sync().metaByUid(tbl, uid) != null) uid = UUID.randomUUID().toString()
        db.sync().upsertMeta(SyncMetaEntity(tbl, uid, localKey, "0"))
        return uid
    }

    private suspend fun localFor(tbl: String, uid: String): String? {
        if (tbl in STRING_KEYED) return uid
        return db.sync().metaByUid(tbl, uid)?.takeIf { !it.deleted }?.localKey
    }

    // ------------------------------------------------------------------ rows <-> JSON

    private fun readRow(tbl: String, localKey: String): JsonObject? {
        val keyCol = TABLES[tbl]!!
        sql.query("SELECT * FROM `$tbl` WHERE `$keyCol` = ?", arrayOf<Any>(localKey)).use { c -> return if (c.moveToFirst()) cursorToJson(c) else null }
    }

    private fun cursorToJson(c: Cursor): JsonObject = buildJsonObject {
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> put(name, JsonNull)
                Cursor.FIELD_TYPE_INTEGER -> put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> put(name, c.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> put(name, c.getString(i))
                else -> {}
            }
        }
    }

    /** Local row -> sync record: drops the local id and turns id references into uids. */
    private suspend fun toRecord(tbl: String, row: JsonObject): JsonObject = buildJsonObject {
        val keyCol = TABLES[tbl]!!
        val refs = REFS[tbl] ?: emptyMap()
        for ((k, v) in row) {
            if (k == keyCol && tbl !in STRING_KEYED) continue
            val target = refs[k]
            if (target != null && v is JsonPrimitive && v.longOrNull != null) put(k, uidFor(target, v.long.toString()))
            else put(k, v)
        }
    }

    /** Sync record -> local column values, mapping uids back to local ids (unknown references become null). */
    private suspend fun toValues(tbl: String, rec: JsonObject): ContentValues {
        val cv = ContentValues()
        val refs = REFS[tbl] ?: emptyMap()
        for ((k, v) in rec) {
            val target = refs[k]
            if (target != null) {
                val local = (v as? JsonPrimitive)?.contentOrNull?.let { localFor(target, it) }
                if (local == null) cv.putNull(k) else cv.put(k, local.toLong())
                continue
            }
            when {
                v is JsonNull -> cv.putNull(k)
                v is JsonPrimitive && v.isString -> cv.put(k, v.content)
                v is JsonPrimitive && v.booleanOrNull != null -> cv.put(k, if (v.booleanOrNull == true) 1 else 0)
                v is JsonPrimitive && v.longOrNull != null -> cv.put(k, v.longOrNull)
                v is JsonPrimitive && v.doubleOrNull != null -> cv.put(k, v.doubleOrNull)
                else -> cv.put(k, v.toString())
            }
        }
        return cv
    }

    /** A local row with no sync id yet that is the same seeded/natural record (e.g. the Cash account). */
    private suspend fun matchSeeded(tbl: String, uid: String): String? {
        if (tbl != "accounts" && tbl != "keyword_rules") return null
        val keyCol = TABLES[tbl]!!
        sql.query("SELECT * FROM `$tbl`").use { c ->
            while (c.moveToNext()) {
                val row = cursorToJson(c)
                val lk = row[keyCol]!!.jsonPrimitive.content
                if (deterministicUid(tbl, row) == uid && db.sync().metaByLocal(tbl, lk) == null) return lk
            }
        }
        return null
    }

    // ------------------------------------------------------------------ push

    /** Uploads local changes as one new ops file. Returns how many records were sent. */
    suspend fun push(): Int {
        var total = 0
        while (true) {
            val batch = db.sync().outbox(2000)
            if (batch.isEmpty()) break
            val latest = batch.groupBy { it.tbl to it.localKey }.mapValues { it.value.last() }
            val changes = buildJsonArray {
                db.withTransaction {
                    for ((tk, entry) in latest) {
                        val (tbl, localKey) = tk
                        if (tbl !in TABLES) continue
                        val row = if (entry.op == "D") null else readRow(tbl, localKey)
                        val version = hlc.next(now())
                        if (row == null) {
                            val meta = if (tbl in STRING_KEYED) SyncMetaEntity(tbl, localKey, localKey, "0") else db.sync().metaByLocal(tbl, localKey) ?: continue
                            db.sync().upsertMeta(meta.copy(version = version, deleted = true, localKey = if (tbl in STRING_KEYED) localKey else "deleted:${meta.uid}"))
                            add(buildJsonObject { put("t", tbl); put("u", meta.uid); put("v", version); put("d", true) })
                        } else {
                            val uid = uidFor(tbl, localKey, row)
                            db.sync().upsertMeta(SyncMetaEntity(tbl, uid, localKey, version))
                            add(buildJsonObject { put("t", tbl); put("u", uid); put("v", version); put("r", toRecord(tbl, row)) })
                        }
                    }
                }
            }
            saveClock()
            if (changes.isNotEmpty()) {
                val seq = state.nextSeq
                val body = buildJsonObject {
                    put("format", FORMAT); put("device", state.deviceId); put("seq", seq); put("createdAt", now()); put("changes", changes)
                }
                store.upload(opsName(state.deviceId, seq), SyncCrypto.encrypt(key(), body.toString().toByteArray()))
                state.nextSeq = seq + 1
                total += changes.size
            }
            db.sync().clearOutbox(batch.maxOf { it.seq })
        }
        return total
    }

    private fun opsName(device: String, seq: Long) = "ops-$device-%010d.kosh".format(seq)

    // ------------------------------------------------------------------ pull

    /** Downloads and applies changes from other devices. Returns how many records were applied. */
    suspend fun pull(): Int {
        val ops = store.list("ops-").mapNotNull { f -> OPS.matchEntire(f.name)?.let { Triple(it.groupValues[1], it.groupValues[2].toLong(), f) } }
        val byDevice = ops.filter { it.first != state.deviceId }.groupBy({ it.first }, { it.second to it.third })
        var cursors = state.cursors.toMutableMap()
        var applied = 0

        // Use a snapshot when this device is new or has fallen behind files that were already tidied away.
        val behind = byDevice.any { (dev, files) -> files.minOf { it.first } > (cursors[dev] ?: 0) + 1 }
        if (cursors.isEmpty() || behind) {
            store.list("snap-").maxByOrNull { it.name }?.let { snap ->
                val body = json.parseToJsonElement(String(SyncCrypto.decrypt(key(), store.download(snap.id)))).jsonObject
                applied += apply(body["records"]!!.jsonArray)
                body["cursors"]?.jsonObject?.forEach { (dev, v) -> if (dev != state.deviceId) cursors[dev] = maxOf(cursors[dev] ?: 0, v.jsonPrimitive.long) }
            }
        }
        for ((dev, files) in byDevice) {
            for ((seq, f) in files.sortedBy { it.first }) {
                if (seq <= (cursors[dev] ?: 0)) continue
                val body = json.parseToJsonElement(String(SyncCrypto.decrypt(key(), store.download(f.id)))).jsonObject
                applied += apply(body["changes"]!!.jsonArray)
                cursors[dev] = seq
                state.cursors = cursors
            }
        }
        state.cursors = cursors
        saveClock()
        return applied
    }

    /** Applies remote records newest-version-wins, without echoing them back into the outbox. */
    private suspend fun apply(changes: JsonArray): Int {
        val order = TABLES.keys.toList()
        val sorted = changes.map { it.jsonObject }.sortedBy { order.indexOf(it["t"]!!.jsonPrimitive.content) }
        var n = 0
        val archivedMonths = mutableSetOf<String>()
        db.withTransaction {
            db.sync().ensureFlag(); db.sync().setSuppress(1)
            try {
                for (c in sorted) {
                    val tbl = c["t"]!!.jsonPrimitive.content
                    if (tbl !in TABLES) continue
                    val uid = c["u"]!!.jsonPrimitive.content
                    val version = c["v"]!!.jsonPrimitive.content
                    hlc.observe(version)
                    val meta = db.sync().metaByUid(tbl, uid)
                    if (meta != null && meta.version >= version) continue
                    val keyCol = TABLES[tbl]!!
                    if (c["d"]?.jsonPrimitive?.booleanOrNull == true) {
                        val local = meta?.localKey?.takeIf { !it.startsWith("deleted:") } ?: if (tbl in STRING_KEYED) uid else null
                        if (local != null) sql.delete(tbl, "`$keyCol` = ?", arrayOf(local))
                        db.sync().upsertMeta(SyncMetaEntity(tbl, uid, if (tbl in STRING_KEYED) uid else "deleted:$uid", version, deleted = true))
                        n++; continue
                    }
                    val cv = toValues(tbl, c["r"]!!.jsonObject)
                    val localKey: String = if (tbl in STRING_KEYED) {
                        sql.insert(tbl, SQLiteDatabase.CONFLICT_REPLACE, cv); uid
                    } else {
                        val existing = meta?.localKey?.takeIf { !meta.deleted && !it.startsWith("deleted:") }
                            ?: if (meta == null) matchSeeded(tbl, uid) else null
                        val updated = existing != null && sql.update(tbl, SQLiteDatabase.CONFLICT_IGNORE, cv, "`$keyCol` = ?", arrayOf(existing)) > 0
                        if (updated) existing!! else {
                            val id = sql.insert(tbl, SQLiteDatabase.CONFLICT_IGNORE, cv)
                            if (id == -1L) continue
                            id.toString()
                        }
                    }
                    db.sync().upsertMeta(SyncMetaEntity(tbl, uid, localKey, version))
                    if (tbl == "archive_index") archivedMonths += uid
                    n++
                }
                archivedMonths.forEach { purgeMonthLocally(it) }
            } finally {
                db.sync().setSuppress(0)
            }
        }
        return n
    }

    // ------------------------------------------------------------------ snapshots

    /** Writes a full-state snapshot and removes ops files it covers that are older than [keepDays]. */
    suspend fun compact(keepDays: Int = 14): Boolean {
        val ops = store.list("ops-")
        if (ops.size < 300) return false
        val records = buildJsonArray {
            db.withTransaction {
                for ((tbl, keyCol) in TABLES) {
                    sql.query("SELECT * FROM `$tbl`").use { c ->
                        while (c.moveToNext()) {
                            val row = cursorToJson(c)
                            val lk = row[keyCol]!!.jsonPrimitive.content
                            val uid = uidFor(tbl, lk, row)
                            val v = db.sync().metaByUid(tbl, uid)?.version ?: "0"
                            add(buildJsonObject { put("t", tbl); put("u", uid); put("v", v); put("r", toRecord(tbl, row)) })
                        }
                    }
                }
            }
        }
        val cursors = state.cursors + (state.deviceId to state.nextSeq - 1)
        val body = buildJsonObject {
            put("format", FORMAT); put("device", state.deviceId); put("createdAt", now()); put("records", records)
            put("cursors", buildJsonObject { cursors.forEach { (d, s) -> put(d, s) } })
        }
        store.upload("snap-${hlc.next(now())}.kosh", SyncCrypto.encrypt(key(), body.toString().toByteArray()))
        saveClock()
        // Tidy: older snapshots, and ops already inside this snapshot. Recent ones stay for devices mid-sync.
        store.list("snap-").sortedBy { it.name }.dropLast(1).forEach { store.delete(it.id) }
        val cutoff = now() - keepDays * 86_400_000L
        ops.forEach { f ->
            val m = OPS.matchEntire(f.name) ?: return@forEach
            val dev = m.groupValues[1]; val seq = m.groupValues[2].toLong()
            val covered = seq <= (cursors[dev] ?: 0)
            val created = runCatching { json.parseToJsonElement(String(SyncCrypto.decrypt(key(), store.download(f.id)))).jsonObject["createdAt"]!!.jsonPrimitive.long }.getOrDefault(0L)
            if (covered && created < cutoff) store.delete(f.id)
        }
        return true
    }

    // ------------------------------------------------------------------ 12-month archive

    /** First month kept on the device: the current month and the 11 before it. */
    fun firstKeptMonth(today: YearMonth = YearMonth.now(IST)): YearMonth = today.minusMonths(11)

    /**
     * Moves transactions older than 12 months to archive-<month>.kosh in Drive, records the month's
     * totals in archive_index (synced, so insights still show them), then removes them from this device.
     */
    suspend fun archiveOldMonths(today: YearMonth = YearMonth.now(IST)): List<String> {
        if (db.sync().outboxCount() > 0) push()
        val cutoff = firstKeptMonth(today).atDay(1).atStartOfDay(IST).toInstant().toEpochMilli()
        val old = db.transactions().olderThan(cutoff)
        if (old.isEmpty()) return emptyList()
        val done = mutableListOf<String>()
        val categories = db.categories().all().associateBy { it.id }
        val quick = SeedData.QUICK_COMMERCE.toSet()
        for ((month, txns) in old.groupBy { YearMonth.from(Instant.ofEpochMilli(it.dateMillis).atZone(IST)).toString() }) {
            val ids = txns.map { it.id }
            val splits = db.extras().splitsFor(ids)
            val raws = db.rawMessages().forTxns(ids)
            val audits = db.audit().forTxns(ids)
            val records = buildJsonArray {
                for ((tbl, rows) in listOf("transactions" to ids.map { it.toString() }, "transaction_splits" to splits.map { it.id.toString() },
                    "raw_messages" to raws.map { it.id.toString() }, "audit_log" to audits.map { it.id.toString() })) {
                    for (lk in rows) {
                        val row = readRow(tbl, lk) ?: continue
                        val uid = uidFor(tbl, lk, row)
                        add(buildJsonObject { put("t", tbl); put("u", uid); put("v", db.sync().metaByUid(tbl, uid)?.version ?: "0"); put("r", toRecord(tbl, row)) })
                    }
                }
            }
            // Merge with an archive file already in Drive for this month.
            val name = "archive-$month.kosh"
            val existing = store.list("archive-$month").firstOrNull { it.name == name }
            val merged = if (existing != null) {
                val prev = json.parseToJsonElement(String(SyncCrypto.decrypt(key(), store.download(existing.id)))).jsonObject["records"]!!.jsonArray
                val mine = records.map { it.jsonObject["u"]!!.jsonPrimitive.content }.toSet()
                JsonArray(prev.filter { it.jsonObject["u"]!!.jsonPrimitive.content !in mine } + records)
            } else records
            store.upload(name, SyncCrypto.encrypt(key(), buildJsonObject { put("format", FORMAT); put("month", month); put("records", merged) }.toString().toByteArray()), existing?.id)

            val counted = txns.filter { it.status == "POSTED" }
            fun total(type: String) = counted.filter { it.type == type }.sumOf { it.amountPaise }
            val splitBy = splits.groupBy { it.transactionId }
            // (category, amount, matched to a recurring payment) for every expense line.
            val lines = counted.filter { it.type == "EXPENSE" }.flatMap { t ->
                splitBy[t.id]?.map { Triple(it.categoryId, it.amountPaise, t.recurringId != null) } ?: listOf(Triple(t.categoryId ?: "other", t.amountPaise, t.recurringId != null))
            }
            fun fixed(c: String) = categories[c]?.isFixed == true || categories[c]?.parentId?.let { categories[it]?.isFixed } == true
            val recurring = lines.filter { it.third || fixed(it.first) }.sumOf { it.second }
            val byTop = lines.groupBy { it.first.substringBefore('.') }.mapValues { e -> e.value.sumOf { it.second } }
            db.extras().upsertArchive(ArchiveIndexEntity(month, total("INCOME"), total("EXPENSE"), total("REFUND"), total("INVESTMENT"), total("TRANSFER"),
                recurring, lines.filter { it.first in quick }.sumOf { it.second },
                buildJsonObject { byTop.forEach { (k, v) -> put(k, v) } }.toString(), txns.size, now()))
            purgeMonthLocally(month)
            done += month
        }
        push()
        return done
    }

    /** Removes a month's transactions from this device only (they stay in Drive). */
    private suspend fun purgeMonthLocally(month: String) {
        val ym = YearMonth.parse(month)
        if (!ym.isBefore(firstKeptMonth())) return
        val from = ym.atDay(1).atStartOfDay(IST).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(IST).toInstant().toEpochMilli() - 1
        val ids = db.transactions().olderThan(to + 1).filter { it.dateMillis >= from }.map { it.id }
        if (ids.isEmpty()) return
        val wasSuppressed = sql.query("SELECT suppress FROM sync_flag WHERE id = 1").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        db.sync().setSuppress(1)
        try {
            val distinct = ids.distinct()
            db.extras().deleteSplitsFor(distinct); db.rawMessages().deleteForTxns(distinct); db.audit().deleteForTxns(distinct)
            db.transactions().deleteIds(distinct)
        } finally { db.sync().setSuppress(wasSuppressed) }
    }

    /** Reads an archived month from Drive for viewing (nothing is written back to the device). */
    fun fetchArchive(month: String): List<JsonObject> {
        val f = store.list("archive-$month").firstOrNull { it.name == "archive-$month.kosh" } ?: return emptyList()
        return json.parseToJsonElement(String(SyncCrypto.decrypt(key(), store.download(f.id)))).jsonObject["records"]!!.jsonArray.map { it.jsonObject }
    }

    // ------------------------------------------------------------------ documents

    /** Uploads attached files not yet in Drive and downloads ones that arrived from other devices. */
    suspend fun syncDocuments(dir: java.io.File) {
        val remote = store.list("doc-").associateBy { it.name }
        val uploaded = state.uploadedDocs.toMutableSet()
        for (d in db.extras().allDocuments()) {
            val name = "doc-${d.fileKey}.kosh"
            val local = java.io.File(dir, d.fileKey)
            if (local.exists() && name !in remote && d.fileKey !in uploaded) {
                store.upload(name, SyncCrypto.encrypt(key(), local.readBytes())); uploaded += d.fileKey
            } else if (!local.exists() && name in remote) {
                local.writeBytes(SyncCrypto.decrypt(key(), store.download(remote[name]!!.id))); uploaded += d.fileKey
            }
        }
        state.uploadedDocs = uploaded
    }

    /** Full cycle used by the worker: upload, download, tidy. */
    suspend fun syncNow(docsDir: java.io.File? = null): Report {
        val pushed = push()
        val pulled = pull()
        docsDir?.let { syncDocuments(it) }
        val compacted = compact()
        return Report(pushed, pulled, if (compacted) 1 else 0)
    }
}
