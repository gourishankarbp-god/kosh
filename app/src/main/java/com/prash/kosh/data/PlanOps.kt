package com.prash.kosh.data

import androidx.room.withTransaction
import com.prash.kosh.engine.Frequency
import com.prash.kosh.engine.Metrics
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * Phase 2 and 3 operations: budgets, recurring payments, goals, investments.
 * Kept separate from capture logic; everything that changes a transaction is still audited.
 */

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
internal fun Long.toIstDate(): LocalDate = Instant.ofEpochMilli(this).atZone(IST).toLocalDate()
internal fun LocalDate.noonMillis(): Long = atTime(12, 0).atZone(IST).toInstant().toEpochMilli()
internal fun YearMonth.startMs(): Long = atDay(1).atStartOfDay(IST).toInstant().toEpochMilli()
internal fun YearMonth.endMs(): Long = plusMonths(1).atDay(1).atStartOfDay(IST).toInstant().toEpochMilli() - 1

/** Default words that identify each kind of tracked payment in a bank SMS. */
val DEFAULT_INVESTMENT_KEYWORDS = mapOf(
    "LIC" to listOf("lic", "lic of india", "licindia", "life insurance corporation"),
    "INSURANCE" to listOf("insurance premium", "policy premium"),
    "BOND" to listOf("bond", "sgb", "rbi retail direct", "ncd"),
    "RD" to listOf("rd installment", "rd instalment", "recurring deposit", "rd a/c"),
    "FD" to listOf("fd booked", "fixed deposit", "term deposit"),
)

/** Category used for a tracked kind (manual payments and display). */
fun investmentCategory(kind: String) = when (kind) {
    "LIC" -> "investments.lic"; "INSURANCE" -> "insurance"; "BOND" -> "investments.bonds"
    "FD", "RD" -> "investments.deposits"; else -> "investments"
}

// ---------------------------------------------------------------------------- investments

internal suspend fun Repository.autoLinkInvestment(t: TransactionEntity) {
    val inv = db.investments()
    val candidates = inv.active()
    if (candidates.isEmpty()) return
    val text = buildString {
        append(t.merchantKey.orEmpty()).append(' ').append(t.merchantName.orEmpty()).append(' ')
        db.rawMessages().forTxn(t.id).forEach { append(it.body).append(' ') }
    }.lowercase()
    fun hits(i: InvestmentEntity): Boolean {
        val words = i.matchKeywords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } +
            (DEFAULT_INVESTMENT_KEYWORDS[i.kind] ?: emptyList()) +
            listOfNotNull(i.institution?.lowercase()?.trim()?.takeIf { it.length >= 3 && i.kind == "INSURANCE" })
        return words.any { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(text) }
    }
    val matched = candidates.filter(::hits)
    val chosen = when {
        matched.size == 1 -> matched.first()
        matched.size > 1 -> matched.filter { it.installmentPaise == t.amountPaise }.singleOrNull()
        else -> null
    } ?: return
    inv.addEntry(InvestmentEntryEntity(investmentId = chosen.id, kind = "CONTRIBUTION", amountPaise = t.amountPaise,
        dateMillis = t.dateMillis, transactionId = t.id, note = "Linked automatically from bank message"))
}

suspend fun Repository.linkTransactionToInvestment(t: TransactionEntity, investmentId: Long) {
    db.investments().addEntry(InvestmentEntryEntity(investmentId = investmentId, kind = "CONTRIBUTION", amountPaise = t.amountPaise,
        dateMillis = t.dateMillis, transactionId = t.id, note = "Linked by you"))
}

/** Saves an investment; [openingPaise] records money already put in before tracking started. */
suspend fun Repository.saveInvestment(i: InvestmentEntity, openingPaise: Long?): Long = db.withTransaction {
    val id = if (i.id == 0L) db.investments().upsert(i) else { db.investments().upsert(i); i.id }
    if (openingPaise != null && openingPaise > 0) {
        // OPENING counts towards the amount invested but not towards the payment schedule:
        // instalments before today are assumed paid.
        db.investments().addEntry(InvestmentEntryEntity(investmentId = id, kind = "OPENING", amountPaise = openingPaise,
            dateMillis = LocalDate.parse(i.startDate).noonMillis(), note = "Amount invested before tracking"))
    }
    id
}

