package com.prash.kosh.engine

/**
 * Compares a newly recognised transaction with recent ones already stored.
 *
 *  - Same reference number                       -> MERGE (same payment, second message)
 *  - SMS vs app notification, same amount/type,
 *    within 15 minutes, merchants compatible     -> MERGE (bank SMS + GPay alert for one payment)
 *  - Same channel, amount, type and account
 *    within 15 minutes, no shared reference      -> SUSPECTED duplicate, held for you
 *  - Otherwise                                   -> NEW
 */
object DuplicateDetector {

    const val WINDOW_MILLIS = 15 * 60 * 1000L

    data class Existing(
        val id: Long,
        val amountPaise: Long,
        val type: TxnType,
        val dateMillis: Long,
        val accountLast4: String?,
        val reference: String?,
        val merchantKey: String?,
        val sources: Set<CaptureSource>,
    )

    sealed interface Verdict {
        data object New : Verdict
        data class Merge(val existingId: Long) : Verdict
        data class Suspected(val existingId: Long) : Verdict
    }

    fun check(
        amountPaise: Long, type: TxnType, dateMillis: Long, accountLast4: String?, reference: String?,
        merchantKey: String?, source: CaptureSource, candidates: List<Existing>,
    ): Verdict {
        if (reference != null) {
            candidates.firstOrNull { it.reference == reference && it.amountPaise == amountPaise }?.let { return Verdict.Merge(it.id) }
        }
        val near = candidates.filter {
            it.amountPaise == amountPaise && sameDirection(it.type, type) &&
                kotlin.math.abs(it.dateMillis - dateMillis) <= WINDOW_MILLIS
        }
        for (e in near) {
            // An app alert or a statement line next to an SMS for the same payment is one transaction.
            val crossChannel = source !in e.sources &&
                (source == CaptureSource.NOTIFICATION || CaptureSource.NOTIFICATION in e.sources ||
                    source == CaptureSource.IMPORT || CaptureSource.IMPORT in e.sources) &&
                (accountLast4 == null || e.accountLast4 == null || accountLast4 == e.accountLast4)
            val merchantsAgree = merchantKey == null || e.merchantKey == null || merchantKey == e.merchantKey ||
                merchantKey.contains(e.merchantKey) || e.merchantKey.contains(merchantKey)
            val refsConflict = reference != null && e.reference != null && reference != e.reference
            if (crossChannel && merchantsAgree && !refsConflict) return Verdict.Merge(e.id)
        }
        for (e in near) {
            val refsConflict = reference != null && e.reference != null && reference != e.reference
            if (refsConflict) continue
            val accountsAgree = accountLast4 == null || e.accountLast4 == null || accountLast4 == e.accountLast4
            if (accountsAgree) return Verdict.Suspected(e.id)
        }
        return Verdict.New
    }

    /** A refund and an expense are different money flows; income and refunds are both inflows. */
    private fun sameDirection(a: TxnType, b: TxnType): Boolean {
        fun inflow(t: TxnType) = t == TxnType.INCOME || t == TxnType.REFUND
        return a == b || (inflow(a) && inflow(b))
    }
}
