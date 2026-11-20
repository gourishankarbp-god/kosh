package com.prash.kosh.ai

import android.content.Context
import androidx.core.content.edit
import com.prash.kosh.KoshApp
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.SummaryEntity
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.engine.TxnStatus
import com.prash.kosh.security.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Removes anything that identifies a person or an account before text leaves the phone.
 * Kept: amounts, dates, merchant/brand words, bank names, transaction words.
 */
object Masker {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""\b[\w.+-]+@[\w.-]+\.[a-z]{2,}\b""", RegexOption.IGNORE_CASE) to "[email]",
        Regex("""\b[\w.-]{2,}@(ok\w+|ybl|ibl|axl|paytm|upi|apl|icici|hdfcbank|sbi|kotak|axisbank|yesbank|okaxis|okhdfcbank|okicici|oksbi|\w+)\b""", RegexOption.IGNORE_CASE) to "[upi-id]",
        Regex("""(?<!\d)(\+?91[\s-]?)?[6-9]\d{9}(?!\d)""") to "[phone]",
        Regex("""\b[A-Z]{4}0[A-Z0-9]{6}\b""") to "[ifsc]",
        Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""") to "[pan]",
        Regex("""(?i)\b(otp|password|pin|cvv)\b[^\n]{0,40}""") to "[secret removed]",
        // Long digit runs (account, card, policy, reference numbers): keep nothing but the last 2 digits.
        Regex("""(?<![\d.,])([Xx*•]*\d[\d\s-]{6,}\d)(?![\d.,])""") to "#",
        Regex("""(?i)\b(a/?c|acct|account|card|policy|folio)(\s*(no\.?|number|ending|ending with))?\s*[:#]?\s*[Xx*•]*\d{2,}""") to "$1 ••",
    )

    fun mask(text: String): String {
        var s = text
        for ((re, rep) in rules) {
            s = if (rep == "#") re.replace(s) { m -> val d = m.value.filter(Char::isDigit); "••" + d.takeLast(2) } else re.replace(s, rep)
        }
        return s.take(1200)
    }
}

/** Gemini REST client (free tier key from Google AI Studio). The key is kept wrapped by the Android Keystore. */
open class Gemini(private val key: () -> String?, private val model: () -> String) {
    class NotConfigured : Exception("Add a Gemini API key in AI assistant settings")
    class ApiError(val code: Int, msg: String) : Exception(msg)

    private val json = Json { ignoreUnknownKeys = true }

    /** Tries the chosen model, retrying once when Google is busy (503/500), then the other free models. */
    open suspend fun generate(system: String, prompt: String, jsonOut: Boolean = false, maxTokens: Int = 2048): String {
        val models = listOf(model()) + listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite").filter { it != model() }
        var last: ApiError? = null
        for (m in models) for (attempt in 0..1) {
            try { return call(m, system, prompt, jsonOut, maxTokens) } catch (e: ApiError) {
                last = e
                if (e.code == 400 || e.code == 403) throw e
                if ((e.code == 503 || e.code == 500) && attempt == 0) { kotlinx.coroutines.delay(2000); continue }
                break
            }
        }
        throw ApiError(last?.code ?: 503, (if (last?.code == 503 || last?.code == 500) "Google's Gemini servers are busy right now" else last?.message ?: "Gemini unavailable") + ". Try again in a few minutes.")
    }

    private suspend fun call(modelName: String, system: String, prompt: String, jsonOut: Boolean, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val k = key() ?: throw NotConfigured()
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) } }) }
            putJsonObject("generationConfig") {
                put("temperature", 0.2); put("maxOutputTokens", maxTokens)
                if (jsonOut) put("responseMimeType", "application/json")
            }
        }
        val c = (URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", k)
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            throw ApiError(code, when (code) {
                400, 403 -> "The Gemini key was not accepted. Check it in AI assistant settings."
                404 -> "Model '$modelName' not found"
                429 -> "Free-tier limit reached for now"
                else -> "Gemini error $code: ${err.take(200)}"
            })
        }
        val out = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
        out["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() } ?: ""
    }
}

