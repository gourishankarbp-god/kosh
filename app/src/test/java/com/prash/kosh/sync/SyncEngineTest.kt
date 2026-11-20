package com.prash.kosh.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.Repository
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Device(val db: AppDatabase, val repo: Repository, val state: MemorySyncState, store: RemoteStore) {
        val sync = SyncEngine(db, store, state)
    }

    private lateinit var store: FolderRemoteStore
    private val devices = mutableListOf<Device>()

    private fun device(id: String): Device = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries()
            .addCallback(com.prash.kosh.data.Migrations.TRIGGERS_CALLBACK).build()
        val repo = Repository(db); repo.seedIfEmpty()
        Device(db, repo, MemorySyncState(id), store).also { devices += it }
    }

    @Before fun setUp() { store = FolderRemoteStore(tmp.newFolder("drive")) }
    @After fun tearDown() { devices.forEach { it.db.close() } }

    private val ist = ZoneId.of("Asia/Kolkata")
    private val today get() = java.time.LocalDate.now(ist)
    private fun sms(body: String) = RawInput(CaptureSource.SMS, "VM-HDFCBK-S", body, System.currentTimeMillis())
    private fun hdfc(amount: String, to: String, ref: String) = sms("Sent Rs.$amount\nFrom HDFC Bank A/C *4821\nTo $to\nOn ${today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))}\nRef $ref\nNot You?")

    @Test fun twoDevicesConvergeWithEditsAndDeletes() = runBlocking {
        val phone = device("phone"); val web = device("web")
        phone.repo.ingest(hdfc("250.00", "SWIGGY", "432112345678"))
        assertEquals(SyncEngine.SetupResult.CreatedNew, phone.sync.setup("correct horse"))
        assertEquals(SyncEngine.SetupResult.JoinedExisting, web.sync.setup("correct horse"))

        val t = web.db.transactions().all().single()
        assertEquals(25_000L, t.amountPaise)
        assertEquals("food.delivery", t.categoryId)
        // The account reference was carried by uid and resolved to the web device's own account row.
        assertEquals("4821", web.db.accounts().all().first { it.id == t.accountId }.last4)
        // Seeded rows were matched, not duplicated.
        assertEquals(phone.db.accounts().all().size, web.db.accounts().all().size)
        assertEquals(phone.db.categories().all().size, web.db.categories().all().size)

        // Edit on the web, add on the phone, sync both ways.
        web.repo.saveEdit(t.copy(note = "dinner", categoryId = "food.dining"))
        web.sync.push()
        phone.repo.ingest(hdfc("99.00", "ZEPTO", "432112345679"))
        phone.sync.syncNow(); web.sync.syncNow()
        assertEquals("dinner", phone.db.transactions().all().first { it.amountPaise == 25_000L }.note)
        assertEquals(2, web.db.transactions().all().size)

        // Delete on the phone reaches the web.
        val zepto = phone.db.transactions().all().first { it.amountPaise == 9_900L }
        phone.repo.deleteTransaction(zepto.id)
        phone.sync.push(); web.sync.pull()
        assertEquals(1, web.db.transactions().all().size)
        // Applying remote changes must not echo them back into the outbox.
        assertEquals(0, web.db.sync().outboxCount())
    }

    @Test fun newestEditWinsOnConflict() = runBlocking {
        val a = device("aaa"); val b = device("bbb")
        a.repo.ingest(hdfc("120.00", "UBER", "500000000001"))
        a.sync.setup("pw"); b.sync.setup("pw")
        val ta = a.db.transactions().all().single(); val tb = b.db.transactions().all().single()
        a.repo.saveEdit(ta.copy(note = "older"))
        a.sync.push()
        Thread.sleep(5)
        b.repo.saveEdit(tb.copy(note = "newer"))
        b.sync.push()
        a.sync.pull(); b.sync.pull()
        assertEquals("newer", a.db.transactions().all().single().note)
        assertEquals("newer", b.db.transactions().all().single().note)
    }

    @Test fun wrongPassphraseIsRejected() = runBlocking {
        val a = device("aaa"); val b = device("bbb")
        a.sync.setup("right")
        try { b.sync.setup("wrong"); fail("should reject") } catch (_: SyncCrypto.WrongPassphrase) {}
        assertNull(b.state.key)
    }

    @Test fun filesInDriveAreEncrypted() = runBlocking {
        val a = device("aaa")
        a.repo.ingest(hdfc("250.00", "SWIGGY", "432112345678"))
        a.sync.setup("pw")
        val ops = store.list("ops-")
        assertTrue(ops.isNotEmpty())
        ops.forEach { f -> val text = String(store.download(f.id), Charsets.ISO_8859_1); assertFalse(text.contains("SWIGGY", true)); assertFalse(text.contains("4821")) }
    }

    @Test fun oldMonthsAreArchivedToDriveAndReadable() = runBlocking {
        val a = device("aaa"); val b = device("bbb")
        a.sync.setup("pw"); b.sync.setup("pw")
        val cash = a.repo.cashAccountId()
        val old = YearMonth.now(ist).minusMonths(14)
        val at = old.atDay(10).atStartOfDay(ist).toInstant().toEpochMilli()
        a.repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 50_000, dateMillis = at, accountId = cash, merchantName = "Old shop", categoryId = "food.groceries", method = "CASH", createdAt = at, updatedAt = at))
        a.repo.addManual(TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = 1_000, dateMillis = System.currentTimeMillis(), accountId = cash, merchantName = "New shop", categoryId = "food.groceries", method = "CASH", createdAt = 1, updatedAt = 1))
        a.sync.push(); b.sync.pull()
        assertEquals(2, b.db.transactions().all().size)

        assertEquals(listOf(old.toString()), a.sync.archiveOldMonths())
        assertEquals(1, a.db.transactions().all().size)
        val idx = a.db.extras().archive(old.toString())!!
        assertEquals(50_000L, idx.expense)
        // Other device drops the month too, but keeps the totals.
        b.sync.pull()
        assertEquals(1, b.db.transactions().all().size)
        assertEquals(50_000L, b.db.extras().archive(old.toString())!!.expense)
        // Archived data can be fetched on request.
        val recs = b.sync.fetchArchive(old.toString())
        assertTrue(recs.any { it.toString().contains("Old shop") })
        // Archive-driven local removal did not create deletes in Drive.
        assertEquals(0, b.db.sync().outboxCount())
        assertEquals(1, a.db.transactions().all().size)
    }
}
