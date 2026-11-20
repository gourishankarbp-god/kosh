package com.prash.kosh

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.data.InvestmentEntity
import com.prash.kosh.data.RecurringRuleEntity
import com.prash.kosh.data.GoalEntity
import com.prash.kosh.data.saveInvestment
import com.prash.kosh.data.saveBudget
import com.prash.kosh.data.saveRecurringRule
import com.prash.kosh.data.addGoalContribution
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import com.prash.kosh.ui.theme.KoshTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** App container backed by an in-memory database (SQLCipher's native library only runs on a phone). */
class ScreenshotApp : KoshApp() {
    override fun isTestHost() = true
    override val db: AppDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).allowMainThreadQueries().build()
    }
    override fun onCreate() { /* no WorkManager or seeding here; the test seeds */ }
}

/** Renders every page of the app with sample data and saves PNGs to app/build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = ScreenshotApp::class, qualifiers = "w412dp-h1180dp-xxhdpi")
class ScreenshotTour {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300); compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(t: String) = compose.waitUntil(8_000) { compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(t: String) { waitText(t); compose.onAllNodesWithText(t).onFirst().performClick(); settle() }
    private fun scrollTap(t: String) {
        settle()
        compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onLast()
            .performScrollToNode(androidx.compose.ui.test.hasText(t))
        tap(t)
    }
    private fun back() { compose.onAllNodesWithContentDescription("Back").onLast().performClick(); settle() }
    /** Let navigation transitions finish so only the new screen is on display. */
    private fun settle() { repeat(3) { compose.mainClock.advanceTimeBy(400); compose.waitForIdle() } }

    private var licId = 0L
    /** When set, the tour shows this screen directly instead of the app's navigation. */
    private var direct by androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) { direct = content; settle() }

    private fun millis(d: LocalDate, h: Int = 12) = d.atTime(LocalTime.of(h, 0)).atZone(ist).toInstant().toEpochMilli()

    private suspend fun seed(app: KoshApp) {
        val repo = app.repo
        repo.seedIfEmpty()
        val now = System.currentTimeMillis()
        val thisMonth = YearMonth.now(ist)
        // Sample history for the trend chart (illustrative amounts, not real data).
        val monthly = listOf(
            Triple("INCOME", "income.salary", 12000000L) to "Salary",
            Triple("EXPENSE", "housing.rent", 2800000L) to "Rent",
            Triple("EXPENSE", "groceries.quick", 920000L) to "Blinkit",
            Triple("EXPENSE", "food.delivery", 640000L) to "Swiggy",
            Triple("EXPENSE", "fuel.petrol", 450000L) to "Indian Oil",
            Triple("EXPENSE", "utilities.electricity", 230000L) to "BESCOM",
            Triple("EXPENSE", "utilities.mobile", 99900L) to "Jio",
            Triple("EXPENSE", "subscriptions.streaming", 64900L) to "Netflix",
            Triple("INVESTMENT", "investments.mf", 1000000L) to "Groww SIP",
        )
        for (back in 5 downTo 1) {
            val ym = thisMonth.minusMonths(back.toLong())
            monthly.forEachIndexed { i, (t, name) ->
                val wobble = 1.0 + ((back * 7 + i * 3) % 9 - 4) / 25.0
                val amt = if (t.first == "INCOME") t.third else (t.third * wobble).toLong()
                repo.addManual(TransactionEntity(type = t.first, direction = if (t.first == "INCOME") "CREDIT" else "DEBIT",
                    status = "POSTED", amountPaise = amt, dateMillis = millis(ym.atDay(2 + i * 3)), merchantName = name,
                    merchantKey = name.lowercase(), categoryId = t.second, createdAt = now, updatedAt = now))
            }
            repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = (180000L * (6 - back)),
                dateMillis = millis(ym.atDay(20)), merchantName = "Myntra", merchantKey = "myntra", categoryId = "shopping.clothing", createdAt = now, updatedAt = now))
        }
        // This month: salary, rent, then your real message formats run through the engine.
        val d1 = thisMonth.atDay(1)
        repo.addManual(TransactionEntity(type = "INCOME", direction = "CREDIT", status = "POSTED", amountPaise = 12000000L, dateMillis = millis(d1),
            merchantName = "Salary", merchantKey = "salary", categoryId = "income.salary", createdAt = now, updatedAt = now))
        repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 2800000L, dateMillis = millis(thisMonth.atDay(3)),
            merchantName = "Rent", merchantKey = "rent", categoryId = "housing.rent", createdAt = now, updatedAt = now))
        val today = LocalDate.now(ist)
        val dmy = today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))
        val dm2 = today.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yy"))
        val msgs = listOf(
            "VM-HSBCIN" to "HSBC Credit Card xx8820 used at APOLLO PHARMACIES LTD for INR 891.95 on $dmy. Avl limit INR 127560.53; due INR 26439.47. Call +914065118002 to report fraud.( Online)",
            "VM-HSBCIN" to "HSBC Credit Card xx8820 used at AMAZON PAY INDIA PRIVATET for INR 804.00 on $dmy. Avl limit INR 126756.53; due INR 27243.47. Call +914065118002 to report fraud.",
            "VM-HDFCBK" to "Sent Rs.26200.00\nFrom HDFC Bank A/C *1690\nTo CRED Club\nOn $dmy\nRef 668819657999\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808.",
            "VM-HDFCBK" to "Sent Rs.82.00\nFrom HDFC Bank A/C *1690\nTo RUPALI DATTATRAY CHAVAN\nOn $dmy\nRef 828348136803\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
            "VM-HDFCBK" to "Credit Alert!\nRs.529.00 credited to HDFC Bank A/c XX1690 on $dm2 from VPA *8221@ybl (UPI 030835982809)",
            "AX-UNIONB" to "Union Bank of India A/c*1131 Dr Rs3000.00 on $dm2 10:39 by MB 538434129626 Fv.GOURI, Bal Rs22162.60 Not U?Call 18002333/SMS BLOCK 1131 to 8879635472",
            "VM-HDFCBK" to "Rs 5,000.00 debited from A/c XX1690 on $dm2 towards NACH-MF ICCL SIP. Avl Bal Rs 80,112.00",
            "VM-UNIONB" to "Your OTP is 482913 for txn of Rs 2,000.00. Never share OTP/PIN/CVV-Union Bank of India",
            "VM-RBLBNK" to "Congratulations! You are pre-approved for a Personal Loan of Rs 5,00,000. Apply now: rbl.bank/pl",
        )
        // Phase 2/3 sample setup (illustrative).
        app.setUserName("Prash")
        repo.saveInvestment(InvestmentEntity(kind = "FD", name = "HDFC FD 2026", institution = "HDFC Bank", startDate = today.minusMonths(8).toString(),
            maturityDate = today.plusMonths(4).toString(), ratePercent = 7.1, maturityAmountPaise = 21420000), 20000000)
        licId = repo.saveInvestment(InvestmentEntity(kind = "LIC", name = "Jeevan Anand", institution = "LIC of India", refLast4 = "4471",
            startDate = today.minusYears(2).plusDays(12).toString(), installmentPaise = 1260000, frequency = "YEARLY",
            sumAssuredPaise = 50000000, maturityDate = today.plusYears(19).toString(), maturityAmountPaise = 98000000), 2520000)
        repo.saveInvestment(InvestmentEntity(kind = "BOND", name = "RBI Floating Rate Bond", institution = "RBI Retail Direct",
            startDate = today.minusMonths(6).toString(), maturityDate = today.plusYears(6).toString(), ratePercent = 8.05), 10000000)
        repo.saveInvestment(InvestmentEntity(kind = "INSURANCE", name = "Star Health family floater", institution = "Star Health",
            startDate = today.minusMonths(10).toString(), installmentPaise = 2450000, frequency = "YEARLY", sumAssuredPaise = 100000000), 2450000)
        repo.saveInvestment(InvestmentEntity(kind = "RD", name = "Union Bank RD", institution = "Union Bank", startDate = today.minusMonths(3).plusDays(4).toString(),
            installmentPaise = 300000, frequency = "MONTHLY", ratePercent = 6.5, maturityDate = today.plusMonths(21).toString()), 900000)
        val ym = YearMonth.from(today)
        repo.saveBudget("food", 800000, false, ym); repo.saveBudget("groceries", 1200000, true, ym)
        repo.saveBudget("shopping", 500000, false, ym); repo.saveBudget("housing", 2900000, false, ym); repo.saveBudget("transport", 400000, false, ym)
        repo.saveRecurringRule(RecurringRuleEntity(name = "Rent", type = "EXPENSE", amountPaise = 2800000, categoryId = "housing.rent", merchantKey = "rent",
            frequency = "MONTHLY", startDate = ym.plusMonths(1).atDay(3).toString(), nextDueDate = ym.plusMonths(1).atDay(3).toString()))
        repo.saveRecurringRule(RecurringRuleEntity(name = "Airtel broadband", type = "EXPENSE", amountPaise = 117800, categoryId = "utilities.mobile",
            frequency = "MONTHLY", startDate = today.plusDays(2).toString(), nextDueDate = today.plusDays(2).toString()))
        repo.saveRecurringRule(RecurringRuleEntity(name = "Netflix", type = "EXPENSE", amountPaise = 64900, categoryId = "subscriptions.streaming",
            merchantKey = "netflix", frequency = "MONTHLY", startDate = today.plusDays(9).toString(), nextDueDate = today.plusDays(9).toString()))
        repo.saveRecurringRule(RecurringRuleEntity(name = "Salary", type = "INCOME", amountPaise = 12000000, categoryId = "income.salary",
            frequency = "MONTHLY", startDate = ym.plusMonths(1).atDay(1).toString(), nextDueDate = ym.plusMonths(1).atDay(1).toString()))
        val ef = app.db.goals().upsert(GoalEntity(name = "Emergency fund", kind = "EMERGENCY", targetPaise = 30000000, createdAt = now))
        repo.addGoalContribution(ef, 15000000, today.minusMonths(2), null)
        val lap = app.db.goals().upsert(GoalEntity(name = "New laptop", kind = "PURCHASE", targetPaise = 12000000, targetDate = today.plusMonths(7).toString(), createdAt = now))
        repo.addGoalContribution(lap, 3000000, today.minusDays(10), null)
        repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 64000, dateMillis = millis(today.minusDays(2)),
            merchantName = "Zepto", merchantKey = "zepto", categoryId = "groceries.quick", createdAt = now, updatedAt = now))
        repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 112000, dateMillis = millis(today.minusDays(5)),
            merchantName = "Blinkit", merchantKey = "blinkit", categoryId = "groceries.quick", createdAt = now, updatedAt = now))
        msgs.forEachIndexed { i, (s, b) -> repo.ingest(RawInput(CaptureSource.SMS, s, b, now - (msgs.size - i) * 60_000L)) }
        // Approve the checks so the sample month looks lived-in; leave genuine review items.
        app.db.transactions().all().filter { it.status == "PENDING_VERIFICATION" && it.type != "EXPENSE" }
            .forEach { repo.setStatus(it.id, com.prash.kosh.engine.TxnStatus.POSTED) }
        repo.ingest(RawInput(CaptureSource.SHARED, "shared", "Rs 780 paid to ZXQ Traders via UPI", now))
        repo.ingest(RawInput(CaptureSource.SMS, "VM-SBIINB", "Rs 2,000 withdrawn at ATM S1BW000123 from A/c X3310 on ${today.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyy"))}. Avl Bal Rs 18,000. -SBI", now))
        app.db.transactions().all().filter { it.categoryId == "transfers.cash" }.forEach { repo.setStatus(it.id, com.prash.kosh.engine.TxnStatus.POSTED) }
        val cashId = repo.cashAccountId()
        repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 45000, dateMillis = millis(today),
            accountId = cashId, merchantName = "Vegetables", categoryId = "groceries.supermarket", method = "CASH", createdAt = now, updatedAt = now))
        // Same amount from a second SMS within minutes and no shared reference -> held as a possible duplicate.
        repo.ingest(RawInput(CaptureSource.SMS, "AX-UNIONB", "Union Bank of India A/c*1131 Dr Rs3000.00 on $dm2 10:41 by MB Fv.GOURI, Bal Rs19162.60", now))
    }

    @Test fun tour() {
        val app = ApplicationProvider.getApplicationContext<KoshApp>()
        runBlocking { seed(app) }
        val main = com.prash.kosh.ui.MainViewModel(app.repo)
        compose.setContent { KoshTheme(app.themeMode.value) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val d = direct
            if (d != null) d() else KoshNav(app)
        } } }

        waitText("Where the money went"); shot("01-dashboard")
        tap("Activity"); waitText("Apollo"); shot("02-transactions")
        tap("Apollo"); waitText("Source messages"); shot("03-edit-transaction"); back()
        compose.onNodeWithContentDescription("Add transaction").performClick(); waitText("Add transaction"); shot("04-add-transaction"); back()
        tap("Review"); waitText("Keep both"); shot("05-review")
        tap("More"); waitText("Capture & import"); shot("06-more")
        listOf(
            "Capture & import" to "07-capture", "Accounts" to "08-accounts", "Categories" to "09-categories",
            "Merchant rules" to "10-merchant-rules", "Keyword rules" to "11-keyword-rules", "Ignored messages" to "12-ignored",
            "Backup & restore" to "13-backup", "App lock" to "14-app-lock",
        ).forEach { (entry, file) ->
            scrollTap(entry); compose.waitForIdle(); Thread.sleep(400); shot(file); back()
        }
        // Phase 2 and 3 screens.
        tap("Plan"); waitText("Budgets"); shot("15-plan")
        val noop = {}
        show { com.prash.kosh.ui.screens.BudgetsScreen(main, noop) }; waitText("This month"); shot("16-budgets")
        show { com.prash.kosh.ui.screens.UpcomingScreen(main, noop) }; waitText("Next 60 days"); shot("17-upcoming")
        show { com.prash.kosh.ui.screens.InvestmentsScreen(main, noop, {}, noop) }; waitText("Total paid"); shot("18-investments")
        show { com.prash.kosh.ui.screens.InvestmentDetailScreen(main, licId, noop, noop) }; waitText("Premiums paid"); shot("19-investment-lic")
        show { com.prash.kosh.ui.screens.InvestmentEditScreen(main, null) {} }; waitText("Bank, insurer or fund house"); shot("20-add-investment")
        show { com.prash.kosh.ui.screens.CashScreen(main, noop) {} }; waitText("Cash in hand"); shot("28-cash")
        show { com.prash.kosh.ui.screens.AccountsScreen(main, noop) }; waitText("In banks"); shot("29-accounts")
        show { com.prash.kosh.ui.screens.ReportsScreen(main, noop) }; waitText("Monthly expenses"); Thread.sleep(500); shot("30-reports")
        show { com.prash.kosh.ui.screens.RecognitionSettingsScreen(noop) }; waitText("Confidence thresholds"); shot("31-recognition")
        kotlinx.coroutines.runBlocking {
            val old = java.time.YearMonth.now(ist).minusMonths(13).toString()
            app.db.extras().upsertArchive(com.prash.kosh.data.ArchiveIndexEntity(old, 9_500_000, 6_120_000, 0, 1_000_000, 300_000, 2_100_000, 450_000, "{}", 142, System.currentTimeMillis()))
        }
        show { com.prash.kosh.ui.screens.SyncScreen(main, noop) {} }; waitText("Connect Google Drive"); shot("32-sync")
        show { com.prash.kosh.ui.screens.AssistantScreen(main, noop) }; waitText("Ask about your money"); shot("33-assistant")
        show { androidx.compose.foundation.lazy.LazyColumn { item { com.prash.kosh.ui.screens.ImportExtrasSection() } } }; waitText("Choose screenshot"); shot("34-import-extras")
        show { com.prash.kosh.ui.screens.GoalsScreen(main, noop) }; waitText("Emergency fund"); shot("21-goals")
        show { com.prash.kosh.ui.screens.InsightsScreen(main, noop) }; waitText("Recurring expense ratio"); shot("22-insights-month")
        tap("Financial year"); waitText(com.prash.kosh.engine.Metrics.fyLabel(com.prash.kosh.engine.Metrics.fyStartYear(java.time.LocalDate.now(ist)))); shot("23-insights-year")
        direct = null; settle(); waitText("This month at a glance"); shot("24-home-greeting")
        tap("More")
        // Switch to dark from the Appearance page and revisit the main tabs.
        scrollTap("Appearance & name"); waitText("Follow phone"); tap("Dark"); shot("25-appearance-dark"); back()
        tap("Home"); waitText("Where the money went"); shot("26-dashboard-dark")
        show { com.prash.kosh.ui.screens.InsightsScreen(main, {}) }; waitText("Recurring expense ratio"); shot("27-insights-dark")
    }
}
