package com.prash.kosh.engine

import java.math.BigDecimal
import java.math.RoundingMode

/** Exact money helpers. Amounts are stored as whole paise in a Long. */
object Money {

    /** "1,23,456.7" -> 12345670 paise. Returns null for anything that is not a positive amount. */
    fun parseToPaise(text: String): Long? {
        val cleaned = text.replace(",", "").replace(" ", "").trim().trimEnd('.')
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd.signum() <= 0) null
            else bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** 12345670 -> "₹1,23,456.70" (Indian digit grouping). */
    fun format(paise: Long, withSymbol: Boolean = true, showPaise: Boolean = true): String {
        val negative = paise < 0
        val abs = kotlin.math.abs(paise)
        val rupees = abs / 100
        val p = abs % 100
        val grouped = groupIndian(rupees.toString())
        val body = if (showPaise) "$grouped.${p.toString().padStart(2, '0')}" else grouped
        val sym = if (withSymbol) "₹" else ""
        return (if (negative) "-" else "") + sym + body
    }

    /** Compact form for chart labels: ₹1.2L, ₹3.4Cr, ₹950. */
    fun compact(paise: Long): String {
        val r = paise / 100.0
        val a = kotlin.math.abs(r)
        val sign = if (r < 0) "-" else ""
        return when {
            a >= 1_00_00_000 -> sign + "₹" + trim(a / 1_00_00_000) + "Cr"
            a >= 1_00_000 -> sign + "₹" + trim(a / 1_00_000) + "L"
            a >= 1_000 -> sign + "₹" + trim(a / 1_000) + "K"
            else -> sign + "₹" + a.toLong()
        }
    }

    private fun trim(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        var rest = digits.dropLast(3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** Savings rate in whole percent, or null when there is no income. */
    fun savingsRatePercent(incomePaise: Long, expensePaise: Long): Int? {
        if (incomePaise <= 0) return null
        return BigDecimal(incomePaise - expensePaise)
            .multiply(BigDecimal(100))
            .divide(BigDecimal(incomePaise), 0, RoundingMode.HALF_UP)
            .toInt()
    }
}
