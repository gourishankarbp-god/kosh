package com.prash.kosh.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prash.kosh.data.AppDatabase
import com.prash.kosh.data.Migrations
import com.prash.kosh.data.Repository
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.RawInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phone <-> web compatibility, driven by scripts/cross-test.sh:
 *  1. KOSH_CROSS=write: the phone creates the encrypted space in a folder standing in for Drive.
 *  2. the web test (web/test/cross.test.ts) joins, checks, edits and adds records.
 *  3. KOSH_CROSS=read: a fresh phone joins and must see the web's changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CrossPlatformTest {
    private val dir = File(System.getProperty("user.dir"), "../build/cross-drive").canonicalFile
    private val pass = "cross platform passphrase"

    private fun phone(): Pair<AppDatabase, Repository> = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().addCallback(Migrations.TRIGGERS_CALLBACK).build()
        db to Repository(db).also { it.seedIfEmpty() }
    }

    @Test fun write() = runBlocking {
        assumeTrue(System.getenv("KOSH_CROSS") == "write")
        dir.deleteRecursively(); dir.mkdirs()
        val (db, repo) = phone()
        val d = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"))
        repo.ingest(RawInput(CaptureSource.SMS, "VM-HDFCBK-S", "Sent Rs.250.00\nFrom HDFC Bank A/C *4821\nTo SWIGGY\nOn $d\nRef 432112345678\nNot You?", System.currentTimeMillis()))
        SyncEngine(db, FolderRemoteStore(dir), MemorySyncState("phoneA")).setup(pass)
        db.close()
    }

    @Test fun read() = runBlocking {
        assumeTrue(System.getenv("KOSH_CROSS") == "read")
        val (db, _) = phone()
        SyncEngine(db, FolderRemoteStore(dir), MemorySyncState("phoneB")).setup(pass)
        val all = db.transactions().all()
        val swiggy = all.single { it.amountPaise == 25_000L }
        assertEquals("edited on the web", swiggy.note)
        val web = all.single { it.amountPaise == 1_234_500L }
        assertEquals("Web shop", web.merchantName)
        assertEquals("POSTED", web.status)
        assertEquals("4821", db.accounts().all().first { it.id == web.accountId }.last4)
        val split = db.extras().splitsFor(listOf(web.id))
        assertEquals(2, split.size)
        assertTrue(db.budgets().all().any { it.categoryId == "food" && it.monthlyLimitPaise == 500_000L })
        db.close()
    }
}
