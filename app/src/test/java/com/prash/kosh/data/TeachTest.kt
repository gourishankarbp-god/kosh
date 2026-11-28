package com.prash.kosh.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import com.prash.kosh.engine.Taught
import com.prash.kosh.engine.TxnStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Teach Kosh this message": saving a format, re-reading skipped messages, updating a transaction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TeachTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private val now = System.currentTimeMillis()

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        repo.seedIfEmpty()
    }

    @After fun tearDown() = db.close()

    private fun sms(sender: String, body: String, at: Long = now) = RawInput(CaptureSource.SMS, sender, body, at)

    // No credit/debit wording, so the built-in rules skip it.
    private val opdText = "Your OPD reimbursement claim 55821 for Rs 2,450.00 is settled. Amount will reflect in your bank account shortly. - MediAssist"

    private fun pattern(name: String, sender: String?, words: String, category: String, accountId: Long? = null) = TaughtPatternEntity(
        name = name, sender = sender, keywords = words, direction = "CREDIT", type = "INCOME", categoryId = category,
        amountIndex = 0, accountId = accountId, sample = "", createdAt = now,
    )

    @Test fun teachingReadsTheSkippedMessageAndFutureOnes() = runBlocking {
        assertEquals(IngestOutcome.IGNORED, repo.ingest(sms("VM-MEDIAS-S", opdText)))
        val raw = db.rawMessages().all().single()
        val bank = db.accounts().insert(AccountEntity(name = "Union Bank ••1234", kind = "BANK", bank = "Union Bank", last4 = "1234"))

        val res = repo.teach(pattern("OPD reimbursement", "MEDIAS", "opd|reimbursement", Taught.REIMBURSEMENT_CATEGORY, bank), raw.id)
        assertEquals(1, res.readNow)
        assertEquals(0, res.updated)

        val t = db.transactions().all().single()
        assertEquals(TxnStatus.PENDING_VERIFICATION.name, t.status)   // waits in Check
        assertEquals("INCOME", t.type)
        assertEquals("income.reimbursement", t.categoryId)
        assertEquals(245_000L, t.amountPaise)
        assertEquals(bank, t.accountId)
        assertEquals("OPD reimbursement", t.merchantName)
        assertEquals("RECOGNISED", db.rawMessages().get(raw.id)!!.outcome)

        // The next one is read on arrival, also into Check.
        val next = opdText.replace("55821", "55990").replace("2,450.00", "1,100.00")
        assertEquals(IngestOutcome.PENDING, repo.ingest(sms("VM-MEDIAS-S", next, now + 86_400_000)))
        assertEquals(2, db.taught().all().single().matchCount)
        assertNotNull(db.taught().all().single().lastMatchedAt)
    }

    @Test fun teachingFromAnExistingTransactionUpdatesIt() = runBlocking {
        val salary = "Dear Customer, Your A/c XX4821 is credited with INR 85,000.00 on 30-11-2026 by NEFT-SAL-ACME TECHNOLOGIES PVT LTD. Avl Bal INR 1,02,345.67 -HDFC Bank"
        repo.ingest(sms("VM-HDFCBK-S", salary))
        val raw = db.rawMessages().all().single()
        val before = db.transactions().all().single()

        val res = repo.teach(pattern("Salary", "HDFCBK", "sal|neft", "income.salary"), raw.id)
        assertEquals(1, res.updated)
        val after = db.transactions().get(before.id)!!
        assertEquals("income.salary", after.categoryId)
        assertEquals("Salary", after.merchantName)
        assertEquals(before.status, after.status)
        assertEquals("RULE_APPLIED", db.audit().all().last().action)
    }

    @Test fun forgettingStopsMatching() = runBlocking {
        val res = repo.teach(pattern("OPD reimbursement", null, "opd|reimbursement", Taught.REIMBURSEMENT_CATEGORY), null)
        repo.deleteTaught(res.patternId)
        assertEquals(IngestOutcome.IGNORED, repo.ingest(sms("VM-MEDIAS-S", opdText)))
    }
}
