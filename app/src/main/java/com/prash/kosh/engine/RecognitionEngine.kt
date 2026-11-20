package com.prash.kosh.engine

/** Rules the user has set or edited; loaded from the database before each run. */
data class RuleSet(
    /** merchant key -> category id, set by the user ("Always put Swiggy in Food"). */
    val userMerchantRules: Map<String, String> = emptyMap(),
    /** Editable merchant dictionary (seeded from [SeedData.merchants]). */
    val dictionary: List<SeedData.SeedMerchant> = SeedData.merchants,
    /** Editable keyword rules, first match wins. */
    val keywordRules: List<Pair<String, String>> = SeedData.keywordRules,
    /** Category id -> kind, so a user rule pointing at an investment or transfer category sets the type. */
    val categoryKinds: Map<String, SeedData.Kind> = SeedData.categories.associate { it.id to it.kind },
)

/**
 * Runs filter -> parse -> type -> merchant -> category -> confidence.
 * Duplicate detection needs the database, so it lives in [DuplicateDetector].
 */
class RecognitionEngine(private val rules: RuleSet = RuleSet()) {

    private val normalizer = MerchantNormalizer(rules.dictionary)
    private val keywordRegexes = rules.keywordRules.map { (k, c) ->
        Regex("""\b${Regex.escape(k.trim())}\b""", RegexOption.IGNORE_CASE) to c
    }
    private val investmentRx = phraseRegex(SeedData.investmentPhrases)
    private val transferRx = phraseRegex(SeedData.transferPhrases)
    private val refundRx = Regex("""\b(?:refund(?:ed)?|reversed|reversal|chargeback)\b""", RegexOption.IGNORE_CASE)
    private val cardPaymentCredit = Regex("""payment (?:of .{0,20})?(?:has been )?received (?:on|towards|for) (?:your )?(?:\w+ )?(?:bank )?(?:credit )?card""", RegexOption.IGNORE_CASE)

    fun recognise(input: RawInput): EngineResult {
        if (input.source == CaptureSource.NOTIFICATION && input.sender !in TransactionParser.notificationApps) {
            return EngineResult.Ignored(IgnoreReason.UNTRUSTED_APP)
        }
        MessageFilter.check(input)?.let { return EngineResult.Ignored(it) }
        val f = TransactionParser.parse(input) ?: return EngineResult.Ignored(IgnoreReason.NO_AMOUNT)

        val merchant = f.merchantRaw?.let { normalizer.normalise(it) }
        val (catId, catSource) = categorise(merchant, f, input.body)
        val type = classifyType(f, input.body, merchant, catId)

        val finalCategory = when (type) {
            TxnType.REFUND -> SeedData.REFUND_CATEGORY
            TxnType.TRANSFER -> catId?.takeIf { it.startsWith("transfers") } ?: transferSubcategory(input.body)
            TxnType.INVESTMENT -> catId?.takeIf { it.startsWith("investments") } ?: SeedData.INVESTMENT_CATEGORY
            TxnType.INCOME -> catId?.takeIf { it.startsWith("income") } ?: SeedData.FALLBACK_INCOME
            TxnType.EXPENSE -> catId?.takeIf { rules.categoryKinds[it] == SeedData.Kind.EXPENSE } ?: SeedData.FALLBACK_EXPENSE
        }
        val finalSource = if (finalCategory == catId) catSource else when (type) {
            TxnType.REFUND, TxnType.TRANSFER, TxnType.INVESTMENT -> CategorySource.KEYWORD
            else -> CategorySource.NONE
        }

        val knownSender = TransactionParser.isKnownSender(input)
        val confidence = ConfidenceScorer.score(f, merchant != null, finalSource, knownSender)
        return EngineResult.Recognised(
            Recognition(
                fields = f, type = type, merchant = merchant, categoryId = finalCategory,
                categorySource = finalSource, confidence = confidence, knownSender = knownSender,
                txnDateMillis = f.dateMillis ?: input.receivedAtMillis,
            )
        )
    }

    private fun categorise(merchant: Merchant?, f: ParsedFields, body: String): Pair<String?, CategorySource> {
        if (merchant != null) {
            rules.userMerchantRules[merchant.key]?.let { return it to CategorySource.USER_RULE }
            merchant.dictionaryCategoryId?.let { return it to CategorySource.DICTIONARY }
        }
        val haystacks = listOfNotNull(f.merchantRaw, body)
        for (h in haystacks) for ((rx, cat) in keywordRegexes) if (rx.containsMatchIn(h)) return cat to CategorySource.KEYWORD
        return null to CategorySource.NONE
    }

    private fun classifyType(f: ParsedFields, body: String, merchant: Merchant?, catId: String?): TxnType {
        val kind = catId?.let { rules.categoryKinds[it] ?: rules.categoryKinds[it.substringBefore('.')] }
        if (f.direction == Direction.CREDIT) {
            if (cardPaymentCredit.containsMatchIn(body)) return TxnType.TRANSFER
            if (refundRx.containsMatchIn(body)) return TxnType.REFUND
            if (transferRx.containsMatchIn(body)) return TxnType.TRANSFER
            // Money coming back from a broker or AMC is an investment redemption, not income.
            if (kind == SeedData.Kind.INVESTMENT) return TxnType.INVESTMENT
            return TxnType.INCOME
        }
        if (kind == SeedData.Kind.TRANSFER || transferRx.containsMatchIn(body) || f.method == PaymentMethod.ATM) return TxnType.TRANSFER
        if (kind == SeedData.Kind.INVESTMENT) return TxnType.INVESTMENT
        // Phrases only count when no ordinary-expense rule already claimed the merchant.
        if (kind != SeedData.Kind.EXPENSE && investmentRx.containsMatchIn(body)) return TxnType.INVESTMENT
        return TxnType.EXPENSE
    }

    private fun transferSubcategory(body: String): String {
        val b = body.lowercase()
        return when {
            "cash dep" in b || "cdm" in b -> "transfers.cashdep"
            "atm" in b || "cash withdrawal" in b -> "transfers.cash"
            "card" in b -> "transfers.ccbill"
            "self" in b || "own account" in b -> "transfers.self"
            else -> SeedData.TRANSFER_CATEGORY
        }
    }

    private fun phraseRegex(phrases: List<String>) =
        Regex(phrases.joinToString("|") { """\b${Regex.escape(it)}\b""" }, RegexOption.IGNORE_CASE)
}

/** Points-based score from the spec: 0..100. */
object ConfidenceScorer {
    fun score(f: ParsedFields, merchantFound: Boolean, catSource: CategorySource, knownSender: Boolean): Int {
        var s = if (f.templateId != null) 40 else 25
        s += 20 // an amount is always present by the time we score
        if (f.directionExplicit) s += 10
        if (f.accountLast4 != null) s += 5
        if (merchantFound) s += 10
        s += catSource.score
        if (knownSender) s += 5
        return s.coerceAtMost(100)
    }
}
