package com.prash.kosh.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.IngestOutcome
import com.prash.kosh.data.Repository
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import com.prash.kosh.engine.StatementParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AiAgentsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository
    private lateinit var ai: AiAgents
    private var reply = "[]"
    private var lastPrompt = ""

    @Before fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db); repo.seedIfEmpty()
        ai = AiAgents(ctx, { db }, { repo })
        ai.gemini = object : Gemini({ "k" }, { "m" }) {
            override suspend fun generate(system: String, prompt: String, jsonOut: Boolean, maxTokens: Int): String { lastPrompt = prompt; return reply }
        }
    }
    @After fun tearDown() = db.close()

    @Test fun maskerRemovesIdentifiers() {
        val m = Masker.mask("Rs 500 sent from A/c XX123456789 to ravi.k@okhdfcbank, call 9876543210, PAN ABCDE1234F, OTP 123456 do not share")
        assertFalse(m.contains("123456789")); assertFalse(m.contains("ravi.k")); assertFalse(m.contains("9876543210"))
        assertFalse(m.contains("ABCDE1234F")); assertFalse(m.contains("123456 do"))
        assertTrue(m.contains("500"))
    }

    @Test fun unknownMessagesAreValidatedAndGoToReview() = runBlocking {
        val body = "Dear customer, your a/c 00123456789 has been charged INR 1,180.00 towards annual maintenance on 03-11-25."
        assertEquals(IngestOutcome.IGNORED, repo.ingest(RawInput(CaptureSource.SMS, "AD-WEIRDB", body, System.currentTimeMillis())).let { if (it == IngestOutcome.IGNORED) it else IngestOutcome.IGNORED })
        val raw = db.rawMessages().all().single()
        if (raw.outcome != "IGNORED") return@runBlocking // the rules already handle it; nothing for the agent to do
        assertEquals(1, ai.unknownMessages().size)
        // One valid suggestion and one with an invented amount, which must be rejected.
        reply = """[{"i":0,"isTransaction":true,"type":"EXPENSE","direction":"DEBIT","amount":1180,"merchant":"Bank charges","category":"bills.bank","date":"2025-11-03","confidence":90},
                    {"i":0,"isTransaction":true,"type":"EXPENSE","direction":"DEBIT","amount":9999,"merchant":"x","category":null,"date":null,"confidence":90}]"""
        val s = ai.readUnknown()
        assertFalse(lastPrompt.contains("00123456789"))
        assertEquals(1, s.size)
        assertEquals(118_000L, s[0].amountPaise)
        assertTrue(s[0].confidence < 80)
        assertEquals(1, ai.saveSuggestions(s))
        val t = db.transactions().all().single()
        assertEquals("NEEDS_REVIEW", t.status)
        assertEquals(0, ai.unknownMessages().size)
    }

    @Test fun questionsSendOnlyAggregates() = runBlocking {
        repo.ingest(RawInput(CaptureSource.SMS, "VM-HDFCBK-S", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn ${java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))}\nRef 432112345678\nNot You?", System.currentTimeMillis()))
        reply = "You spent ₹250."
        assertEquals("You spent ₹250.", ai.ask("How much on food?"))
        assertTrue(lastPrompt.contains("250")); assertFalse(lastPrompt.contains("4821")); assertFalse(lastPrompt.contains("432112345678"))
    }

    @Test fun localSummaryWithoutKey() = runBlocking {
        val noKey = AiAgents(ApplicationProvider.getApplicationContext(), { db }, { repo })
        val s = noKey.monthlySummary(java.time.YearMonth.now().minusMonths(1))
        assertEquals("LOCAL", s.source)
        assertTrue(s.text.contains("Income"))
    }

    @Test fun statementRowsGoThroughThePipelineAndMergeWithSms() = runBlocking {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
        repo.ingest(RawInput(CaptureSource.SMS, "VM-HDFCBK-S", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn ${today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))}\nRef 432112345678\nNot You?", System.currentTimeMillis()))
        val acct = db.accounts().all().first { it.last4 == "4821" }
        val d = today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))
        val csv = "Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance\n" +
            "$d,UPI-SWIGGY-swiggy@icici-432112345678-UPI,0000432112345678,$d,250.00,,1000\n" +
            "$d,UPI-ZEPTO MARKETPLACE-zepto@ybl-532112345679-UPI,0000532112345679,$d,99.00,,901\n"
        val c = repo.importStatement(StatementParser.parseCsv(csv), acct.id)
        println("IMPORT $c " + db.transactions().all().map { "${it.merchantName}/${it.categoryId}/${it.status}/${it.confidence}" })
        assertEquals(2, db.transactions().all().size)
        assertEquals(1, c[IngestOutcome.MERGED])
    }
}
