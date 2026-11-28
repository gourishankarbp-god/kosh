package com.prash.kosh.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Database upgrades. Each one keeps all existing data. */
object Migrations {

    /** Phase 1 -> Phases 2 and 3: budgets, recurring payments, goals, investments. */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `categories` ADD COLUMN `isFixed` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `transactions` ADD COLUMN `recurringId` INTEGER")
            V2_CREATE.forEach(db::execSQL)
            // Starter fixed categories and the new bonds subcategory.
            db.execSQL("UPDATE `categories` SET `isFixed` = 1 WHERE `id` IN ('housing', 'utilities', 'subscriptions', 'insurance', 'education')")
            db.execSQL("INSERT OR IGNORE INTO `categories` (`id`, `name`, `parentId`, `kind`, `icon`, `sortOrder`, `hidden`, `isFixed`) VALUES ('investments.bonds', 'Bonds', 'investments', 'INVESTMENT', 'trending_up', 900, 0, 0)")
            db.execSQL("INSERT OR IGNORE INTO `merchants` (`key`, `displayName`, `categoryId`, `source`, `aliases`) VALUES ('flipkartminutes', 'Flipkart Minutes', 'groceries.quick', 'SEED', 'flipkartminutes|flipkart minutes')")
            db.execSQL("INSERT OR IGNORE INTO `merchants` (`key`, `displayName`, `categoryId`, `source`, `aliases`) VALUES ('amazonnow', 'Amazon Now / Fresh', 'groceries.quick', 'SEED', 'amazonnow|amazon now|amazon fresh')")
        }
    }

    /** v0.2 -> v0.3: transfers to accounts, splits, summaries, documents, archive index, sync bookkeeping. */
    val V2_TO_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `transactions` ADD COLUMN `toAccountId` INTEGER")
            V3_CREATE.forEach(db::execSQL)
            db.execSQL("INSERT OR IGNORE INTO `sync_flag` (`id`, `suppress`) VALUES (1, 0)")
            // ATM withdrawals already recorded become transfers into the Cash account.
            db.execSQL("UPDATE `transactions` SET `toAccountId` = (SELECT `id` FROM `accounts` WHERE `kind` = 'CASH' ORDER BY `id` LIMIT 1) WHERE `categoryId` = 'transfers.cash' AND `direction` = 'DEBIT'")
            // Pending (80-94%) items are no longer counted automatically: they go to Review.
            db.execSQL("UPDATE `categories` SET `name` = 'LIC & insurance premiums' WHERE `id` = 'investments.lic' AND `name` = 'LIC & policies'")
            db.execSQL("UPDATE `categories` SET `name` = 'Bond payments' WHERE `id` = 'investments.bonds' AND `name` = 'Bonds'")
            db.execSQL("INSERT OR IGNORE INTO `categories` (`id`, `name`, `parentId`, `kind`, `icon`, `sortOrder`, `hidden`, `isFixed`) VALUES ('transfers.cashdep', 'Cash deposit', 'transfers', 'TRANSFER', 'swap_horiz', 950, 0, 0)")
            db.execSQL("INSERT OR IGNORE INTO `categories` (`id`, `name`, `parentId`, `kind`, `icon`, `sortOrder`, `hidden`, `isFixed`) VALUES ('transfers.adjust', 'Balance adjustment', 'transfers', 'TRANSFER', 'swap_horiz', 951, 0, 0)")
        }
    }

    /** 1.0.x -> 1.1: message formats taught by the owner (phone-only table) and the Reimbursement income category. */
    val V3_TO_V4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(V4_CREATE_TAUGHT)
            db.execSQL("INSERT OR IGNORE INTO `categories` (`id`, `name`, `parentId`, `kind`, `icon`, `sortOrder`, `hidden`, `isFixed`) VALUES ('income.reimbursement', 'Reimbursement', 'income', 'INCOME', 'payments', 960, 0, 0)")
        }
    }

    const val V4_CREATE_TAUGHT = "CREATE TABLE IF NOT EXISTS `taught_patterns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sender` TEXT, `keywords` TEXT NOT NULL, `direction` TEXT NOT NULL, `type` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `amountIndex` INTEGER NOT NULL, `accountId` INTEGER, `sample` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `matchCount` INTEGER NOT NULL, `lastMatchedAt` INTEGER)"

    /** Tables whose changes are synced, with the column that identifies a row locally. */
    val SYNCED_TABLES = linkedMapOf(
        "accounts" to "id", "categories" to "id", "merchants" to "key", "keyword_rules" to "id",
        "recurring_rules" to "id", "savings_goals" to "id", "investments" to "id", "budgets" to "categoryId",
        "transactions" to "id", "transaction_splits" to "id", "raw_messages" to "id", "audit_log" to "id",
        "investment_entries" to "id", "goal_contributions" to "id", "summaries" to "id", "documents" to "id",
        "archive_index" to "month",
    )

    fun triggerSql(): List<String> = SYNCED_TABLES.flatMap { (t, k) ->
        val cond = "WHEN IFNULL((SELECT `suppress` FROM `sync_flag` WHERE `id` = 1), 0) = 0"
        val now = "CAST(strftime('%s','now') AS INTEGER) * 1000"
        listOf(
            "CREATE TRIGGER IF NOT EXISTS `sync_${t}_ins` AFTER INSERT ON `$t` $cond BEGIN INSERT INTO `sync_outbox` (`tbl`, `localKey`, `op`, `at`) VALUES ('$t', CAST(NEW.`$k` AS TEXT), 'U', $now); END",
            "CREATE TRIGGER IF NOT EXISTS `sync_${t}_upd` AFTER UPDATE ON `$t` $cond BEGIN INSERT INTO `sync_outbox` (`tbl`, `localKey`, `op`, `at`) VALUES ('$t', CAST(NEW.`$k` AS TEXT), 'U', $now); END",
            "CREATE TRIGGER IF NOT EXISTS `sync_${t}_del` AFTER DELETE ON `$t` $cond BEGIN INSERT INTO `sync_outbox` (`tbl`, `localKey`, `op`, `at`) VALUES ('$t', CAST(OLD.`$k` AS TEXT), 'D', $now); END",
        )
    }

    /** Creates the change-capture triggers (idempotent) every time the database opens. */
    val TRIGGERS_CALLBACK = object : androidx.room.RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT OR IGNORE INTO `sync_flag` (`id`, `suppress`) VALUES (1, 0)")
            db.execSQL("UPDATE `sync_flag` SET `suppress` = 0 WHERE `id` = 1")
            triggerSql().forEach(db::execSQL)
        }
    }

    val V3_CREATE: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `transaction_splits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `transactionId` INTEGER NOT NULL, `categoryId` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `note` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_transaction_splits_transactionId` ON `transaction_splits` (`transactionId`)",
        "CREATE TABLE IF NOT EXISTS `summaries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `month` TEXT NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_summaries_month` ON `summaries` (`month`)",
        "CREATE TABLE IF NOT EXISTS `documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `investmentId` INTEGER NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `fileKey` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_documents_investmentId` ON `documents` (`investmentId`)",
        "CREATE TABLE IF NOT EXISTS `archive_index` (`month` TEXT NOT NULL, `income` INTEGER NOT NULL, `expense` INTEGER NOT NULL, `refunds` INTEGER NOT NULL, `invested` INTEGER NOT NULL, `transfers` INTEGER NOT NULL, `recurring` INTEGER NOT NULL, `quickCommerce` INTEGER NOT NULL, `categoryTotalsJson` TEXT NOT NULL, `transactionCount` INTEGER NOT NULL, `archivedAt` INTEGER NOT NULL, PRIMARY KEY(`month`))",
        "CREATE TABLE IF NOT EXISTS `sync_meta` (`tbl` TEXT NOT NULL, `uid` TEXT NOT NULL, `localKey` TEXT NOT NULL, `version` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`tbl`, `uid`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_meta_tbl_localKey` ON `sync_meta` (`tbl`, `localKey`)",
        "CREATE TABLE IF NOT EXISTS `sync_outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tbl` TEXT NOT NULL, `localKey` TEXT NOT NULL, `op` TEXT NOT NULL, `at` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `sync_flag` (`id` INTEGER NOT NULL, `suppress` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE VIEW `expense_lines` AS SELECT t.id AS txnId, t.type AS type, t.status AS status, t.dateMillis AS dateMillis, t.categoryId AS categoryId,\n        t.amountPaise AS amountPaise, t.recurringId AS recurringId, t.merchantName AS merchantName\n        FROM transactions t WHERE NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transactionId = t.id)\n        UNION ALL\n        SELECT t.id, t.type, t.status, t.dateMillis, s.categoryId, s.amountPaise, t.recurringId, t.merchantName\n        FROM transaction_splits s JOIN transactions t ON t.id = s.transactionId",
    )

    /** CREATE statements for the tables added in version 2 (copied from Room's exported schema). */
    val V2_CREATE: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `budgets` (`categoryId` TEXT NOT NULL, `monthlyLimitPaise` INTEGER NOT NULL, `rollover` INTEGER NOT NULL, `startMonth` TEXT NOT NULL, PRIMARY KEY(`categoryId`))",
        "CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `categoryId` TEXT, `merchantKey` TEXT, `accountId` INTEGER, `frequency` TEXT NOT NULL, `startDate` TEXT NOT NULL, `nextDueDate` TEXT NOT NULL, `lastPaidDate` TEXT, `remindDaysBefore` INTEGER NOT NULL, `active` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `savings_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `targetPaise` INTEGER NOT NULL, `targetDate` TEXT, `createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `goal_contributions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `goalId` INTEGER NOT NULL, `amountPaise` INTEGER NOT NULL, `dateMillis` INTEGER NOT NULL, `note` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_goal_contributions_goalId` ON `goal_contributions` (`goalId`)",
        "CREATE TABLE IF NOT EXISTS `investments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `institution` TEXT, `refLast4` TEXT, `startDate` TEXT NOT NULL, `maturityDate` TEXT, `ratePercent` REAL, `installmentPaise` INTEGER, `frequency` TEXT NOT NULL, `sumAssuredPaise` INTEGER, `maturityAmountPaise` INTEGER, `matchKeywords` TEXT NOT NULL, `status` TEXT NOT NULL, `note` TEXT)",
        "CREATE TABLE IF NOT EXISTS `investment_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `investmentId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `dateMillis` INTEGER NOT NULL, `transactionId` INTEGER, `note` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_investment_entries_investmentId` ON `investment_entries` (`investmentId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_investment_entries_transactionId` ON `investment_entries` (`transactionId`)",
    )
}