suspend fun Repository.addInvestmentEntry(e: InvestmentEntryEntity) = db.withTransaction {
    db.investments().addEntry(e)
    if (e.kind == "MATURITY") db.investments().get(e.investmentId)?.let { db.investments().upsert(it.copy(status = "MATURED")) }
}

/** Next instalment of a SIP, LIC premium or RD, or null when it has no schedule. */
fun nextInvestmentDue(i: InvestmentEntity, lastContribution: Long?, today: LocalDate): LocalDate? {
    val f = runCatching { Frequency.valueOf(i.frequency) }.getOrNull() ?: return null
    if (f == Frequency.ONE_TIME || i.installmentPaise == null || i.status != "ACTIVE") return null
    val start = LocalDate.parse(i.startDate)
    if (lastContribution == null) return Metrics.nextDue(start, f, today)
    // A payment covers the due date nearest to it, even if paid a few days early or late.
    val paid = lastContribution.toIstDate()
    val covered = Metrics.nextDue(start, f, paid.minusDays(f.months * 15)) ?: return null
    val next = Metrics.following(start, f, covered) ?: return null
    return i.maturityDate?.let { m -> next.takeIf { !it.isAfter(LocalDate.parse(m)) } } ?: next
}

// ---------------------------------------------------------------------------- recurring payments

internal suspend fun Repository.matchRecurring(t: TransactionEntity) {
    val date = t.dateMillis.toIstDate()
    val rule = db.recurring().active().firstOrNull { r ->
        r.type == t.type && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(r.nextDueDate), date)) <= 10 &&
            ((r.merchantKey != null && r.merchantKey == t.merchantKey) ||
                (r.merchantKey == null && r.categoryId == t.categoryId && kotlin.math.abs(t.amountPaise - r.amountPaise) * 10 <= r.amountPaise))
    } ?: return
    val after = t.copy(recurringId = rule.id, updatedAt = System.currentTimeMillis())
    db.transactions().update(after)
    audit(t.id, "RECURRING_MATCHED", t, after)
    advanceRule(rule, date)
}

private suspend fun Repository.advanceRule(rule: RecurringRuleEntity, paidOn: LocalDate) {
    val f = Frequency.valueOf(rule.frequency)
    val next = Metrics.following(LocalDate.parse(rule.startDate), f, LocalDate.parse(rule.nextDueDate))
    db.recurring().upsert(rule.copy(lastPaidDate = paidOn.toString(),
        nextDueDate = (next ?: LocalDate.parse(rule.nextDueDate)).toString(), active = rule.active && next != null))
}

/** Saves a rule and links matching past payments from the last 60 days. */
suspend fun Repository.saveRecurringRule(r: RecurringRuleEntity): Long = db.withTransaction {
    val id = db.recurring().upsert(r).let { if (r.id != 0L) r.id else it }
    if (r.id == 0L) {
        val saved = db.recurring().get(id)!!
        val now = System.currentTimeMillis()
        db.transactions().unmatchedForRecurring(r.type, now - 60L * 86_400_000, now)
            .filter { t -> (saved.merchantKey != null && t.merchantKey == saved.merchantKey) ||
                (saved.merchantKey == null && t.categoryId == saved.categoryId && kotlin.math.abs(t.amountPaise - saved.amountPaise) * 10 <= saved.amountPaise) }
            .forEach { t ->
                val after = t.copy(recurringId = id, updatedAt = now)
                db.transactions().update(after); audit(t.id, "RECURRING_MATCHED", t, after)
            }
    }
    id
}

