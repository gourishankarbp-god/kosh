package com.prash.kosh.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading from v0.1.x (database version 1) must keep every transaction and add the new tables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v1ToV2KeepsDataAndAddsTables() {
        helper.createDatabase("mig.db", 1).apply {
            execSQL("INSERT INTO categories (id, name, parentId, kind, icon, sortOrder, hidden) VALUES ('housing', 'Housing and Rent', NULL, 'EXPENSE', 'home', 1, 0)")
            execSQL("INSERT INTO categories (id, name, parentId, kind, icon, sortOrder, hidden) VALUES ('housing.rent', 'Rent', 'housing', 'EXPENSE', 'home', 2, 0)")
            execSQL("INSERT INTO categories (id, name, parentId, kind, icon, sortOrder, hidden) VALUES ('investments', 'Investments', NULL, 'INVESTMENT', 'trending_up', 3, 0)")
            execSQL("""INSERT INTO transactions (type, direction, status, amountPaise, dateMillis, method, confidence, sources, createdAt, updatedAt, categoryId, merchantName)
                VALUES ('EXPENSE', 'DEBIT', 'POSTED', 2800000, 1795000000000, 'OTHER', 100, 'MANUAL', 1, 1, 'housing.rent', 'Landlord')""")
            close()
        }
        // Room validates the upgraded schema against the v2 entities here.
        helper.runMigrationsAndValidate("mig.db", 2, true, Migrations.V1_TO_V2).close()
        helper.runMigrationsAndValidate("mig.db", 3, true, Migrations.V2_TO_V3).close()
        helper.runMigrationsAndValidate("mig.db", 4, true, Migrations.V3_TO_V4).close()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "mig.db").addMigrations(Migrations.V1_TO_V2, Migrations.V2_TO_V3, Migrations.V3_TO_V4).addCallback(Migrations.TRIGGERS_CALLBACK).allowMainThreadQueries().build()
        runBlocking {
            val t = db.transactions().all().single()
            assertEquals(2800000L, t.amountPaise)
            assertEquals("Landlord", t.merchantName)
            assertNull(t.recurringId)
            assertTrue(db.categories().get("housing")!!.isFixed)
            assertEquals("Bond payments", db.categories().get("investments.bonds")!!.name)
            assertTrue(db.budgets().all().isEmpty())
            assertTrue(db.investments().all().isEmpty())
            assertNull(t.toAccountId)
            assertEquals("Reimbursement", db.categories().get("income.reimbursement")!!.name)
            assertTrue(db.taught().all().isEmpty())
            // Change capture works after the upgrade.
            db.transactions().update(t.copy(note = "rent"))
            assertTrue(db.sync().outboxCount() >= 1)
        }
        db.close()
    }
}
