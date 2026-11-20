package com.prash.kosh.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateDetectorTest {

    private val t0 = 1_731_390_000_000L
    private fun existing(
        id: Long = 1, amount: Long = 25000, type: TxnType = TxnType.EXPENSE, at: Long = t0, acct: String? = "4821",
        ref: String? = null, merchant: String? = "swiggy", sources: Set<CaptureSource> = setOf(CaptureSource.SMS),
    ) = DuplicateDetector.Existing(id, amount, type, at, acct, ref, merchant, sources)

    @Test fun sameReferenceMerges() {
        val v = DuplicateDetector.check(25000, TxnType.EXPENSE, t0 + 60_000, "4821", "432112345678", "swiggy",
            CaptureSource.SMS, listOf(existing(ref = "432112345678")))
        assertEquals(DuplicateDetector.Verdict.Merge(1), v)
    }

    @Test fun smsPlusAppNotificationMerges() {
        val v = DuplicateDetector.check(25000, TxnType.EXPENSE, t0 + 30_000, null, null, "swiggy",
            CaptureSource.NOTIFICATION, listOf(existing(ref = "432112345678")))
        assertEquals(DuplicateDetector.Verdict.Merge(1), v)
    }

    @Test fun twoSmsSameAmountNoReferenceIsSuspected() {
        val v = DuplicateDetector.check(25000, TxnType.EXPENSE, t0 + 5 * 60_000, "4821", null, "swiggy",
            CaptureSource.SMS, listOf(existing()))
        assertEquals(DuplicateDetector.Verdict.Suspected(1), v)
    }

    @Test fun differentReferencesAreNew() {
        val v = DuplicateDetector.check(25000, TxnType.EXPENSE, t0 + 60_000, "4821", "999", "swiggy",
            CaptureSource.SMS, listOf(existing(ref = "111")))
        assertEquals(DuplicateDetector.Verdict.New, v)
    }

    @Test fun outsideWindowIsNew() {
        val v = DuplicateDetector.check(25000, TxnType.EXPENSE, t0 + 20 * 60_000, "4821", null, "swiggy",
            CaptureSource.SMS, listOf(existing()))
        assertEquals(DuplicateDetector.Verdict.New, v)
    }

    @Test fun refundIsNotDuplicateOfExpense() {
        val v = DuplicateDetector.check(25000, TxnType.REFUND, t0 + 60_000, "4821", null, "swiggy",
            CaptureSource.SMS, listOf(existing()))
        assertEquals(DuplicateDetector.Verdict.New, v)
    }

    @Test fun differentMerchantsAcrossChannelsAreNotMerged() {
        val v = DuplicateDetector.check(25000, TxnType.EXPENSE, t0 + 60_000, null, null, "zomato",
            CaptureSource.NOTIFICATION, listOf(existing()))
        // Not merged; same amount within window and no conflicting account -> held for review.
        assertEquals(DuplicateDetector.Verdict.Suspected(1), v)
    }
}