/** Creates a recurring rule from an existing payment ("this is my monthly rent"). */
suspend fun Repository.makeRecurringFrom(t: TransactionEntity, frequency: Frequency): Long {
    val date = t.dateMillis.toIstDate()
    val next = Metrics.following(date, frequency, date) ?: date
    val id = db.recurring().upsert(RecurringRuleEntity(name = t.merchantName ?: t.note ?: "Recurring payment", type = t.type,
        amountPaise = t.amountPaise, categoryId = t.categoryId, merchantKey = t.merchantKey, accountId = t.accountId,
        frequency = frequency.name, startDate = date.toString(), nextDueDate = next.toString(), lastPaidDate = date.toString()))
    val after = t.copy(recurringId = id, updatedAt = System.currentTimeMillis())
    db.transactions().update(after); audit(t.id, "RECURRING_MATCHED", t, after)
    return id
}

/** "Mark paid" for something the bank did not message about (cash rent, say): records it and moves the due date. */
suspend fun Repository.markRulePaid(ruleId: Long, paidOn: LocalDate, recordTransaction: Boolean) = db.withTransaction {
    val r = db.recurring().get(ruleId) ?: return@withTransaction
    if (recordTransaction) {
        val now = System.currentTimeMillis()
        val t = TransactionEntity(type = r.type, direction = if (r.type == "INCOME") "CREDIT" else "DEBIT", status = "POSTED",
            amountPaise = r.amountPaise, dateMillis = paidOn.noonMillis(), accountId = r.accountId, merchantKey = r.merchantKey,
            merchantName = r.name, categoryId = r.categoryId, sources = "MANUAL", recurringId = r.id, createdAt = now, updatedAt = now)
        val id = db.transactions().insert(t)
        audit(id, "CREATED", null, t.copy(id = id))
        if (t.type == "EXPENSE") checkBudget(t.copy(id = id))
    }
    advanceRule(r, paidOn)
}

/** Skip this occurrence without recording a payment. */
suspend fun Repository.skipRuleOccurrence(ruleId: Long) {
    val r = db.recurring().get(ruleId) ?: return
    val next = Metrics.following(LocalDate.parse(r.startDate), Frequency.valueOf(r.frequency), LocalDate.parse(r.nextDueDate))
    db.recurring().upsert(r.copy(nextDueDate = (next ?: LocalDate.parse(r.nextDueDate)).toString(), active = next != null))
}

// ---------------------------------------------------------------------------- upcoming payments

data class DueItem(
    val key: String,
    val kind: String,           // RECURRING, LIC, SIP, RD, OTHER
    val refId: Long,
    val name: String,
    val amountPaise: Long,
    val dueDate: LocalDate,
    val isIncome: Boolean = false,
)

/** Everything due up to [horizonDays] ahead, plus anything overdue. */
suspend fun Repository.upcoming(today: LocalDate, horizonDays: Long = 30): List<DueItem> {
    val limit = today.plusDays(horizonDays)
    val out = ArrayList<DueItem>()
    db.recurring().active().forEach { r ->
        val due = LocalDate.parse(r.nextDueDate)
        if (!due.isAfter(limit)) out += DueItem("r${r.id}", "RECURRING", r.id, r.name, r.amountPaise, due, r.type == "INCOME")
    }
    val totals = db.investments().allEntries().groupBy { it.investmentId }
    db.investments().active().forEach { i ->
        val last = totals[i.id]?.filter { it.kind == "CONTRIBUTION" }?.maxOfOrNull { it.dateMillis }
        val due = nextInvestmentDue(i, last, today) ?: return@forEach
        if (!due.isAfter(limit)) out += DueItem("i${i.id}", if (i.kind in setOf("LIC", "INSURANCE", "BOND", "RD")) i.kind else "OTHER", i.id,
            i.name, i.installmentPaise ?: 0, due)
    }
    return out.sortedBy { it.dueDate }
}

/** Records an instalment paid outside the bank SMS flow (cash, another app). */
suspend fun Repository.markInvestmentInstalmentPaid(investmentId: Long, paidOn: LocalDate) {
    val i = db.investments().get(investmentId) ?: return
    db.investments().addEntry(InvestmentEntryEntity(investmentId = i.id, kind = "CONTRIBUTION", amountPaise = i.installmentPaise ?: return,
        dateMillis = paidOn.noonMillis(), note = "Marked paid by you"))
}

