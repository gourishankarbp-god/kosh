package com.prash.kosh.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.Frequency
import com.prash.kosh.engine.RawInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlanOpsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val alerts = mutableListOf<String>()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val today get() = LocalDate.now(ist)
    private val dm2 get() = today.format(DateTimeFormatter.ofPattern("dd-MM-yy"))
    private val dmy get() = today.format(DateTimeFormatter.ofPattern("dd/MM/yy"))

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db, object : Notifier {
            override fun budgetAlert(categoryName: String, percent: Int, spentPaise: Long, availablePaise: Long) { alerts += "$categoryName $percent" }
        })
        repo.seedIfEmpty()
    }
    @After fun tearDown() = db.close()

    private fun manual(type: String, amount: Long, cat: String, merchant: String, date: LocalDate = today) = TransactionEntity(
        type = type, direction = if (type == "INCOME") "CREDIT" else "DEBIT", status = "POSTED", amountPaise = amount,
        dateMillis = date.noonMillis(), merchantName = merchant, merchantKey = merchant.lowercase(), categoryId = cat, createdAt = 0, updatedAt = 0)

    /** Approves whatever is waiting in Review (80-94% captures are not counted until approved). */
    private suspend fun approveAll() { db.transactions().all().filter { it.status != "POSTED" }.forEach { repo.setStatus(it.id, com.prash.kosh.engine.TxnStatus.POSTED) } }

    @Test fun bondPaymentLinksOnceApproved() = runBlocking {
        val bond = repo.saveInvestment(InvestmentEntity(kind = "BOND", name = "RBI Floating Rate", startDate = today.minusMonths(6).toString()), openingPaise = 3000000)
        repo.ingest(RawInput(CaptureSource.SMS, "VM-HDFCBK", "Rs 5,000.00 debited from A/c XX1690 on $dm2 towards RBI RETAIL DIRECT BOND. Avl Bal Rs 80,112.00", System.currentTimeMillis()))
        val t = db.transactions().all().single()
        if (t.status != "POSTED") { assertEquals(1, db.investments().entries(bond).size); approveAll() }
        val entries = db.investments().entries(bond)
        assertEquals(2, entries.size)
        assertEquals("OPENING", entries.first { it.transactionId == null }.kind)
        assertEquals(3500000L, entries.sumOf { it.amountPaise })
    }

    @Test fun twoLicPoliciesUseAmountToPickTheRightOne() = runBlocking {
        val a = repo.saveInvestment(InvestmentEntity(kind = "LIC", name = "Policy A", startDate = today.toString(), installmentPaise = 300000, frequency = "YEARLY"), null)
        val b = repo.saveInvestment(InvestmentEntity(kind = "LIC", name = "Policy B", startDate = today.toString(), installmentPaise = 1260000, frequency = "YEARLY"), null)
        repo.ingest(RawInput(CaptureSource.SMS, "VM-SBIINB", "Your A/c X3310 is debited for Rs.12,600.00 on ${today.format(DateTimeFormatter.ofPattern("ddMMMyy"))} towards LIC OF INDIA premium. -SBI", System.currentTimeMillis()))
        approveAll()
        assertTrue(db.investments().entries(a).isEmpty())
        assertEquals(1, db.investments().entries(b).size)
    }

    @Test fun licPremiumLinksAndNextDueMoves() = runBlocking {
        val start = today.minusYears(2)
        val lic = repo.saveInvestment(InvestmentEntity(kind = "LIC", name = "Jeevan Anand", startDate = start.toString(), installmentPaise = 1260000,
            frequency = "YEARLY", sumAssuredPaise = 50000000, maturityDate = start.plusYears(21).toString()), openingPaise = 2520000)
        val before = repo.upcoming(today, 400).first { it.refId == lic }
        assertEquals(start.plusYears(2), before.dueDate) // due today (third premium)
        repo.ingest(RawInput(CaptureSource.SMS, "VM-SBIINB", "Your A/c X3310 is debited for Rs.12,600.00 on ${today.format(DateTimeFormatter.ofPattern("ddMMMyy"))} towards LIC OF INDIA premium. -SBI", System.currentTimeMillis()))
        approveAll()
        assertEquals(2, db.investments().entries(lic).size)
        val after = repo.upcoming(today, 400).first { it.refId == lic }
        assertEquals(start.plusYears(3), after.dueDate)
    }

    @Test fun recurringRuleMatchesAndAdvances() = runBlocking {
        val id = repo.saveRecurringRule(RecurringRuleEntity(name = "Airtel broadband", type = "EXPENSE", amountPaise = 99900, categoryId = "utilities.mobile",
            merchantKey = "airtel", frequency = Frequency.MONTHLY.name, startDate = today.toString(), nextDueDate = today.toString()))
        repo.addManual(manual("EXPENSE", 99900, "utilities.mobile", "Airtel"))
        val t = db.transactions().all().single()
        assertEquals(id, t.recurringId)
        val rule = db.recurring().get(id)!!
        assertEquals(today.plusMonths(1).withDayOfMonth(minOf(today.dayOfMonth, YearMonth.from(today.plusMonths(1)).lengthOfMonth())).toString(), rule.nextDueDate)
        assertEquals(today.toString(), rule.lastPaidDate)
    }

    @Test fun makeRecurringFromTransaction() = runBlocking {
        repo.addManual(manual("EXPENSE", 2800000, "housing.rent", "Landlord", today.minusDays(3)))
        val t = db.transactions().all().single()
        val id = repo.makeRecurringFrom(t, Frequency.MONTHLY)
        assertEquals(id, db.transactions().get(t.id)!!.recurringId)
        assertEquals(today.minusDays(3).plusMonths(1), LocalDate.parse(db.recurring().get(id)!!.nextDueDate))
    }

    @Test fun markPaidRecordsTransactionAndAdvances() = runBlocking {
        val id = repo.saveRecurringRule(RecurringRuleEntity(name = "Maid", type = "EXPENSE", amountPaise = 600000, categoryId = "housing.maintenance",
            frequency = "MONTHLY", startDate = today.toString(), nextDueDate = today.toString()))
        repo.markRulePaid(id, today, recordTransaction = true)
        val t = db.transactions().all().single()
        assertEquals(id, t.recurringId); assertEquals(600000L, t.amountPaise)
        assertTrue(LocalDate.parse(db.recurring().get(id)!!.nextDueDate).isAfter(today))
    }

    @Test fun budgetAlertsAt80And100() = runBlocking {
        repo.saveBudget("food", 1000000, rollover = false, month = YearMonth.from(today))
        repo.addManual(manual("EXPENSE", 700000, "food.restaurants", "Truffles"))
        assertTrue(alerts.isEmpty())
        repo.addManual(manual("EXPENSE", 150000, "food.delivery", "Swiggy"))
        assertEquals(listOf("Food & Dining 80"), alerts)
        repo.addManual(manual("EXPENSE", 200000, "food.cafes", "Third Wave"))
        assertEquals(listOf("Food & Dining 80", "Food & Dining 100"), alerts)
        val row = repo.budgetRows(YearMonth.from(today)).single()
        assertEquals(1050000L, row.spentPaise); assertEquals(105, row.percent); assertEquals(-50000L, row.remainingPaise)
    }

    @Test fun budgetRolloverFromLastMonth() = runBlocking {
        val m = YearMonth.from(today)
        db.budgets().upsert(BudgetEntity("groceries", 1000000, rollover = true, startMonth = m.minusMonths(1).toString()))
        repo.addManual(manual("EXPENSE", 750000, "groceries.supermarket", "DMart", m.minusMonths(1).atDay(10)))
        val row = repo.budgetRows(m).single()
        assertEquals(1250000L, row.availablePaise)
    }

    @Test fun recurringRatioCountsFixedCategoriesAndRules() = runBlocking {
        repo.addManual(manual("EXPENSE", 2800000, "housing.rent", "Landlord"))      // fixed category
        repo.addManual(manual("EXPENSE", 200000, "food.delivery", "Swiggy"))       // variable
        repo.saveRecurringRule(RecurringRuleEntity(name = "Gym", type = "EXPENSE", amountPaise = 200000, categoryId = "personal.fitness",
            merchantKey = "cultfit", frequency = "MONTHLY", startDate = today.toString(), nextDueDate = today.toString()))
        repo.addManual(manual("EXPENSE", 200000, "personal.fitness", "cultfit"))  // matched to a rule
        val m = YearMonth.from(today)
        val recurring = db.transactions().observeMonthlyRecurring(m.startMs(), m.endMs()).first().sumOf { it.total }
        assertEquals(3000000L, recurring)
        assertEquals(94, com.prash.kosh.engine.Metrics.recurringExpenseRatio(recurring, 3200000))
    }

    @Test fun unlinkedInvestmentShowsUntilLinked() = runBlocking {
        repo.ingest(RawInput(CaptureSource.SMS, "VM-HDFCBK", "Rs 5,000.00 debited from A/c XX1690 on $dm2 towards NACH-MF ICCL SIP.", System.currentTimeMillis()))
        approveAll()
        val unlinked = db.transactions().observeUnlinkedInvestments().first()
        assertEquals(1, unlinked.size)
        val id = repo.saveInvestment(InvestmentEntity(kind = "OTHER_FIXED", name = "Fund", startDate = today.toString()), null)
        repo.linkTransactionToInvestment(unlinked.single(), id)
        assertTrue(db.transactions().observeUnlinkedInvestments().first().isEmpty())
    }

    @Test fun maturityClosesFixedDeposit() = runBlocking {
        val fd = repo.saveInvestment(InvestmentEntity(kind = "FD", name = "HDFC FD", startDate = today.minusYears(1).toString(),
            maturityDate = today.toString(), ratePercent = 7.1), openingPaise = 10000000)
        repo.addInvestmentEntry(InvestmentEntryEntity(investmentId = fd, kind = "MATURITY", amountPaise = 10710000, dateMillis = today.noonMillis()))
        assertEquals("MATURED", db.investments().get(fd)!!.status)
        assertNull(repo.upcoming(today).firstOrNull { it.refId == fd })
    }

    @Test fun goalsAndEmergencyFund() = runBlocking {
        val g = db.goals().upsert(GoalEntity(name = "Emergency fund", kind = "EMERGENCY", targetPaise = 30000000, createdAt = 0))
        repo.addGoalContribution(g, 15000000, today, null)
        repo.addGoalContribution(g, -1000000, today, "Car repair")
        val totals = db.goals().observeTotals().first().single()
        assertEquals(15000000L, totals.contributed); assertEquals(1000000L, totals.withdrawn)
        assertNotNull(repo.averageMonthlyExpense(today))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CashAndSplitTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db); repo.seedIfEmpty()
    }
    @After fun tearDown() = db.close()

    @Test fun atmWithdrawalMovesMoneyToCashWithoutExpense() = runBlocking {
        val d = today.format(DateTimeFormatter.ofPattern("ddMMMyy"))
        repo.ingest(RawInput(CaptureSource.SMS, "VM-SBIINB", "Rs 2,000 withdrawn at ATM S1BW000123 from A/c X3310 on $d. Avl Bal Rs 18,000. -SBI", System.currentTimeMillis()))
        db.transactions().all().filter { it.status != "POSTED" }.forEach { repo.setStatus(it.id, com.prash.kosh.engine.TxnStatus.POSTED) }
        val cash = repo.cashAccountId()
        val t = db.transactions().all().single()
        assertEquals("TRANSFER", t.type); assertEquals(cash, t.toAccountId)
        // Spend ₹500 of it in cash.
        repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 50000, dateMillis = today.noonMillis(),
            accountId = cash, categoryId = "groceries.supermarket", createdAt = 0, updatedAt = 0))
        val flows = db.transactions().observeAccountFlows().first().associateBy { it.accountId }
        assertEquals(200000L, flows[cash]!!.inflow); assertEquals(50000L, flows[cash]!!.outflow)
        val m = YearMonth.from(today)
        val totals = db.transactions().observeTypeTotals(m.startMs(), m.endMs()).first().associate { it.type to it.total }
        assertEquals(50000L, totals["EXPENSE"])   // only the cash spend is an expense
        assertEquals(200000L, totals["TRANSFER"]) // the withdrawal is a transfer
    }

    @Test fun splitCountsUnderEachCategory() = runBlocking {
        val id = repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 300000, dateMillis = today.noonMillis(),
            merchantName = "DMart", categoryId = "groceries.supermarket", createdAt = 0, updatedAt = 0))
        assertTrue(!repo.saveSplits(id, listOf(SplitEntity(transactionId = id, categoryId = "groceries.supermarket", amountPaise = 100000))))
        assertTrue(repo.saveSplits(id, listOf(
            SplitEntity(transactionId = id, categoryId = "groceries.supermarket", amountPaise = 200000),
            SplitEntity(transactionId = id, categoryId = "personal.salon", amountPaise = 100000))))
        val m = YearMonth.from(today)
        val byTop = db.transactions().expenseByTop(m.startMs(), m.endMs()).associate { it.topCategory to it.total }
        assertEquals(200000L, byTop["groceries"]); assertEquals(100000L, byTop["personal"])
        assertEquals(300000L, db.transactions().observeTypeTotals(m.startMs(), m.endMs()).first().single().total)
    }

    @Test fun cardBillPaymentReducesOutstanding() = runBlocking {
        val bank = db.accounts().insert(AccountEntity(name = "HDFC ••1690", kind = "BANK", openingBalancePaise = 10000000))
        val card = db.accounts().insert(AccountEntity(name = "HSBC Card ••8820", kind = "CARD"))
        repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 500000, dateMillis = today.noonMillis(),
            accountId = card, categoryId = "shopping.online", createdAt = 0, updatedAt = 0))
        repo.addManual(TransactionEntity(type = "TRANSFER", direction = "DEBIT", status = "POSTED", amountPaise = 300000, dateMillis = today.noonMillis(),
            accountId = bank, toAccountId = card, categoryId = "transfers.ccbill", createdAt = 0, updatedAt = 0))
        val f = db.transactions().observeAccountFlows().first().associateBy { it.accountId }
        val cardAcc = db.accounts().all().first { it.id == card }
        assertEquals(200000L, com.prash.kosh.ui.screens.outstandingOf(cardAcc, f[card]))
        val m = YearMonth.from(today)
        assertEquals(500000L, db.transactions().observeTypeTotals(m.startMs(), m.endMs()).first().associate { it.type to it.total }["EXPENSE"])
    }

    @Test fun deleteRemovesButKeepsHistory() = runBlocking {
        val id = repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 100, dateMillis = today.noonMillis(), createdAt = 0, updatedAt = 0))
        repo.deleteTransaction(id)
        assertTrue(db.transactions().all().isEmpty())
        assertTrue(db.audit().all().any { it.transactionId == id && it.action == "DELETED" })
    }

    @Test fun thresholdsRouteCaptures() = runBlocking {
        repo.thresholds = { 99 to 50 }
        val d = today.format(DateTimeFormatter.ofPattern("dd/MM/yy"))
        // Scores 100 -> still saved.
        repo.ingest(RawInput(CaptureSource.SMS, "VM-HDFCBK", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $d\nRef 432112345678", System.currentTimeMillis()))
        assertEquals("POSTED", db.transactions().all().single().status)
    }
}
