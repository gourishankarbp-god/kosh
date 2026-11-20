package com.prash.kosh.engine

/**
 * Decides whether a message could be a real, completed money movement before any
 * parsing is attempted. Ignored messages are still stored with their reason.
 */
object MessageFilter {

    private fun rx(vararg p: String) = Regex(p.joinToString("|"), RegexOption.IGNORE_CASE)

    private val otp = rx("""\botp\b""", "one[ -]time password", "verification code", """\bcode is\b""", "do not share this code", "never share your")
    private val failed = rx("""\bfailed\b""", """\bdeclined\b""", "could not be processed", """\bunsuccessful\b""", "insufficient (?:funds|balance)", """\bnot successful\b""", "has been rejected")
    private val collect = rx("has requested (?:money|rs|inr|₹)", "requested you to pay", "collect request", "is requesting", "approve (?:the )?(?:payment )?request", "mandate (?:request|created)", "autopay (?:request|setup)")
    private val reminder = rx("""\bis due\b""", "due (?:date|on)", "minimum amount due", "total amount due", "statement (?:is )?generated", "bill (?:is )?generated", "will be debited", "scheduled for", "pay before", "payment reminder", "available balance is", "avl bal(?:ance)? (?:in|for|is)", "balance in your")
    private val promo = rx("pre-?approved", "apply now", "click here", """\boffer\b""", "get up to", "upto [0-9]+% off", """\bwin\b""", """\bloan\b.*\beligible\b""", "limited period", "exclusive deal", """\bcoupon\b""", "use code", "hurry", "festive", "lowest price")

    /** Words that show money actually moved. Without one of these we do not trust an amount. */
    private val movement = rx("debited", "credited", """\bspent\b""", """\bpaid\b""", """\bsent\b""", "received", "withdrawn", "withdrawal", "deposited", "transferred", "purchase", """\btxn\b""", "transaction", """\brefund""", "reversed", """\bdr\b""", """\bcr\b""", "deducted", "charged", "used for", "used at", "used on", """\bwdl\b""")

    /** Safety footers like "Never Share OTP/PIN/CVV" appear on real transaction alerts. */
    private val safetyFooter = Regex("""(?:never|do not|don't|dont|not to|pls don't|please do not)\s+(?:ever\s+)?share\s+(?:your\s+)?(?:otp|pin|cvv|password|card)[^.\n]*""", RegexOption.IGNORE_CASE)

    fun check(input: RawInput): IgnoreReason? {
        val b = input.body
        // An OTP message is one that *contains* a code; "never share OTP" on its own is a footer.
        if (otp.containsMatchIn(safetyFooter.replace(b, " "))) return IgnoreReason.OTP
        if (failed.containsMatchIn(b)) return IgnoreReason.FAILED
        if (collect.containsMatchIn(b)) return IgnoreReason.COLLECT_REQUEST
        val moved = movement.containsMatchIn(b)
        if (!moved && reminder.containsMatchIn(b)) return IgnoreReason.REMINDER
        if (promo.containsMatchIn(b) && !Regex("debited|credited|spent", RegexOption.IGNORE_CASE).containsMatchIn(b)) return IgnoreReason.PROMOTIONAL
        if (!moved) return IgnoreReason.NOT_FINANCIAL
        if (reminder.containsMatchIn(b) && !Regex("debited|credited|spent|received|paid", RegexOption.IGNORE_CASE).containsMatchIn(b)) return IgnoreReason.REMINDER
        return null
    }
}
