package com.prash.kosh.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Room entities. They are also @Serializable so a backup is simply every table written out
 * as JSON and encrypted. Money is always whole paise (Long). Times are UTC epoch millis.
 */

@Serializable
@Entity(tableName = "accounts", indices = [Index(value = ["bank", "last4"])])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,          // BANK, CARD, WALLET, CASH
    val bank: String? = null,
    val last4: String? = null,
    val openingBalancePaise: Long = 0,
    val archived: Boolean = false,
)

@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val kind: String,          // EXPENSE, INCOME, TRANSFER, INVESTMENT
    val icon: String,
    val sortOrder: Int,
    val hidden: Boolean = false,
    /** Fixed (recurring) spending such as rent, bills, subscriptions, insurance. */
    @ColumnInfo(defaultValue = "0") val isFixed: Boolean = false,
)

/**
 * The merchant dictionary. source = SEED for built-in entries, USER once you have set or
 * changed the category yourself (a USER row is a "merchant rule" and beats everything else).
 */
@Serializable
@Entity(tableName = "merchants")
data class MerchantEntity(
    @PrimaryKey val key: String,
    val displayName: String,
    val categoryId: String?,
    val source: String,        // SEED or USER
    val aliases: String = "",  // '|' separated, used for matching
)

@Serializable
@Entity(tableName = "keyword_rules")
data class KeywordRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val categoryId: String,
    val sortOrder: Int,
)

/** Every captured message, kept raw. The unique hash makes re-delivery and re-import idempotent. */
@Serializable
@Entity(tableName = "raw_messages", indices = [Index(value = ["hash"], unique = true), Index("transactionId")])
data class RawMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val hash: String,
    val outcome: String,       // IGNORED, RECOGNISED, MERGED, ERROR
    val ignoreReason: String? = null,
    val confidence: Int? = null,
    val transactionId: Long? = null,
)

@Serializable
@Entity(
    tableName = "transactions",
    indices = [Index("dateMillis"), Index("status"), Index("reference"), Index("merchantKey"), Index("accountId")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,          // TxnType
    val direction: String,     // DEBIT / CREDIT
    val status: String,        // TxnStatus
    val amountPaise: Long,
    val dateMillis: Long,
    val accountId: Long? = null,
    val accountLast4: String? = null,
    val merchantKey: String? = null,
    val merchantName: String? = null,
    val categoryId: String? = null,
    val method: String = "OTHER",
    val reference: String? = null,
    val note: String? = null,
    val confidence: Int = 100,
    val sources: String = "MANUAL", // comma separated CaptureSource names
    /** Refund -> original expense; suspected duplicate -> the transaction it may duplicate. */
    val linkedTxnId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Set when the payment matched a recurring payment rule. */
    val recurringId: Long? = null,
    /** For transfers: the other account (ATM withdrawal -> Cash, card bill -> the card). */
    val toAccountId: Long? = null,
)

@Serializable
@Entity(tableName = "audit_log", indices = [Index("transactionId")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val at: Long,
    val action: String,        // CREATED, EDITED, APPROVED, REJECTED, MERGED, RULE_APPLIED, RESTORED
    val before: String? = null,
    val after: String? = null,
)

/* ------------------------------------------------------------------ Phase 2: budgets, recurring, goals */

/** One monthly limit per top-level expense category. */
@Serializable
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val categoryId: String,
    val monthlyLimitPaise: Long,
    val rollover: Boolean = false,
    val startMonth: String,     // yyyy-MM: first month the budget applies
)

/** A payment that repeats (rent, SIP-free bills, subscriptions, EMIs, salary). */
@Serializable
@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,           // EXPENSE, INCOME or TRANSFER
    val amountPaise: Long,
    val categoryId: String?,
    val merchantKey: String? = null,
    val accountId: Long? = null,
    val frequency: String,      // Frequency
    val startDate: String,      // yyyy-MM-dd, sets the day of month
    val nextDueDate: String,    // yyyy-MM-dd
    val lastPaidDate: String? = null,
    val remindDaysBefore: Int = 1,
    val active: Boolean = true,
)

@Serializable
@Entity(tableName = "savings_goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,           // SAVINGS, EMERGENCY, PURCHASE
    val targetPaise: Long,
    val targetDate: String? = null,
    val createdAt: Long,
    val archived: Boolean = false,
)

@Serializable
@Entity(tableName = "goal_contributions", indices = [Index("goalId")])
data class GoalContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val amountPaise: Long,      // negative when money is taken out
    val dateMillis: Long,
    val note: String? = null,
)

/* ------------------------------------------------------------------ Phase 3: fixed investments, LIC, SIP */

/**
 * A fixed investment (FD, RD, bond, PPF, EPF, NPS), an LIC/insurance policy or a SIP.
 * Only money in and out is tracked, never market growth.
 */
