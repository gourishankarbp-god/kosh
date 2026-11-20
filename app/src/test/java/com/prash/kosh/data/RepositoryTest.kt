package com.prash.kosh.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.backup.BackupCrypto
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import com.prash.kosh.engine.TxnStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end pipeline on a real (in-memory) Room database: capture -> engine -> duplicates
 * -> status routing -> totals -> learning -> audit. SQLCipher is swapped for plain SQLite
 * here because its native library only runs on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RepositoryTest {

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

    private val today get() = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
    private val dmy get() = today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))
    private val dmyDash get() = today.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yy"))

    @Test fun highConfidenceSmsIsPostedAndAccountIsCreated() = runBlocking {
        val out = repo.ingest(sms("VM-HDFCBK-S", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $dmy\nRef 432112345678\nNot You?"))
        assertEquals(IngestOutcome.POSTED, out)
        val t = db.transactions().all().single()
        assertEquals(TxnStatus.POSTED.name, t.status)
        assertEquals("food.delivery", t.categoryId)
        val acct = db.accounts().all().first { it.last4 == "4821" }
        assertEquals("HDFC ••4821", acct.name)
        assertEquals(acct.id, t.accountId)
        assertEquals("CREATED", db.audit().all().single().action)
    }

    @Test fun sameSmsTwiceIsSkipped() = runBlocking {
        val m = sms("VM-HDFCBK", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $dmy\nRef 432112345678")
        repo.ingest(m)
        assertEquals(IngestOutcome.ALREADY_SEEN, repo.ingest(m.copy(receivedAtMillis = now + 5000)))
        assertEquals(1, db.transactions().all().size)
    }

    @Test fun smsAndGpayNotificationMergeIntoOne() = runBlocking {
        repo.ingest(sms("VM-HDFCBK", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $dmy\nRef 432112345678"))
        val out = repo.ingest(RawInput(CaptureSource.NOTIFICATION, "com.google.android.apps.nbu.paisa.user", "₹250 paid to Swiggy", now + 20_000))
        assertEquals(IngestOutcome.MERGED, out)
        val t = db.transactions().all().single()
        assertTrue(t.sources.contains("SMS") && t.sources.contains("NOTIFICATION"))
    }

    @Test fun lowConfidenceGoesToReviewAndIsNotCounted() = runBlocking {
        // Unknown sender, unknown merchant, no keyword -> low confidence.
        val out = repo.ingest(RawInput(CaptureSource.SHARED, "shared", "Rs 780 paid to ZXQ Traders via UPI", now))
        assertEquals(IngestOutcome.NEEDS_REVIEW, out)
        val from = now - 86_400_000; val to = now + 86_400_000
        assertTrue(db.transactions().observeTypeTotals(from, to).first().isEmpty())
        assertEquals(1, db.transactions().observeReviewCount().first())
    }

    @Test fun investmentsAndTransfersAreNotExpenses() = runBlocking {
        repo.ingest(sms("VM-HDFCBK", "Rs 5,000.00 debited from A/c XX4821 on $dmyDash towards NACH-MF ICCL SIP. Avl Bal Rs 80,112.00"))
        repo.ingest(sms("VM-HDFCBK", "Payment of Rs 12,450.00 has been received towards your HDFC Bank Credit Card XX7712 on $dmyDash. Thank you."))
        repo.ingest(sms("VM-HDFCBK-S", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $dmy\nRef 432112345678"))
        // The SIP message scores 90: it waits in Review ("Check") until approved.
        val sip = db.transactions().all().first { it.type == "INVESTMENT" }
        assertEquals(TxnStatus.PENDING_VERIFICATION.name, sip.status)
        repo.setStatus(sip.id, TxnStatus.POSTED)
        val totals = db.transactions().observeTypeTotals(now - 86_400_000 * 2, now + 86_400_000).first().associate { it.type to it.total }
        assertEquals(25000L, totals["EXPENSE"])
        assertEquals(500000L, totals["INVESTMENT"])
        // The card-bill message names no merchant, so it scores 73 and waits in Review (not counted yet).
        val cc = db.transactions().all().first { it.amountPaise == 1245000L }
        assertEquals("TRANSFER", cc.type)
        assertEquals("transfers.ccbill", cc.categoryId)
        assertEquals(TxnStatus.NEEDS_REVIEW.name, cc.status)
    }

    @Test fun refundLinksToOriginalPurchase() = runBlocking {
        repo.ingest(sms("AD-HDFCBK", "Spent Rs.1,249 From HDFC Bank Card x7712 At AMAZON On ${today.minusDays(3)}:21:04:55", now - 3 * 86_400_000L))
        repo.ingest(sms("VM-HDFCBK", "Rs 1,249.00 refund from AMAZON has been credited to your HDFC Bank Card x7712 on $dmyDash."))
        val all = db.transactions().all()
        val refund = all.first { it.type == "REFUND" }
        val purchase = all.first { it.type == "EXPENSE" }
        assertEquals(purchase.id, refund.linkedTxnId)
    }

    @Test fun correctionCreatesRuleThatAppliesNextTime() = runBlocking {
        repo.ingest(sms("VM-FEDBNK", "Rs 150.00 debited from your A/c XX2201 on $dmyDash towards ZXQ Traders. UPI Ref 432200001111."))
        val t = db.transactions().all().single()
        assertEquals("other", t.categoryId)
        repo.saveEdit(t.copy(categoryId = "groceries.supermarket"))
        repo.rememberMerchantCategory(t.merchantKey!!, t.merchantName!!, "groceries.supermarket", applyToPast = false)
        repo.ingest(sms("VM-FEDBNK", "Rs 90.00 debited from your A/c XX2201 on $dmyDash towards ZXQ Traders. UPI Ref 432200002222."))
        val second = db.transactions().all().first { it.amountPaise == 9000L }
        assertEquals("groceries.supermarket", second.categoryId)
        // The edit was audited with before and after.
        val edit = db.audit().all().first { it.action == "EDITED" }
        assertNotNull(edit.before); assertNotNull(edit.after)
    }

    @Test fun otpIsStoredAsIgnoredNotAsTransaction() = runBlocking {
        assertEquals(IngestOutcome.IGNORED, repo.ingest(sms("VM-HDFCBK", "OTP is 482913 for txn of Rs 2,000.00 at AMAZON. Do not share.")))
        assertTrue(db.transactions().all().isEmpty())
        assertEquals("OTP", db.rawMessages().all().single().ignoreReason)
    }

    @Test fun backupRoundTripRestoresEverything() = runBlocking {
        repo.ingest(sms("VM-HDFCBK-S", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $dmy\nRef 432112345678"))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val snapshot = com.prash.kosh.backup.BackupData(
            createdAt = now, accounts = db.accounts().all(), categories = db.categories().all(), merchants = db.merchants().all(),
            keywordRules = db.keywordRules().all(), rawMessages = db.rawMessages().all(), transactions = db.transactions().all(), audit = db.audit().all(),
        )
        val bytes = BackupCrypto.encrypt(json.encodeToString(com.prash.kosh.backup.BackupData.serializer(), snapshot).toByteArray(), "correct horse")
        try { BackupCrypto.decrypt(bytes, "wrong password"); throw AssertionError("should fail") } catch (e: BackupCrypto.WrongPasswordException) {}
        val back = json.decodeFromString(com.prash.kosh.backup.BackupData.serializer(), String(BackupCrypto.decrypt(bytes, "correct horse")))
        assertEquals(snapshot, back)
    }
}
