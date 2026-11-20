package com.prash.kosh.engine

/**
 * Turns raw payee text ("UPI-SWIGGY-1234", "swiggy.stores@axb", "SWIGGY BANGALORE")
 * into a stable key ("swiggy") and matches it to the merchant dictionary.
 */
class MerchantNormalizer(dictionary: List<SeedData.SeedMerchant>) {

    private data class Alias(val squashed: String, val words: String, val merchant: SeedData.SeedMerchant)

    // Longest alias first, so "swiggy instamart" beats "swiggy".
    private val aliases: List<Alias> = dictionary.flatMap { sm ->
        sm.aliases.map { a -> Alias(squash(a), cleanWords(a), sm) }
    }.filter { it.squashed.length >= 2 }.sortedByDescending { it.squashed.length }

    private val noise = setOf(
        "upi", "pos", "ecom", "vps", "ach", "nach", "imps", "neft", "rtgs", "mb", "ib", "p2m", "p2a", "payment", "payments",
        "pvt", "ltd", "private", "limited", "llp", "inc", "india", "in", "technologies", "technology", "tech", "services",
        "service", "retail", "stores", "store", "online", "bangalore", "bengaluru", "blr", "mumbai", "delhi", "new",
        "digital", "co", "company", "the", "and", "of", "via", "razorpay", "rzp", "payu", "billdesk", "ccavenue", "cashfree",
        "phonepe", "paytm", "gpay", "bharatpe", "ok", "okaxis", "okhdfcbank", "okicici", "oksbi", "ybl", "ibl", "axl",
        "apl", "axb", "yes", "upiswiggy", "pg", "merchant", "mer", "ref", "txn", "dr", "cr", "to", "from", "at", "by",
    )

    fun normalise(raw: String): Merchant? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // For a UPI handle keep only the part before '@', and split dotted handles ("swiggy.stores").
        val base = if (Regex("""^[\w.\-]+@[a-zA-Z]+$""").matches(text)) text.substringBefore('@') else text
        val words = cleanWords(base)
        val sq = squash(base)

        for (a in aliases) {
            // Short aliases ("vi", "lic", "cred") must match a whole word, or "vijay" would be Vi
            // and "credit" would be CRED. Longer ones may also prefix or sit inside a squashed name.
            val hit = (" $words ").contains(" ${a.words} ") ||
                (a.squashed.length >= 6 && sq.startsWith(a.squashed)) ||
                (a.squashed.length >= 7 && sq.contains(a.squashed))
            if (hit) return Merchant(a.merchant.id, a.merchant.displayName, a.merchant.categoryId)
        }
        val tokens = words.split(' ').filter { it.isNotBlank() && it !in noise && !it.all(Char::isDigit) && it.length > 1 }
        if (tokens.isEmpty()) {
            // A masked UPI handle like "*8221@ybl": keep it as the payee so a rule can learn it.
            return if ('@' in text) Merchant(text.lowercase().trimStart('*'), "UPI $text", null) else null
        }
        val key = tokens.take(3).joinToString(" ")
        val display = tokens.take(4).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        return Merchant(key, display, null)
    }

    companion object {
        fun cleanWords(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

        fun squash(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