// ---------------------------------------------------------------------------- budgets

data class BudgetRow(
    val categoryId: String,
    val limitPaise: Long,
    val availablePaise: Long,
    val spentPaise: Long,
    val rollover: Boolean,
) {
    val percent: Int get() = Metrics.percent(spentPaise, availablePaise) ?: 0
    val remainingPaise: Long get() = availablePaise - spentPaise
}

suspend fun Repository.budgetRows(month: YearMonth): List<BudgetRow> {
    val spent = db.transactions().expenseByTop(month.startMs(), month.endMs()).associate { it.topCategory to it.total }
    val prev = month.minusMonths(1)
    val prevSpent = db.transactions().expenseByTop(prev.startMs(), prev.endMs()).associate { it.topCategory to it.total }
    return db.budgets().all().filter { YearMonth.parse(it.startMonth) <= month }.map { b ->
        val available = Metrics.budgetAvailable(b.monthlyLimitPaise, b.rollover, prevSpent[b.categoryId] ?: 0,
            YearMonth.parse(b.startMonth) <= prev)
        BudgetRow(b.categoryId, b.monthlyLimitPaise, available, spent[b.categoryId] ?: 0, b.rollover)
    }
}

internal suspend fun Repository.checkBudget(t: TransactionEntity) {
    val top = t.categoryId?.substringBefore('.') ?: return
    val date = t.dateMillis.toIstDate()
    val month = YearMonth.from(date)
    if (month != YearMonth.now(IST)) return // alerts only for the current month
    val row = budgetRows(month).firstOrNull { it.categoryId == top } ?: return
    val crossed = Metrics.crossedThresholds(row.spentPaise - t.amountPaise, row.spentPaise, row.availablePaise)
    val name = db.categories().get(top)?.name ?: top
    crossed.maxOrNull()?.let { notifier.budgetAlert(name, it, row.spentPaise, row.availablePaise) }
}

suspend fun Repository.saveBudget(categoryId: String, limitPaise: Long, rollover: Boolean, month: YearMonth) {
    val existing = db.budgets().get(categoryId)
    db.budgets().upsert(BudgetEntity(categoryId, limitPaise, rollover, existing?.startMonth ?: month.toString()))
}

// ---------------------------------------------------------------------------- goals

suspend fun Repository.addGoalContribution(goalId: Long, amountPaise: Long, on: LocalDate, note: String?) {
    db.goals().addContribution(GoalContributionEntity(goalId = goalId, amountPaise = amountPaise, dateMillis = on.noonMillis(), note = note))
}

/** Average monthly spending over the last 3 complete months (for the emergency fund). */
suspend fun Repository.averageMonthlyExpense(today: LocalDate): Long {
    val months = (1..3).map { YearMonth.from(today).minusMonths(it.toLong()) }
    val totals = months.map { m -> db.transactions().expenseByTop(m.startMs(), m.endMs()).sumOf { it.total } }
    val withData = totals.filter { it > 0 }
    return if (withData.isEmpty()) 0 else withData.sum() / withData.size
}

/** Instalments still to pay until maturity (count and amount), or null without a schedule and maturity date. */
fun remainingPayments(i: InvestmentEntity, lastContribution: Long?, today: LocalDate): Pair<Int, Long>? {
    val f = runCatching { Frequency.valueOf(i.frequency) }.getOrNull() ?: return null
    if (f == Frequency.ONE_TIME || i.installmentPaise == null || i.maturityDate == null || i.status != "ACTIVE") return null
    val end = LocalDate.parse(i.maturityDate)
    var d = nextInvestmentDue(i, lastContribution, today) ?: return 0 to 0L
    var n = 0
    val start = LocalDate.parse(i.startDate)
    while (d.isBefore(end) && n < 1200) { n++; d = Metrics.following(start, f, d) ?: break }
    return n to n * i.installmentPaise
}
