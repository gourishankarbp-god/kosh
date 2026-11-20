package com.prash.kosh.engine

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pulls amount, direction, account, merchant, date and reference out of a bank SMS or a
 * payment-app notification. Bank- and app-specific templates are tried first; the generic
 * Indian-bank parser handles everything else.
 */
object TransactionParser {

    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    // ---------------------------------------------------------------- senders

    /** DLT header code (the part after "XX-") -> bank name. */
    private val bankHeaders = mapOf(
        "HDFCBK" to "HDFC", "HDFCBN" to "HDFC", "ICICIB" to "ICICI", "ICICIT" to "ICICI", "ICICIO" to "ICICI",
        "SBIINB" to "SBI", "SBIUPI" to "SBI", "SBICRD" to "SBI Card", "ATMSBI" to "SBI", "CBSSBI" to "SBI", "SBIPSG" to "SBI",
        "AXISBK" to "Axis", "AXISMR" to "Axis", "KOTAKB" to "Kotak", "KOTAKM" to "Kotak", "IDFCFB" to "IDFC First",
        "YESBNK" to "Yes Bank", "INDUSB" to "IndusInd", "PNBSMS" to "PNB", "BOBTXN" to "Bank of Baroda", "BOBSMS" to "Bank of Baroda",
        "CANBNK" to "Canara", "UNIONB" to "Union Bank", "FEDBNK" to "Federal", "AUBANK" to "AU Bank", "RBLBNK" to "RBL",
        "SCBANK" to "Standard Chartered", "HSBCIN" to "HSBC", "CITIBK" to "Citi", "AMEXIN" to "Amex", "ONECRD" to "OneCard",
        "JUPITR" to "Jupiter", "FIMONY" to "Fi", "PAYTMB" to "Paytm Payments Bank", "AIRBNK" to "Airtel Payments Bank",
        "IOBCHN" to "IOB", "BOIIND" to "Bank of India", "CENTBK" to "Central Bank", "UCOBNK" to "UCO", "KVBANK" to "KVB",
        "SIBSMS" to "South Indian Bank", "KBLBNK" to "Karnataka Bank", "KTKBNK" to "Karnataka Bank", "RBLCRD" to "RBL", "HSBCCC" to "HSBC", "DBSBNK" to "DBS", "EQUITS" to "Equitas", "UJJIVN" to "Ujjivan",
    )

