package com.prash.kosh.engine

/**
 * A message format the owner taught Kosh ("Teach Kosh this message"): e.g. a salary credit or
 * an OPD reimbursement. A message matches when it comes from the same sender (if set) and
 * contains every identifying word. Matches skip the normal rules and always wait in Check.
 */
data class TaughtPattern(
    val id: Long,
    val name: String,                 // shown as the merchant / payer, e.g. "Salary"
    val sender: String?,              // DLT header code such as "HDFCBK"; null = any sender
    val keywords: List<String>,       // lower-case words; all must appear (whole words)
    val direction: Direction,
    val type: TxnType,
    val categoryId: String,
    val amountIndex: Int,             // which Rs/INR/₹ amount in the message is the transaction amount
    val accountId: Long?,             // fixed account, or null = use the account in the message
)

object Taught {

    /** Confidence given to taught matches: inside the Check band so they wait for the owner. */
    const val CONFIDENCE = 90

    private val amountRx = Regex("""(?:rs\.?|inr|₹)\s*:?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)

    data class AmountHit(val paise: Long, val text: String, val start: Int)

    /** Every currency amount in the message, in order (Rs 1,234.50 / INR 500 / ₹99). */
    fun amounts(body: String): List<AmountHit> = amountRx.findAll(body).mapNotNull { m ->
        val num = m.groupValues[1].replace(",", "").trimEnd('.')
        val paise = num.toBigDecimalOrNull()?.movePointRight(2)?.toLong() ?: return@mapNotNull null
        if (paise <= 0) null else AmountHit(paise, m.value.trim(), m.range.first)
    }.toList()

    /** "VM-HDFCBK-S" -> "HDFCBK"; app packages and plain names are kept as they are (upper case). */
    fun senderCode(sender: String): String =
        sender.trim().uppercase().replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[A-Z]$"), "")

    private val stopWords = setOf(
        "the", "and", "for", "your", "you", "has", "have", "been", "was", "are", "with", "from", "this", "that", "not",
        "our", "its", "via", "into", "per", "dear", "customer", "please", "call", "info", "avl", "bal", "balance",
        "available", "ref", "txn", "inr", "acct", "account", "bank", "date", "time", "on", "rs", "amt", "amount",
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec", "sms", "www", "http", "https", "com",
    )

    /** Words worth ticking as identifiers, in message order (letters only, 3+ chars, no filler). */
    fun candidateWords(body: String): List<String> =
        Regex("""[A-Za-z]{3,}""").findAll(body).map { it.value.lowercase() }
            .filter { it !in stopWords }.distinct().take(40).toList()

    /** Words Kosh pre-ticks because they usually name the kind of payment. */
    val hintWords = setOf(
        "salary", "sal", "payroll", "reimbursement", "reimb", "opd", "claim", "medical", "bonus", "stipend",
        "pension", "incentive", "allowance", "arrears", "dividend", "settlement",
    )

    /** A sensible starting category for the ticked words. */
    fun suggestCategory(words: Collection<String>, direction: Direction): String = when {
        direction == Direction.DEBIT -> SeedData.FALLBACK_EXPENSE
        words.any { it in setOf("reimbursement", "reimb", "opd", "claim", "medical") } -> REIMBURSEMENT_CATEGORY
        words.any { it in setOf("salary", "sal", "payroll", "bonus", "arrears", "incentive", "allowance", "stipend") } -> "income.salary"
        else -> SeedData.FALLBACK_INCOME
    }

    const val REIMBURSEMENT_CATEGORY = "income.reimbursement"

    private fun wordRx(k: String) = Regex("""\b${Regex.escape(k)}\b""", RegexOption.IGNORE_CASE)

    fun matches(p: TaughtPattern, input: RawInput): Boolean {
        if (p.keywords.isEmpty()) return false
        if (p.sender != null && senderCode(input.sender) != p.sender) return false
        return p.keywords.all { wordRx(it).containsMatchIn(input.body) }
    }

    /** The most specific taught pattern that fits (most identifying words), if any. */
    fun find(patterns: List<TaughtPattern>, input: RawInput): TaughtPattern? =
        patterns.filter { matches(it, input) }.maxWithOrNull(compareBy({ it.keywords.size }, { if (it.sender != null) 1 else 0 }))

    /** Builds the recognition for a taught match; null if the message has no usable amount. */
    fun recognise(p: TaughtPattern, input: RawInput): Recognition? {
        val hits = amounts(input.body)
        val amount = hits.getOrNull(p.amountIndex)?.paise ?: hits.firstOrNull()?.paise ?: return null
        val parsed = runCatching { TransactionParser.parse(input) }.getOrNull()
        val fields = ParsedFields(
            amountPaise = amount, direction = p.direction, directionExplicit = true, merchantRaw = p.name,
            accountLast4 = parsed?.accountLast4, dateMillis = parsed?.dateMillis, reference = parsed?.reference,
            method = parsed?.method ?: PaymentMethod.OTHER, bank = parsed?.bank, templateId = "taught:${p.id}",
        )
        return Recognition(
            fields = fields, type = p.type,
            merchant = Merchant("taught${p.id}", p.name, p.categoryId),
            categoryId = p.categoryId, categorySource = CategorySource.USER_RULE,
            confidence = CONFIDENCE, knownSender = TransactionParser.isKnownSender(input),
            txnDateMillis = fields.dateMillis ?: input.receivedAtMillis,
            taughtPatternId = p.id, taughtAccountId = p.accountId, forcedStatus = TxnStatus.PENDING_VERIFICATION,
        )
    }
}