@Serializable
@Entity(tableName = "investments")
data class InvestmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,           // FD, RD, BOND, PPF, EPF, NPS, OTHER_FIXED, LIC, SIP
    val name: String,
    val institution: String? = null,
    val refLast4: String? = null,
    val startDate: String,      // yyyy-MM-dd
    val maturityDate: String? = null,
    val ratePercent: Double? = null,
    val installmentPaise: Long? = null,
    val frequency: String = "ONE_TIME",
    val sumAssuredPaise: Long? = null,
    val maturityAmountPaise: Long? = null,
    /** Words that identify this investment's payments in bank SMS ("ICCL", "LIC", "PPF"). */
    val matchKeywords: String = "",
    val status: String = "ACTIVE", // ACTIVE, MATURED, CLOSED
    val note: String? = null,
)

@Serializable
@Entity(tableName = "investment_entries", indices = [Index("investmentId"), Index(value = ["transactionId"], unique = true)])
data class InvestmentEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investmentId: Long,
    val kind: String,           // OPENING (before tracking), CONTRIBUTION, WITHDRAWAL, MATURITY
    val amountPaise: Long,
    val dateMillis: Long,
    val transactionId: Long? = null,
    val note: String? = null,
)

/* ------------------------------------------------------------------ v0.3: splits, summaries, documents, archive */

/** One part of a split transaction. When a transaction has splits, category totals use them instead. */
@Serializable
@Entity(tableName = "transaction_splits", indices = [Index("transactionId")])
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val categoryId: String,
    val amountPaise: Long,
    val note: String? = null,
)

/** Monthly written summary (AI or local template). */
@Serializable
@Entity(tableName = "summaries", indices = [Index(value = ["month"], unique = true)])
data class SummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val month: String,          // yyyy-MM
    val text: String,
    val source: String,         // AI or LOCAL
    val createdAt: Long,
)

/** A file attached to a policy or investment. The bytes live in app storage and in Drive. */
@Serializable
@Entity(tableName = "documents", indices = [Index("investmentId")])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investmentId: Long,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val fileKey: String,        // file name in app storage and in Drive
    val addedAt: Long,
)

/** Totals for a month whose transactions were moved to the Drive archive. */
@Serializable
@Entity(tableName = "archive_index")
data class ArchiveIndexEntity(
    @PrimaryKey val month: String, // yyyy-MM
    val income: Long,
    val expense: Long,
    val refunds: Long,
    val invested: Long,
    val transfers: Long,
    val recurring: Long,
    val quickCommerce: Long,
    val categoryTotalsJson: String,
    val transactionCount: Int,
    val archivedAt: Long,
)

/* ------------------------------------------------------------------ sync bookkeeping (not synced themselves) */

/** Maps a local row to its global id and remembers the last version applied. */
@Entity(tableName = "sync_meta", primaryKeys = ["tbl", "uid"], indices = [Index(value = ["tbl", "localKey"], unique = true)])
data class SyncMetaEntity(
    val tbl: String,
    val uid: String,
    val localKey: String,
    val version: String,
    val deleted: Boolean = false,
)

/** Local changes waiting to be uploaded; filled by database triggers. */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val tbl: String,
    val localKey: String,
    val op: String,             // U (insert/update) or D (delete)
    val at: Long,
)

/** Single-row switch: while 1, triggers do not record changes (used when applying remote data or archiving). */
@Entity(tableName = "sync_flag")
data class SyncFlagEntity(@PrimaryKey val id: Int = 1, val suppress: Int = 0)

/**
 * Expense lines for category statistics: a transaction without splits counts once under its own
 * category; a split transaction counts once per split under each split's category.
 */
@androidx.room.DatabaseView(
    viewName = "expense_lines",
    value = """SELECT t.id AS txnId, t.type AS type, t.status AS status, t.dateMillis AS dateMillis, t.categoryId AS categoryId,
        t.amountPaise AS amountPaise, t.recurringId AS recurringId, t.merchantName AS merchantName
        FROM transactions t WHERE NOT EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transactionId = t.id)
        UNION ALL
        SELECT t.id, t.type, t.status, t.dateMillis, s.categoryId, s.amountPaise, t.recurringId, t.merchantName
        FROM transaction_splits s JOIN transactions t ON t.id = s.transactionId""",
)
data class ExpenseLine(
    val txnId: Long, val type: String, val status: String, val dateMillis: Long, val categoryId: String?,
    val amountPaise: Long, val recurringId: Long?, val merchantName: String?,
)

/* ------------------------------------------------------------------ query results */

data class TypeTotal(val type: String, val total: Long)
data class CategoryTotal(val topCategory: String, val total: Long)
data class MonthTypeTotal(val month: String, val type: String, val total: Long)
data class AccountFlow(val accountId: Long, val inflow: Long, val outflow: Long)
data class CategorySpend(val categoryId: String?, val total: Long)
data class InvestmentTotal(val investmentId: Long, val contributed: Long, val withdrawn: Long, val lastContribution: Long?, val count: Int)
data class AccountBalance(val accountId: Long, val net: Long, val inflow: Long, val outflow: Long)
