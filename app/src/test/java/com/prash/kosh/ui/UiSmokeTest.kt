package com.prash.kosh.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.Repository
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import com.prash.kosh.ui.screens.CaptureScreen
import com.prash.kosh.ui.screens.CategoriesScreen
import com.prash.kosh.ui.screens.DashboardScreen
import com.prash.kosh.ui.screens.EditTransactionScreen
import com.prash.kosh.ui.screens.MerchantRulesScreen
import com.prash.kosh.ui.screens.MoreScreen
import com.prash.kosh.ui.screens.ReviewScreen
import com.prash.kosh.ui.screens.TransactionsScreen
import com.prash.kosh.ui.theme.KoshTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders every main screen against a seeded database to catch crashes and empty layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.prash.kosh.ScreenshotApp::class)
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var repo: Repository
    private lateinit var main: MainViewModel

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        repo.seedIfEmpty()
        val now = System.currentTimeMillis()
        val d = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))
        repo.ingest(RawInput(CaptureSource.SMS, "VM-HDFCBK", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $d\nRef 432112345678", now))
        repo.ingest(RawInput(CaptureSource.SHARED, "shared", "Rs 780 paid to ZXQ Traders via UPI", now))
        main = MainViewModel(repo)
    }

    @Test fun dashboardShowsTotalsAndReviewBanner() {
        compose.setContent { KoshTheme { DashboardScreen(main, DashboardViewModel(repo, main.month, main.categoriesById), {}, {}) } }
        // Expenses card shows the posted Swiggy payment; the low-confidence one is not counted.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("₹250").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("1 transaction waiting for your review").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun transactionsListAndFilters() {
        compose.setContent { KoshTheme { TransactionsScreen(main, TransactionsViewModel(repo, main.month), {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Swiggy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Income").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Swiggy").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun reviewQueueApprove() {
        compose.setContent { KoshTheme { ReviewScreen(main) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Approve").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Approve").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("All clear. Nothing needs your attention.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun editScreenLoadsTransaction() {
        val id = runBlocking { repo.db.transactions().all().first { it.merchantKey == "swiggy" }.id }
        compose.setContent { KoshTheme { EditTransactionScreen(main, id) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit transaction").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Food & Dining › Delivery").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Source messages").assertExists()
    }

    @Test fun addScreenRendersEmpty() {
        compose.setContent { KoshTheme { EditTransactionScreen(main, null) {} } }
        compose.onNodeWithText("Add transaction").assertIsDisplayed()
    }

    @Test fun moreAndSettingsScreensRender() {
        var opened = ""
        compose.setContent { KoshTheme { MoreScreen { opened = it } } }
        compose.onNodeWithText("Accounts").performClick()
        assertTrue(opened == "accounts")
    }

    @Test fun merchantRulesListsDictionary() {
        compose.setContent { KoshTheme { MerchantRulesScreen(main) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("ACKO").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun categoriesAndCaptureRender() {
        compose.setContent { KoshTheme { androidx.compose.foundation.layout.Column { CategoriesScreen(main) {} } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Food & Dining").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun captureScreenRenders() {
        compose.setContent { KoshTheme { CaptureScreen {} } }
        compose.onNodeWithText("Allow SMS access").assertIsDisplayed()
    }
}