/** What the AI suggested for one unknown message, checked against the original text. */
data class AiSuggestion(val rawId: Long, val type: String, val direction: String, val amountPaise: Long, val merchant: String?, val categoryId: String?, val dateMillis: Long, val confidence: Int)

/**
 * The three AI agents. All of them see masked or summarised data only; nothing they return is saved
 * without passing the same checks as rule-based captures, and AI-read transactions always wait in
 * Review for the user's approval.
 */
class AiAgents(
    private val context: Context,
    private val dbProvider: () -> AppDatabase = { KoshApp.get(context).db },
    private val repoProvider: () -> com.prash.kosh.data.Repository = { KoshApp.get(context).repo },
) {
    private val p = context.getSharedPreferences("kosh_ai", Context.MODE_PRIVATE)
    private val db get() = dbProvider()
    private val ist = ZoneId.of("Asia/Kolkata")

    var model: String
        get() = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = p.edit { putString("model", v.trim().ifEmpty { DEFAULT_MODEL }) }
    var enabled: Boolean
        get() = p.getBoolean("enabled", true); set(v) = p.edit { putBoolean("enabled", v) }
    val hasKey get() = p.contains("key")

    fun setKey(key: String?) = p.edit { if (key.isNullOrBlank()) remove("key") else putString("key", KeyManager.wrap(key.trim().toByteArray())) }
    private fun key(): String? = if (!enabled) null else p.getString("key", null)?.let { String(KeyManager.unwrap(it)) }

    var gemini: Gemini = Gemini(::key) { model }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        val MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite")
        private const val SYSTEM = "You are Kosh, a careful personal-finance assistant for one user in India. Amounts are Indian rupees (₹). " +
            "Financial year runs April to March. Personal identifiers have been removed from the data on purpose; never ask for them. " +
            "Never ask for or mention passwords, PINs or OTPs. Be brief and concrete."
    }

    suspend fun test(): String = gemini.generate(SYSTEM, "Reply with the single word OK.", maxTokens = 10).trim()

    // ---------------------------------------------------------------- 1. unknown messages

    /** Ignored messages that still look like money movement: worth a second look. */
    suspend fun unknownMessages(limit: Int = 20) = db.rawMessages().all()
        .filter { it.outcome == "IGNORED" && it.ignoreReason !in setOf("OTP", "PROMOTIONAL", "COLLECT_REQUEST", "FAILED") && it.body.isNotBlank() && it.transactionId == null }
        .filter { Regex("""(?i)(rs\.?|inr|₹)\s*[\d,]+""").containsMatchIn(it.body) }
        .sortedByDescending { it.receivedAt }.take(limit)

    /** Sends masked unknown messages to Gemini and returns validated suggestions (nothing is saved here). */
    suspend fun readUnknown(): List<AiSuggestion> {
        val msgs = unknownMessages()
        if (msgs.isEmpty()) return emptyList()
        val cats = db.categories().all().filter { !it.hidden }
        val prompt = buildString {
            appendLine("Below are bank/payment messages my rules could not read. For each, decide if it records money actually moving (not an offer, reminder, OTP, failed or requested payment).")
            appendLine("Return a JSON array, one object per message: {\"i\": index, \"isTransaction\": bool, \"type\": \"EXPENSE|INCOME|TRANSFER|REFUND|INVESTMENT\", \"direction\": \"DEBIT|CREDIT\", \"amount\": number in rupees, \"merchant\": short name or null, \"category\": one id from the list or null, \"date\": \"yyyy-MM-dd\" or null, \"confidence\": 0-100}.")
            appendLine("Category ids: " + cats.joinToString(", ") { "${it.id} (${it.name})" })
            msgs.forEachIndexed { i, m -> appendLine("[$i] from ${m.sender.take(12)}: ${Masker.mask(m.body)}") }
        }
        val out = gemini.generate(SYSTEM, prompt, jsonOut = true)
        val arr = runCatching { Json.parseToJsonElement(out.trim().removePrefix("```json").removeSuffix("```")).jsonArray }.getOrElse { return emptyList() }
        val catIds = cats.map { it.id }.toSet()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val i = o["i"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val m = msgs.getOrNull(i) ?: return@mapNotNull null
            if (o["isTransaction"]?.jsonPrimitive?.contentOrNull != "true") return@mapNotNull null
            val amount = o["amount"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val paise = Math.round(amount * 100)
            // Validation: the amount must literally appear in the original message.
            if (paise <= 0 || !amountAppears(m.body, paise)) return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.contentOrNull?.uppercase()?.takeIf { it in setOf("EXPENSE", "INCOME", "TRANSFER", "REFUND", "INVESTMENT") } ?: return@mapNotNull null
            val dir = o["direction"]?.jsonPrimitive?.contentOrNull?.uppercase()?.takeIf { it == "DEBIT" || it == "CREDIT" } ?: if (type == "INCOME" || type == "REFUND") "CREDIT" else "DEBIT"
            val date = o["date"]?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.takeIf { !it.isAfter(LocalDate.now(ist)) && it.isAfter(LocalDate.now(ist).minusYears(2)) }
                ?.atTime(12, 0)?.atZone(ist)?.toInstant()?.toEpochMilli() ?: m.receivedAt
            AiSuggestion(m.id, type, dir, paise, o["merchant"]?.jsonPrimitive?.contentOrNull?.take(40),
                o["category"]?.jsonPrimitive?.contentOrNull?.takeIf { it in catIds }, date,
                (o["confidence"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(0, 79))
        }
    }

    private fun amountAppears(body: String, paise: Long): Boolean {
        val nums = Regex("""\d[\d,]*(\.\d{1,2})?""").findAll(body).mapNotNull { it.value.replace(",", "").toDoubleOrNull() }
        return nums.any { Math.round(it * 100) == paise }
    }

    /** Saves accepted suggestions to the Review queue (never straight into totals). */
    suspend fun saveSuggestions(list: List<AiSuggestion>): Int {
        val repo = repoProvider()
        var n = 0
        for (s in list) {
            val raw = db.rawMessages().get(s.rawId) ?: continue
            if (raw.transactionId != null) continue
            val now = System.currentTimeMillis()
            val id = repo.addForReview(TransactionEntity(type = s.type, direction = s.direction, status = TxnStatus.NEEDS_REVIEW.name, amountPaise = s.amountPaise,
                dateMillis = s.dateMillis, merchantName = s.merchant, categoryId = s.categoryId, confidence = s.confidence,
                note = "Read by AI — please check", createdAt = now, updatedAt = now), raw.source)
            db.rawMessages().update(raw.copy(outcome = "RECOGNISED", transactionId = id, confidence = s.confidence))
            n++
        }
        return n
    }

    // ---------------------------------------------------------------- shared context (aggregates only)

    /** Month-level aggregates for the last 12 months plus archived months; no individual transactions. */
    suspend fun financialContext(today: LocalDate = LocalDate.now(ist)): String = buildString {
        val cats = db.categories().all().associateBy { it.id }
        appendLine("Today: $today")
        val archived = db.extras().allArchive().sortedBy { it.month }
        if (archived.isNotEmpty()) {
            appendLine("Archived months (totals in ₹): month, income, expense, invested")
            archived.takeLast(24).forEach { appendLine("${it.month}, ${it.income / 100}, ${it.expense / 100}, ${it.invested / 100}") }
        }
        appendLine("Recent months (₹): month | income | expense | invested | top spending categories")
        for (k in 11 downTo 0) {
            val ym = YearMonth.from(today).minusMonths(k.toLong())
            val from = ym.atDay(1).atStartOfDay(ist).toInstant().toEpochMilli()
            val to = ym.plusMonths(1).atDay(1).atStartOfDay(ist).toInstant().toEpochMilli() - 1
            val t = db.transactions().countedBetween(from, to)
            if (t.isEmpty()) continue
            fun sum(type: String) = t.filter { it.type == type }.sumOf { it.amountPaise } / 100
            val top = db.transactions().expenseByTop(from, to).sortedByDescending { it.total }.take(6)
                .joinToString(", ") { "${cats[it.topCategory]?.name ?: it.topCategory} ${it.total / 100}" }
            appendLine("$ym | ${sum("INCOME")} | ${sum("EXPENSE")} | ${sum("INVESTMENT")} | $top")
            if (k <= 2) {
                val merchants = t.filter { it.type == "EXPENSE" }.groupBy { it.merchantName ?: "Unknown" }
                    .mapValues { e -> e.value.sumOf { it.amountPaise } / 100 }.entries.sortedByDescending { it.value }.take(8)
                appendLine("  top merchants $ym: " + merchants.joinToString(", ") { "${it.key} ${it.value}" })
            }
        }
        val budgets = db.budgets().all()
        if (budgets.isNotEmpty()) appendLine("Monthly budgets (₹): " + budgets.joinToString(", ") { "${cats[it.categoryId]?.name ?: it.categoryId} ${it.monthlyLimitPaise / 100}" })
        val inv = db.investments().all()
        if (inv.isNotEmpty()) appendLine("Policies/investments: " + inv.joinToString("; ") { "${it.kind} ${it.name.replace(Regex("\\d{4,}"), "••")}" })
        val goals = db.goals().all()
        if (goals.isNotEmpty()) appendLine("Goals (₹ target): " + goals.joinToString(", ") { "${it.name} ${it.targetPaise / 100}" })
    }

    // ---------------------------------------------------------------- 2. questions

    suspend fun ask(question: String): String {
        val prompt = "My finances (aggregated; individual identifiers removed):\n${financialContext()}\n\nQuestion: ${Masker.mask(question)}\n" +
            "Answer from the data only. If the data cannot answer it, say what is missing. Use ₹ and Indian number formatting."
        return gemini.generate(SYSTEM, prompt, maxTokens = 1024).trim()
    }

    // ---------------------------------------------------------------- 3. monthly summary

    /** Summary for [month] (default: last month). Uses AI when a key is set, otherwise a plain local summary. */
    suspend fun monthlySummary(month: YearMonth = YearMonth.now(ist).minusMonths(1), force: Boolean = false): SummaryEntity {
        if (!force) db.extras().summary(month.toString())?.let { return it }
        val local = localSummary(month)
        val text = if (key() != null) runCatching {
            gemini.generate(SYSTEM, "Write my monthly money summary for $month in at most 8 short bullet points: income vs spending, " +
                "biggest categories and changes from the previous month, budgets exceeded, recurring vs discretionary, and one practical suggestion.\n\n" +
                "Facts:\n$local\n\nContext:\n${financialContext(month.atEndOfMonth())}", maxTokens = 900).trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } else null
        val s = SummaryEntity(id = db.extras().summary(month.toString())?.id ?: 0, month = month.toString(), text = text ?: local,
            source = if (text != null) "AI" else "LOCAL", createdAt = System.currentTimeMillis())
        db.extras().upsertSummary(s)
        return db.extras().summary(month.toString()) ?: s
    }

    private suspend fun localSummary(month: YearMonth): String {
        val cats = db.categories().all().associateBy { it.id }
        fun range(ym: YearMonth) = ym.atDay(1).atStartOfDay(ist).toInstant().toEpochMilli() to ym.plusMonths(1).atDay(1).atStartOfDay(ist).toInstant().toEpochMilli() - 1
        val (f, t) = range(month); val (pf, pt) = range(month.minusMonths(1))
        val cur = db.transactions().countedBetween(f, t); val prev = db.transactions().countedBetween(pf, pt)
        fun sum(l: List<TransactionEntity>, type: String) = l.filter { it.type == type }.sumOf { it.amountPaise }
        val inc = sum(cur, "INCOME"); val exp = sum(cur, "EXPENSE"); val pexp = sum(prev, "EXPENSE")
        val top = db.transactions().expenseByTop(f, t).sortedByDescending { it.total }.take(3)
        return buildString {
            appendLine("• Income ₹${inc / 100}, spending ₹${exp / 100}" + if (inc > 0) ", saved ${((inc - exp) * 100 / inc)}% of income." else ".")
            if (pexp > 0) appendLine("• Spending ${if (exp >= pexp) "up" else "down"} ${kotlin.math.abs(exp - pexp) * 100 / pexp}% from ${month.minusMonths(1)}.")
            if (top.isNotEmpty()) appendLine("• Biggest: " + top.joinToString(", ") { "${cats[it.topCategory]?.name ?: it.topCategory} ₹${it.total / 100}" } + ".")
            appendLine("• ${cur.size} transactions recorded.")
        }.trim()
    }
}