    /** Payment and bank apps whose notifications we read. Anything else is ignored. */
    val notificationApps = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "com.dreamplug.androidapp" to "CRED",
        "in.amazon.mShop.android.shopping" to "Amazon Pay",
        "com.snapwork.hdfc" to "HDFC", "com.hdfcbank.payzapp" to "HDFC PayZapp",
        "com.csam.icici.bank.imobile" to "ICICI", "com.sbi.lotusintouch" to "SBI", "com.sbi.upi" to "SBI",
        "com.axis.mobile" to "Axis", "com.msf.kbank.mobile" to "Kotak", "com.idfcfirstbank.optimus" to "IDFC First",
    )

    private val headerRegex = Regex("""^[A-Z]{2}-?([A-Z0-9]{6})(?:-[A-Z])?$""")

    fun bankForSender(sender: String): String? {
        val code = headerRegex.matchEntire(sender.trim().uppercase())?.groupValues?.get(1) ?: return null
        return bankHeaders[code]
    }

    /** Banks that sign the message body ("-Karnataka Bank"), for headers not in [bankHeaders]. */
    private val signatures = listOf(
        "Karnataka Bank" to "Karnataka Bank", "Union Bank of India" to "Union Bank", "HDFC Bank" to "HDFC", "HSBC" to "HSBC",
        "RBL Bank" to "RBL", "ICICI Bank" to "ICICI", "Axis Bank" to "Axis", "Kotak" to "Kotak", "SBI" to "SBI",
    )

    fun bankFromSignature(body: String): String? =
        signatures.firstOrNull { (sig, _) -> Regex("""\b${Regex.escape(sig)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(body) }?.second

    private val dltHeader = Regex("""^[A-Z]{2}-?[A-Z0-9]{6}(?:-[A-Z])?$""")

    fun isKnownSender(input: RawInput): Boolean = when (input.source) {
        // A DLT business header plus a bank's own signature in the text counts as a known bank.
        CaptureSource.SMS -> bankForSender(input.sender) != null ||
            (dltHeader.matches(input.sender.trim().uppercase()) && bankFromSignature(input.body) != null)
        CaptureSource.NOTIFICATION -> input.sender in notificationApps
        CaptureSource.IMPORT -> true // the user's own downloaded statement
        else -> false
    }

    // ---------------------------------------------------------------- templates

    private const val AMT = """(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private const val ACC = """[xX*.]*(?<account>[0-9]{3,6})"""

    private class Template(val id: String, val direction: Direction, val method: PaymentMethod, pattern: String) {
        val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    /**
     * Modelled on common 2024–2026 formats. Banks change wording, so every template is
     * backed by the generic parser below; send sample messages to tune these.
     */
    private val smsTemplates = listOf(
        Template("hdfc_upi_sent", Direction.DEBIT, PaymentMethod.UPI,
            """Sent Rs\.?\s?$AMT\s+From HDFC Bank A/C\s*$ACC\s+To (?<merchant>.+?)\s+On (?<date>\d{1,2}/\d{1,2}/\d{2,4})\s+Ref (?<ref>\d+)"""),
        Template("hdfc_card_spent", Direction.DEBIT, PaymentMethod.CARD,
            """Spent Rs\.?\s?$AMT\s+(?:From|On) HDFC Bank Card\s*$ACC\s+At (?<merchant>.+?)\s+On (?<date>\d{4}-\d{2}-\d{2})"""),
        Template("hdfc_credit", Direction.CREDIT, PaymentMethod.UPI,
            """Rs\.?\s?$AMT credited to HDFC Bank A/c\s*$ACC on (?<date>\d{1,2}-\d{1,2}-\d{2,4}) (?:by|from) (?:a/c linked to )?(?:VPA )?(?<merchant>\S+?)\s*\(?(?:UPI Ref No\.?|UPI)\s?(?<ref>\d+)"""),
        Template("icici_upi_debit", Direction.DEBIT, PaymentMethod.UPI,
            """ICICI Bank Acc(?:oun)?t\s*$ACC debited for Rs\.?\s?$AMT on (?<date>\d{1,2}-[A-Za-z]{3}-\d{2,4}); (?<merchant>.+?) credited\. UPI:?\s?(?<ref>\d+)"""),
        Template("icici_card_spent", Direction.DEBIT, PaymentMethod.CARD,
            """INR $AMT spent (?:using|on) ICICI Bank Card\s*$ACC on (?<date>\d{1,2}-[A-Za-z]{3}-\d{2,4}) (?:on|at) (?<merchant>.+?)\.\s*Avl"""),
        Template("icici_credit", Direction.CREDIT, PaymentMethod.UPI,
            """ICICI Bank Acc(?:oun)?t\s*$ACC (?:is )?credited (?:with|for) Rs\.?\s?$AMT on (?<date>\d{1,2}-[A-Za-z]{3}-\d{2,4})(?: (?:from|by) (?<merchant>.+?)\.)?(?:.*?UPI:?\s?(?<ref>\d+))?"""),
        Template("sbi_upi_debit", Direction.DEBIT, PaymentMethod.UPI,
            """A/C\s*$ACC debited by $AMT on date (?<date>\d{1,2}[A-Za-z]{3}\d{2,4}) trf to (?<merchant>.+?) Ref\s?no\.? ?(?<ref>\d+)"""),
        Template("sbi_upi_credit", Direction.CREDIT, PaymentMethod.UPI,
            """A/c\s*$ACC-?\s?credited by Rs\.?\s?$AMT on (?<date>\d{1,2}[A-Za-z]{3}\d{2,4}) (?:transfer from|by) (?<merchant>.+?) Ref\s?No\.? ?(?<ref>\d+)"""),
        Template("axis_upi_debit", Direction.DEBIT, PaymentMethod.UPI,
            """INR $AMT debited\s+A/c no\.\s*$ACC\s+(?<date>\d{1,2}-\d{1,2}-\d{2,4}),? [0-9:]+\s+UPI/P2[AM]/(?<ref>\d+)/(?<merchant>[^\n/]+)"""),
        Template("axis_card_spent", Direction.DEBIT, PaymentMethod.CARD,
            """Spent INR $AMT\s+Axis Bank Card no\.\s*$ACC\s+(?<date>\d{1,2}-\d{1,2}-\d{2,4}) [0-9:]+(?: IST)?\s+(?<merchant>[^\n]+?)\s+Avl"""),
        // HSBC credit card (real messages, Nov 2026)
        Template("hsbc_card_used", Direction.DEBIT, PaymentMethod.CARD,
            """HSBC Credit Card\s*$ACC used at (?<merchant>.+?) for INR $AMT on (?<date>\d{1,2}/\d{1,2}/\d{2,4})"""),
        // Karnataka Bank (real message, Oct 2026). "x015723x" -> last 4 = 5723.
        Template("kbl_neft_credit", Direction.CREDIT, PaymentMethod.NEFT,
            """Your Account\s*[xX*]*\d*?(?<account>\d{4})[xX*]*\s+has been CREDITED for Rs\.?\s?$AMT/?-?\s+(?:NEFT|IMPS|RTGS|UPI)[-/](?<merchant>[^/]+?)\s*/.*?/(?<ref>[A-Z0-9]{8,})\.\s+Balance is Rs\.?[0-9,.]+?\.?\s+(?<date>\d{1,2}-\d{1,2}-\d{4})"""),
        Template("kbl_debit", Direction.DEBIT, PaymentMethod.OTHER,
            """Your Account\s*[xX*]*\d*?(?<account>\d{4})[xX*]*\s+has been DEBITED for Rs\.?\s?$AMT/?-?\s+(?:(?:NEFT|IMPS|RTGS|UPI)[-/])?(?<merchant>[^/.]+?)(?:\s*/[^.]*?/(?<ref>[A-Z0-9]{8,}))?\.\s+Balance is Rs\.?[0-9,.]+?\.?\s+(?<date>\d{1,2}-\d{1,2}-\d{4})"""),
        // Union Bank of India (from real messages, Nov 2026)
        Template("union_debit", Direction.DEBIT, PaymentMethod.OTHER,
            """A/c\s*\*?$ACC Dr Rs\.?:?\s?$AMT on (?<date>\d{1,2}-\d{1,2}-\d{2,4})(?: [0-9:]+)? by [A-Za-z ]{1,20}?\s?(?<ref>\d{6,})(?: Fv\.?\s?(?<merchant>[^,\n]+))?"""),
        Template("union_credit", Direction.CREDIT, PaymentMethod.OTHER,
            """A/c\s*\*?$ACC Credited for Rs\.?:?\s?$AMT on (?<date>\d{1,2}-\d{1,2}-\d{2,4})(?: [0-9:]+)? by [A-Za-z ]{1,20}? ref no (?<ref>\d+)(?:.*?(?:from|Fv\.?)\s?(?<merchant>[A-Za-z][^,.\n]+))?"""),
        Template("kotak_upi_sent", Direction.DEBIT, PaymentMethod.UPI,
            """Sent Rs\.?\s?$AMT from Kotak Bank AC\s*$ACC to (?<merchant>\S+) on (?<date>\d{1,2}-\d{1,2}-\d{2,4})\.?\s?UPI Ref:? ?(?<ref>\d+)"""),
        Template("kotak_upi_received", Direction.CREDIT, PaymentMethod.UPI,
            """Received Rs\.?\s?$AMT in your Kotak Bank AC\s*$ACC from (?<merchant>\S+) on (?<date>\d{1,2}-\d{1,2}-\d{2,4})\.?\s?UPI Ref:? ?(?<ref>\d+)"""),
    )

    private const val NAMT = """(?:₹|Rs\.?|INR)\s?(?<amount>[0-9][0-9,]*(?:\.[0-9]{1,2})?)"""

    private val notificationTemplates = listOf(
        Template("app_paid_to", Direction.DEBIT, PaymentMethod.UPI, """(?:You )?paid $NAMT (?:to|at) (?<merchant>[^\n.]+)"""),
        Template("app_amount_paid_to", Direction.DEBIT, PaymentMethod.UPI, """$NAMT (?:paid|sent) (?:to|at) (?<merchant>[^\n.]+)"""),
        Template("app_sent_to", Direction.DEBIT, PaymentMethod.UPI, """Sent $NAMT to (?<merchant>[^\n.]+)"""),
        Template("app_received_from", Direction.CREDIT, PaymentMethod.UPI, """Received $NAMT from (?<merchant>[^\n.]+)"""),
        Template("app_x_sent_you", Direction.CREDIT, PaymentMethod.UPI, """(?<merchant>[^\n]+?) (?:sent|paid) you $NAMT"""),
        Template("app_amount_received", Direction.CREDIT, PaymentMethod.UPI, """$NAMT received from (?<merchant>[^\n.]+)"""),
    )

    // ---------------------------------------------------------------- generic

    private val amountRx = Regex("""(?:rs\.?|inr|₹)\s*:?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val bareAmountRx = Regex("""(?:debited|credited|spent|paid|sent|received|withdrawn)\s+(?:by|for|of|with)?\s*([0-9][0-9,]*\.[0-9]{1,2})\b""", RegexOption.IGNORE_CASE)
    private val balanceContext = Regex("""(?:bal(?:ance)?|limit|avl|available|outstanding|due)[^0-9]{0,15}$""", RegexOption.IGNORE_CASE)

    private val debitWords = Regex("""\b(?:debited|debit(?:ed)? by|spent|paid|sent|withdrawn|withdrawal|purchase|deducted|charged|used for|used at|used on|txn of|dr\.?|wdl|transferred to)\b""", RegexOption.IGNORE_CASE)
    private val creditWords = Regex("""\b(?:credited|received|deposited|cr\.?|refund(?:ed)?|reversed|reversal|added to)\b""", RegexOption.IGNORE_CASE)

    private val accountRxs = listOf(
        Regex("""(?:a/?c|acct|account|card)(?:\s*no\.?)?(?:\s*ending)?(?:\s*(?:with|in))?\s*[:#]?\s*[xX*.]+\s*([0-9]{3,6})""", RegexOption.IGNORE_CASE),
        Regex("""ending\s*(?:with|in)?\s*([0-9]{4})""", RegexOption.IGNORE_CASE),
        Regex("""\b[xX*]{2,}([0-9]{3,6})\b"""),
    )

    private val refRx = Regex("""(?:upi\s*ref(?:erence)?(?:\s*no)?|ref(?:erence)?\s*(?:no|number|id)?|rrn|txn\s*(?:id|no)|transaction\s*(?:id|no)|imps\s*ref|utr|upi)\s*[.:#-]?\s*([0-9]{6,})""", RegexOption.IGNORE_CASE)

    private val merchantRxs = listOf(
        Regex("""\bVPA\s+([\w.\-]+@[a-zA-Z]+)""", RegexOption.IGNORE_CASE),
        Regex("""\bUPI/(?:P2[AM]|DR|CR)/[0-9]+/([^/\n]+)""", RegexOption.IGNORE_CASE),
        Regex("""\binfo:?\s*(?:upi/|imps/|neft/|ach/)?([A-Za-z][^\n.;]{1,40})""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:at|@)\s+([A-Za-z0-9][A-Za-z0-9 &'.\-*_]{1,40}?)(?=\s+on\b|\s+via\b|\s+ref\b|\s+avl\b|\s+txn\b|\.\s|[.,;(]|$)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:paid to|sent to|trf to|transfer(?:red)? to|towards|to)\s+(?:vpa\s+)?([A-Za-z0-9][A-Za-z0-9 &'.@\-_]{1,40}?)(?=\s+on\b|\s+via\b|\s+ref\b|\s+upi\b|\s+(?:has|have|is|was|for|credited|debited)\b|\.\s|[,;(]|$)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:from|by)\s+(?:vpa\s+)?([A-Za-z0-9][A-Za-z0-9 &'.@\-_]{1,40}?)(?=\s+on\b|\s+via\b|\s+ref\b|\s+upi\b|\s+(?:has|have|is|was|for|credited|debited)\b|\.\s|[,;(]|$)""", RegexOption.IGNORE_CASE),
        Regex("""\b([\w.\-]{3,}@[a-zA-Z]{2,15})\b(?!\.)"""),
    )
    private val notAMerchant = Regex("""^(?:your|ur|the|a/?c|acct|account|card|bank|self|us|you|rs|inr|date|upi|ref|mobile|hdfc bank|icici bank|sbi|axis bank|kotak bank|a/c linked to)\b""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- dates

    private val dateRxs = listOf(
        Regex("""\b(\d{4})-(\d{2})-(\d{2})\b"""),
        Regex("""\b(\d{1,2})[-/ ]?([A-Za-z]{3})[a-z]*[-/ ,]?\s?(\d{2,4})\b"""),
        Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b"""),
    )
    private val months = (1..12).associateBy { java.time.Month.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() }

    fun parseDate(text: String, receivedAtMillis: Long): Long? {
        val received = java.time.Instant.ofEpochMilli(receivedAtMillis).atZone(IST)
        for ((i, rx) in dateRxs.withIndex()) {
            for (m in rx.findAll(text)) {
                val g = m.groupValues
                val date = try {
                    when (i) {
                        0 -> LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                        1 -> {
                            val mon = months[g[2].lowercase().take(3)] ?: continue
                            LocalDate.of(year(g[3]), mon, g[1].toInt())
                        }
                        else -> LocalDate.of(year(g[3]), g[2].toInt(), g[1].toInt()) // Indian banks use dd/mm
                    }
                } catch (e: Exception) { continue }
                val receivedDate = received.toLocalDate()
                // Reject dates in the future or more than 2 years before the message arrived.
                if (date.isAfter(receivedDate.plusDays(1)) || date.isBefore(receivedDate.minusYears(2))) continue
                val time = if (date == receivedDate) received.toLocalTime() else LocalTime.NOON
                return LocalDateTime.of(date, time).atZone(IST).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun year(s: String): Int = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    // ---------------------------------------------------------------- entry point

    fun parse(input: RawInput): ParsedFields? {
        val body = input.body.replace(' ', ' ')
        val bank = when (input.source) {
            CaptureSource.SMS, CaptureSource.SHARED -> bankForSender(input.sender) ?: bankFromSignature(body)
            CaptureSource.NOTIFICATION -> notificationApps[input.sender]
            else -> null
        }
        val templates = if (input.source == CaptureSource.NOTIFICATION) notificationTemplates else smsTemplates
        for (t in templates) {
            val m = t.regex.find(body) ?: continue
            val amount = Money.parseToPaise(m.groups["amount"]?.value ?: continue) ?: continue
            val merchant = group(m, "merchant")?.trim()?.trimEnd('.', ',')
            val dateText = group(m, "date")
            return ParsedFields(
                amountPaise = amount,
                direction = t.direction,
                directionExplicit = true,
                merchantRaw = merchant,
                accountLast4 = group(m, "account")?.takeLast(4) ?: findAccount(body),
                dateMillis = dateText?.let { parseDate(it, input.receivedAtMillis) } ?: parseDate(body, input.receivedAtMillis),
                reference = group(m, "ref") ?: findRef(body),
                method = t.method,
                bank = bank,
                templateId = t.id,
            )
        }
        return parseGeneric(body, bank, input.receivedAtMillis)
    }

    private fun group(m: MatchResult, name: String): String? =
        try { m.groups[name]?.value?.takeIf { it.isNotBlank() } } catch (e: IllegalArgumentException) { null }

    private fun parseGeneric(body: String, bank: String?, receivedAt: Long): ParsedFields? {
        val amount = findAmount(body) ?: return null
        // "credit card" and "debit card" name a card, not a direction.
        val scrubbed = body.replace(Regex("""(?:credit|debit)\s+card""", RegexOption.IGNORE_CASE), "card")
        val d = debitWords.find(scrubbed)
        val c = creditWords.find(scrubbed)
        val direction = when {
            d != null && c != null -> if (d.range.first <= c.range.first) Direction.DEBIT else Direction.CREDIT
            d != null -> Direction.DEBIT
            c != null -> Direction.CREDIT
            else -> Direction.DEBIT
        }
        return ParsedFields(
            amountPaise = amount,
            direction = direction,
            directionExplicit = d != null || c != null,
            merchantRaw = findMerchant(body, direction),
            accountLast4 = findAccount(body),
            dateMillis = parseDate(body, receivedAt),
            reference = findRef(body),
            method = findMethod(body),
            bank = bank,
            templateId = null,
        )
    }

    fun findAmount(body: String): Long? {
        for (m in amountRx.findAll(body)) {
            val before = body.substring(0, m.range.first).takeLast(25)
            if (balanceContext.containsMatchIn(before)) continue
            Money.parseToPaise(m.groupValues[1])?.let { return it }
        }
        bareAmountRx.find(body)?.let { return Money.parseToPaise(it.groupValues[1]) }
        return null
    }

    private fun findAccount(body: String): String? {
        for (rx in accountRxs) rx.find(body)?.let { return it.groupValues[1].takeLast(4) }
        return null
    }

    private fun findRef(body: String): String? = refRx.find(body)?.groupValues?.get(1)

    private fun findMerchant(body: String, direction: Direction): String? {
        for ((i, rx) in merchantRxs.withIndex()) {
            // "from X" names a payer, so only use it for credits; "to X" only for debits.
            if (i == 4 && direction == Direction.CREDIT) continue
            if (i == 5 && direction == Direction.DEBIT) continue
            for (m in rx.findAll(body)) {
                val v = m.groupValues[1].trim().trimEnd('.', ',', '-')
                if (v.length < 2 || notAMerchant.containsMatchIn(v) || v.all { it.isDigit() || it == ' ' }) continue
                return v
            }
        }
        return null
    }

    private fun findMethod(body: String): PaymentMethod {
        val b = body.lowercase()
        return when {
            "upi" in b || Regex("""@[a-z]{2,}""").containsMatchIn(b) -> PaymentMethod.UPI
            "atm" in b -> PaymentMethod.ATM
            "neft" in b -> PaymentMethod.NEFT
            "imps" in b -> PaymentMethod.IMPS
            "rtgs" in b -> PaymentMethod.RTGS
            "card" in b -> PaymentMethod.CARD
            "net banking" in b || "netbanking" in b -> PaymentMethod.NETBANKING
            else -> PaymentMethod.OTHER
        }
    }
}
