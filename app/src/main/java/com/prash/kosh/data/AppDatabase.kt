package com.prash.kosh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.prash.kosh.security.KeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, MerchantEntity::class, KeywordRuleEntity::class,
        RawMessageEntity::class, TransactionEntity::class, AuditLogEntity::class,
        BudgetEntity::class, RecurringRuleEntity::class, GoalEntity::class, GoalContributionEntity::class,
        InvestmentEntity::class, InvestmentEntryEntity::class,
        SplitEntity::class, SummaryEntity::class, DocumentEntity::class, ArchiveIndexEntity::class,
        SyncMetaEntity::class, SyncOutboxEntity::class, SyncFlagEntity::class,
    ],
    views = [ExpenseLine::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun rawMessages(): RawMessageDao
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun merchants(): MerchantDao
    abstract fun keywordRules(): KeywordRuleDao
    abstract fun audit(): AuditDao
    abstract fun budgets(): BudgetDao
    abstract fun recurring(): RecurringDao
    abstract fun goals(): GoalDao
    abstract fun investments(): InvestmentDao
    abstract fun extras(): ExtrasDao
    abstract fun sync(): SyncDao

    companion object {
        fun create(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = KeyManager.databasePassphrase(context)
            return Room.databaseBuilder(context, AppDatabase::class.java, "kosh.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // Upgrades keep every existing transaction: explicit migrations only, never destructive.
                .addMigrations(Migrations.V1_TO_V2, Migrations.V2_TO_V3)
                .addCallback(Migrations.TRIGGERS_CALLBACK)
                .build()
        }
    }
}
