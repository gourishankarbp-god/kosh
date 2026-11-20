package com.prash.kosh.engine

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** How often a payment repeats. */
enum class Frequency(val label: String, val months: Long) {
    MONTHLY("Monthly", 1), QUARTERLY("Quarterly", 3), HALF_YEARLY("Half-yearly", 6), YEARLY("Yearly", 12), ONE_TIME("One-time", 0);
}

/**
 * Pure calculations behind budgets, planning and insights. No Android, no database:
 * every number the screens show comes from here and is unit-tested.
 */
object Metrics {

    // ------------------------------------------------------------ greeting

    /** Greeting by Indian time of day. */
    fun greeting(time: LocalTime): String = when (time.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..23 -> "Good evening"
        else -> "Hello"
    }

    // ------------------------------------------------------------ Indian financial year (April - March)

    /** Calendar year in which the financial year containing [d] starts: 15 Jan 2027 -> 2026 (FY 2026-27). */
    fun fyStartYear(d: LocalDate): Int = if (d.monthValue >= 4) d.year else d.year - 1

    /** "FY 2026-27". */
    fun fyLabel(startYear: Int): String = "FY $startYear-${((startYear + 1) % 100).toString().padStart(2, '0')}"

    /** The 12 months of a financial year, April first. */
    fun fyMonths(startYear: Int): List<YearMonth> = (0..11).map { YearMonth.of(startYear, 4).plusMonths(it.toLong()) }

    // ------------------------------------------------------------ due dates

    /**
     * First due date on or after [onOrAfter] for a schedule that started on [start] and repeats
     * every [frequency]. The day of month follows [start], clamped for short months (31 Jan -> 28 Feb).
     * Returns null for a one-time schedule that has already passed.
     */
    fun nextDue(start: LocalDate, frequency: Frequency, onOrAfter: LocalDate): LocalDate? {
        if (frequency == Frequency.ONE_TIME) return start.takeIf { !it.isBefore(onOrAfter) }
        if (!start.isBefore(onOrAfter)) return start
        val monthsBetween = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(onOrAfter))
        var steps = monthsBetween / frequency.months
        while (true) {
            val candidate = occurrence(start, frequency, steps)
            if (!candidate.isBefore(onOrAfter)) return candidate
            steps++
        }
    }

    private fun occurrence(start: LocalDate, f: Frequency, n: Long): LocalDate {
        val ym = YearMonth.from(start).plusMonths(n * f.months)
        return ym.atDay(minOf(start.dayOfMonth, ym.lengthOfMonth()))
    }

    /** The due date after [current], keeping the schedule's own day of month. */
    fun following(start: LocalDate, frequency: Frequency, current: LocalDate): LocalDate? =
        nextDue(start, frequency, current.plusDays(1))

    // ------------------------------------------------------------ ratios

    /** Whole percent of [part] in [whole], or null when [whole] is zero. */
    fun percent(part: Long, whole: Long): Int? {
        if (whole <= 0) return null
        return BigDecimal(part).multiply(BigDecimal(100)).divide(BigDecimal(whole), 0, RoundingMode.HALF_UP).toInt()
    }

    /**
     * Recurring expense ratio: share of the period's spending that went to recurring commitments
     * (rent, bills, subscriptions, insurance, EMIs and anything matched to a recurring payment).
     */
    fun recurringExpenseRatio(recurringPaise: Long, totalExpensePaise: Long): Int? = percent(recurringPaise, totalExpensePaise)

    // ------------------------------------------------------------ burn rate

    data class BurnRate(
        val spentPaise: Long,
        val daysElapsed: Int,
        val daysInPeriod: Int,
        val perDayPaise: Long,
        val projectedPaise: Long,
    )

    /**
     * Discretionary burn rate for a period: spend per day so far and the projected period total
     * if the same pace continues. A finished period projects to what was actually spent.
     */
    fun burnRate(spentPaise: Long, periodStart: LocalDate, periodEndInclusive: LocalDate, today: LocalDate): BurnRate {
        val total = ChronoUnit.DAYS.between(periodStart, periodEndInclusive).toInt() + 1
        val elapsed = when {
            today.isBefore(periodStart) -> 0
            today.isAfter(periodEndInclusive) -> total
            else -> ChronoUnit.DAYS.between(periodStart, today).toInt() + 1
        }
        val perDay = if (elapsed == 0) 0 else divideRound(spentPaise, elapsed.toLong())
        val projected = if (elapsed == 0) 0 else if (elapsed >= total) spentPaise else divideRound(spentPaise * total, elapsed.toLong())
        return BurnRate(spentPaise, elapsed, total, perDay, projected)
    }

    // ------------------------------------------------------------ budgets

    /**
     * Money available in a category this month. With rollover on, unspent money from last month
     * (never an overspend) is added on top of this month's limit.
     */
    fun budgetAvailable(limitPaise: Long, rollover: Boolean, previousMonthSpentPaise: Long?, previousMonthLimitApplied: Boolean): Long {
        if (!rollover || previousMonthSpentPaise == null || !previousMonthLimitApplied) return limitPaise
        return limitPaise + (limitPaise - previousMonthSpentPaise).coerceAtLeast(0)
    }

    /** Alert levels (in percent) a spend crossed, from [before] to [after], against [available]. */
    fun crossedThresholds(beforePaise: Long, afterPaise: Long, availablePaise: Long, levels: List<Int> = listOf(80, 100)): List<Int> {
        if (availablePaise <= 0) return emptyList()
        return levels.filter { lvl ->
            val line = BigDecimal(availablePaise).multiply(BigDecimal(lvl)).divide(BigDecimal(100))
            BigDecimal(beforePaise) < line && BigDecimal(afterPaise) >= line
        }
    }

    // ------------------------------------------------------------ goals

    /** How much to set aside each month to reach a goal by its date (null without a date). */
    fun monthlyNeeded(remainingPaise: Long, today: LocalDate, targetDate: LocalDate?): Long? {
        if (targetDate == null) return null
        if (remainingPaise <= 0) return 0
        val months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(targetDate)).coerceAtLeast(1)
        return divideRound(remainingPaise, months)
    }

    /** Months of expenses an emergency fund covers, to one decimal place (null without spending history). */
    fun monthsCovered(savedPaise: Long, averageMonthlyExpensePaise: Long): Double? {
        if (averageMonthlyExpensePaise <= 0) return null
        return BigDecimal(savedPaise).divide(BigDecimal(averageMonthlyExpensePaise), 1, RoundingMode.HALF_UP).toDouble()
    }

    private fun divideRound(a: Long, b: Long): Long =
        BigDecimal(a).divide(BigDecimal(b), 0, RoundingMode.HALF_UP).toLong()
}
