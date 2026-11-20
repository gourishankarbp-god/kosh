package com.prash.kosh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Every figure here was worked out by hand first. */
class MetricsTest {

    @Test fun greetingsByTimeOfDay() {
        assertEquals("Good morning", Metrics.greeting(LocalTime.of(5, 0)))
        assertEquals("Good morning", Metrics.greeting(LocalTime.of(11, 59)))
        assertEquals("Good afternoon", Metrics.greeting(LocalTime.of(12, 0)))
        assertEquals("Good afternoon", Metrics.greeting(LocalTime.of(16, 59)))
        assertEquals("Good evening", Metrics.greeting(LocalTime.of(17, 0)))
        assertEquals("Good evening", Metrics.greeting(LocalTime.of(22, 51)))
        assertEquals("Good evening", Metrics.greeting(LocalTime.of(23, 30)))
        assertEquals("Hello", Metrics.greeting(LocalTime.of(2, 0)))
        assertEquals("Hello", Metrics.greeting(LocalTime.of(4, 59)))
    }

    @Test fun monthlyDueDates() {
        val start = LocalDate.of(2026, 1, 5)
        assertEquals(LocalDate.of(2026, 11, 5), Metrics.nextDue(start, Frequency.MONTHLY, LocalDate.of(2026, 11, 5)))
        assertEquals(LocalDate.of(2026, 12, 5), Metrics.nextDue(start, Frequency.MONTHLY, LocalDate.of(2026, 11, 6)))
        assertEquals(LocalDate.of(2026, 1, 5), Metrics.nextDue(start, Frequency.MONTHLY, LocalDate.of(2025, 12, 1)))
    }

    @Test fun monthEndIsClampedForShortMonths() {
        val start = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), Metrics.nextDue(start, Frequency.MONTHLY, LocalDate.of(2026, 2, 1)))
        assertEquals(LocalDate.of(2026, 3, 31), Metrics.following(start, Frequency.MONTHLY, LocalDate.of(2026, 2, 28)))
    }

    @Test fun quarterlyHalfYearlyYearly() {
        val start = LocalDate.of(2024, 3, 28) // LIC policy started 28 Mar 2024
        assertEquals(LocalDate.of(2026, 12, 28), Metrics.nextDue(start, Frequency.QUARTERLY, LocalDate.of(2026, 11, 19)))
        assertEquals(LocalDate.of(2027, 3, 28), Metrics.nextDue(start, Frequency.HALF_YEARLY, LocalDate.of(2026, 11, 19)))
        assertEquals(LocalDate.of(2027, 3, 28), Metrics.nextDue(start, Frequency.YEARLY, LocalDate.of(2026, 11, 19)))
        assertEquals(LocalDate.of(2026, 3, 28), Metrics.nextDue(start, Frequency.YEARLY, LocalDate.of(2026, 3, 28)))
    }

    @Test fun oneTimeSchedules() {
        assertEquals(LocalDate.of(2026, 12, 1), Metrics.nextDue(LocalDate.of(2026, 12, 1), Frequency.ONE_TIME, LocalDate.of(2026, 11, 19)))
        assertNull(Metrics.nextDue(LocalDate.of(2026, 10, 1), Frequency.ONE_TIME, LocalDate.of(2026, 11, 19)))
    }

    @Test fun recurringExpenseRatio() {
        // ₹28,000 rent + ₹3,300 bills out of ₹52,000 spent = 60.19% -> 60%
        assertEquals(60, Metrics.recurringExpenseRatio(2800000 + 330000, 5200000))
        assertNull(Metrics.recurringExpenseRatio(0, 0))
        assertEquals(100, Metrics.recurringExpenseRatio(500, 500))
    }

    @Test fun burnRateMidMonth() {
        // ₹4,650 on quick commerce by 19 Nov: ₹244.74/day -> ₹245; projected 4650 x 30/19 = ₹7,342.11
        val b = Metrics.burnRate(465000, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 19))
        assertEquals(19, b.daysElapsed)
        assertEquals(30, b.daysInPeriod)
        assertEquals(24474L, b.perDayPaise)
        assertEquals(734211L, b.projectedPaise)
    }

    @Test fun burnRateFinishedAndFuturePeriods() {
        val done = Metrics.burnRate(310000, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 19))
        assertEquals(31, done.daysElapsed); assertEquals(310000L, done.projectedPaise); assertEquals(10000L, done.perDayPaise)
        val future = Metrics.burnRate(0, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 11, 19))
        assertEquals(0, future.daysElapsed); assertEquals(0L, future.projectedPaise)
    }

    @Test fun budgetRollover() {
        // ₹10,000 limit, spent ₹7,500 last month -> ₹2,500 carries over.
        assertEquals(1250000L, Metrics.budgetAvailable(1000000, true, 750000, true))
        // Overspending last month never reduces this month.
        assertEquals(1000000L, Metrics.budgetAvailable(1000000, true, 1200000, true))
        assertEquals(1000000L, Metrics.budgetAvailable(1000000, false, 750000, true))
        assertEquals(1000000L, Metrics.budgetAvailable(1000000, true, 750000, false))
    }

    @Test fun budgetAlertThresholds() {
        assertEquals(listOf(80), Metrics.crossedThresholds(700000, 850000, 1000000))
        assertEquals(listOf(80, 100), Metrics.crossedThresholds(700000, 1050000, 1000000))
        assertEquals(emptyList<Int>(), Metrics.crossedThresholds(850000, 900000, 1000000))
        assertEquals(listOf(100), Metrics.crossedThresholds(900000, 1000000, 1000000))
    }

    @Test fun goals() {
        // ₹90,000 left, Nov 2026 -> Jun 2027 = 7 months -> ₹12,857.14 -> ₹12,857
        assertEquals(1285714L, Metrics.monthlyNeeded(9000000, LocalDate.of(2026, 11, 19), LocalDate.of(2027, 6, 30)))
        assertEquals(0L, Metrics.monthlyNeeded(0, LocalDate.of(2026, 11, 19), LocalDate.of(2027, 6, 30)))
        assertNull(Metrics.monthlyNeeded(100, LocalDate.of(2026, 11, 19), null))
        // ₹1,50,000 saved / ₹45,000 a month = 3.3 months
        assertEquals(3.3, Metrics.monthsCovered(15000000, 4500000)!!, 0.0001)
    }
}
