package com.prash.kosh.engine

/**
 * Pure-Kotlin types for the recognition engine. Nothing in the engine package
 * depends on Android, so it runs (and is unit-tested) on a plain JVM.
 */

enum class TxnType { EXPENSE, INCOME, TRANSFER, INVESTMENT, REFUND }

enum class Direction { DEBIT, CREDIT }

enum class CaptureSource { SMS, NOTIFICATION, SHARED, MANUAL, IMPORT }

enum class PaymentMethod { UPI, CARD, NETBANKING, NEFT, IMPS, RTGS, ATM, CASH, OTHER }

enum class CategorySource(val score: Int) { USER_RULE(15), DICTIONARY(15), KEYWORD(8), NONE(0) }

enum class TxnStatus {
    POSTED,               // >= auto-save threshold, or approved by the user
    PENDING_VERIFICATION, // between the thresholds: waits in Review ("Check"), not counted yet
    NEEDS_REVIEW,         // < 80: not counted until approved
    SUSPECTED_DUPLICATE,  // held for investigation, not counted
    REJECTED              // user rejected; kept for audit, never counted
}

/** A message as captured, before any interpretation. */
data class RawInput(
    val source: CaptureSource,
    val sender: String,          // SMS header (e.g. "VM-HDFCBK-S") or app package name
    val body: String,
    val receivedAtMillis: Long,
)

/** What the parser could pull out of a message. All money is in paise. */
data class ParsedFields(
    val amountPaise: Long,
    val direction: Direction,
    val directionExplicit: Boolean,
    val merchantRaw: String?,
    val accountLast4: String?,
    val dateMillis: Long?,
    val reference: String?,
    val method: PaymentMethod,
    val bank: String?,
    val templateId: String?,     // non-null when a bank/app-specific template matched
)

data class Merchant(val key: String, val displayName: String, val dictionaryCategoryId: String?)

data class Recognition(
    val fields: ParsedFields,
    val type: TxnType,
    val merchant: Merchant?,
    val categoryId: String?,
    val categorySource: CategorySource,
    val confidence: Int,
    val knownSender: Boolean,
    val txnDateMillis: Long,
    /** Set when a message format the owner taught matched ([Taught]). */
    val taughtPatternId: Long? = null,
    /** Account chosen when the format was taught; null = use the account in the message. */
    val taughtAccountId: Long? = null,
    /** Overrides the thresholds (taught formats always wait in Check). */
    val forcedStatus: TxnStatus? = null,
) {
    /** Route by confidence alone (defaults 95 / 80); duplicate checks happen against the database. */
    val statusByConfidence: TxnStatus get() = statusFor(95, 80)

    /** At or above [autoSave]: saved. Between [review] and [autoSave]: Check queue. Below [review]: Review queue. */
    fun statusFor(autoSave: Int, review: Int): TxnStatus = forcedStatus ?: when {
        confidence >= autoSave -> TxnStatus.POSTED
        confidence >= review -> TxnStatus.PENDING_VERIFICATION
        else -> TxnStatus.NEEDS_REVIEW
    }
}

sealed interface EngineResult {
    data class Ignored(val reason: IgnoreReason) : EngineResult
    data class Recognised(val recognition: Recognition) : EngineResult
}

enum class IgnoreReason(val label: String) {
    OTP("One-time password"),
    PROMOTIONAL("Promotion or offer"),
    FAILED("Failed or declined transaction"),
    COLLECT_REQUEST("UPI collect / payment request"),
    REMINDER("Due-date or balance reminder"),
    NO_AMOUNT("No amount found"),
    NOT_FINANCIAL("Not a financial transaction"),
    UNTRUSTED_APP("Notification from an app that is not on the allow-list"),
}
