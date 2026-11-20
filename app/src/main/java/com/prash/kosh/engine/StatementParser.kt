package com.prash.kosh.engine

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads bank and card statements exported as CSV (the format every Indian net-banking site offers).
 * Finds the header row, works out which columns hold date / narration / debit / credit / amount,
 * and turns each row into a message-like line so the normal recognition, duplicate merging and
 * category rules apply. The web app has the same logic for CSV, Excel and PDF statements.
 */
object StatementParser {
    data class Row(val dateMillis: Long, val narration: String, val amountPaise: Long, val debit: Boolean, val reference: String?)

    private val IST = ZoneId.of("Asia/Kolkata")
    private val dateFormats = listOf("dd/MM/yyyy", "dd/MM/yy", "dd-MM-yyyy", "dd-MM-yy", "dd-MMM-yyyy", "dd-MMM-yy", "dd MMM yyyy", "dd MMM yy", "yyyy-MM-dd", "dd.MM.yyyy", "d/M/yyyy", "d MMM yyyy")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun parseDate(s: String): LocalDate? {
        val t = s.trim().take(11).trim().replace(Regex("""\s+"""), " ")
        for (f in dateFormats) runCatching { return LocalDate.parse(t, f) }
        for (f in dateFormats) runCatching { return LocalDate.parse(s.trim(), f) }
        return null
    }

    fun splitCsv(line: String, sep: Char = ','): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> q = !q
                c == sep && !q -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString().trim(); return out
    }

    private fun money(s: String): Long? {
        val t = s.replace(Regex("""(?i)inr|rs\.?|₹|,|\s"""), "").removeSuffix("Dr").removeSuffix("Cr").removeSuffix("DR").removeSuffix("CR")
        if (t.isEmpty() || t == "-" ) return null
        return t.toBigDecimalOrNull()?.movePointRight(2)?.toLong()?.let { kotlin.math.abs(it) }?.takeIf { it > 0 }
    }

    /** Parses a whole CSV text. Rows that are not transactions (opening balance, totals) are skipped. */
    fun parseCsv(text: String): List<Row> {
        val lines = text.lines().filter { it.isNotBlank() }
        val sep = if (lines.take(30).sumOf { it.count { c -> c == ';' } } > lines.take(30).sumOf { it.count { c -> c == ',' } }) ';' else ','
        val rows = lines.map { splitCsv(it, sep) }
        return parseTable(rows)
    }

    /** Shared by CSV here and (in the web app) Excel/PDF tables. */
    fun parseTable(rows: List<List<String>>): List<Row> {
        val headerIdx = rows.indexOfFirst { r -> val l = r.map { it.lowercase() }; l.any { "date" in it } && l.any { it.contains("narration") || it.contains("description") || it.contains("particular") || it.contains("details") || it.contains("remarks") } }
        if (headerIdx < 0) return emptyList()
        val h = rows[headerIdx].map { it.lowercase() }
        fun col(vararg keys: String, not: String? = null) = h.indexOfFirst { c -> keys.any { it in c } && (not == null || not !in c) }
        val date = col("txn date", "transaction date", "tran date").takeIf { it >= 0 } ?: col("date", not = "value")
        val desc = col("narration", "description", "particular", "details", "remarks")
        val debit = col("withdrawal", "debit", "dr amount", "paid out")
        val credit = col("deposit", "credit", "cr amount", "paid in")
        val amount = col("amount", not = "balance")
        val drcr = h.indexOfFirst { it == "dr/cr" || it == "cr/dr" || it == "type" || it == "dr / cr" }
        val ref = col("ref", "chq", "cheque")
        val out = ArrayList<Row>()
        for (r in rows.drop(headerIdx + 1)) {
            fun at(i: Int) = if (i in r.indices) r[i] else ""
            val d = parseDate(at(date)) ?: continue
            val narration = at(desc).replace(Regex("""\s+"""), " ").trim()
            if (narration.isEmpty() || narration.contains("opening balance", true) || narration.contains("closing balance", true)) continue
            val dr = if (debit >= 0 && debit != amount) money(at(debit)) else null
            val cr = if (credit >= 0 && credit != amount) money(at(credit)) else null
            val (paise, isDebit) = when {
                dr != null -> dr to true
                cr != null -> cr to false
                amount >= 0 -> {
                    val raw = at(amount); val m = money(raw) ?: continue
                    val flag = at(drcr).lowercase()
                    m to when {
                        flag.startsWith("cr") || raw.endsWith("Cr", true) -> false
                        flag.startsWith("dr") || raw.endsWith("Dr", true) || raw.trim().startsWith("-") -> true
                        else -> true
                    }
                }
                else -> continue
            }
            out += Row(d.atTime(12, 0).atZone(IST).toInstant().toEpochMilli(), narration, paise, isDebit, at(ref).takeIf { it.length >= 6 })
        }
        return out
    }

    private val upiRef = Regex("""(?<!\d)\d{12}(?!\d)""")
    private val vpa = Regex("""^[\w.-]+@[\w.-]+$""")

    /** "UPI-SWIGGY-swiggy@icici-ICIC0000001-527312345678-Payment" -> ("SWIGGY", "527312345678"). */
    fun clean(narration: String): Pair<String, String?> {
        val ref = upiRef.find(narration)?.value
        val parts = narration.split('-', '/', '|').map { it.trim() }.filter { it.isNotEmpty() }
        val head = parts.firstOrNull()?.uppercase()
        val name = if (head != null && (head.startsWith("UPI") || head in setOf("NEFT CR", "NEFT DR", "IMPS", "NEFT", "RTGS", "POS", "ACH D", "ACH C", "NACH", "MMT")) && parts.size > 1) {
            parts.drop(1).firstOrNull { p -> p.any(Char::isLetter) && !vpa.matches(p) && !Regex("""^[A-Z]{4}0[A-Z0-9]{6}$""").matches(p) && p.uppercase() !in setOf("P2A", "P2M", "UPI", "DR", "CR") } ?: narration
        } else narration
        return name.take(60) to ref
    }

    /** Message-like text for the recognition engine. */
    fun toMessage(r: Row, bank: String?, last4: String?): String {
        val d = java.time.Instant.ofEpochMilli(r.dateMillis).atZone(IST).toLocalDate().format(DateTimeFormatter.ofPattern("dd-MM-yy"))
        val acct = last4?.let { " A/c XX$it" } ?: ""
        val amt = "Rs." + java.math.BigDecimal(r.amountPaise).movePointLeft(2).toPlainString()
        val (name, upi) = clean(r.narration)
        val ref = upi ?: r.reference?.trimStart('0')?.takeIf { it.length >= 6 }
        val method = when { r.narration.startsWith("UPI", true) -> " UPI"; r.narration.contains("NEFT", true) -> " NEFT"; r.narration.contains("IMPS", true) -> " IMPS"; r.narration.startsWith("POS", true) -> " card"; else -> "" }
        val kind = if (r.debit) "debited from" else "credited to"
        // Words like SALARY / REFUND / ATM stay in, so the normal rules can classify the row.
        val extra = if (name != r.narration) " (${r.narration.take(80)})" else ""
        return "$amt $kind${bank?.let { " $it" } ?: ""}$acct on $d$method. Info: $name$extra" + (ref?.let { " Ref $it" } ?: "")
    }
}
