package com.prash.kosh.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Statuses that count towards totals. Needs-review, suspected duplicates and rejected do not. */
/** Only approved transactions count. Check (80-94%) and Review items wait in the review queue. */
const val COUNTED = "('POSTED')"

@Dao
interface TransactionDao {
    @Insert suspend fun insert(t: TransactionEntity): Long
    @Update suspend fun update(t: TransactionEntity)
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE id = :id") fun observe(id: Long): Flow<TransactionEntity?>

    @Query("""SELECT * FROM transactions WHERE dateMillis BETWEEN :from AND :to AND status != 'REJECTED'
        AND (:type IS NULL OR type = :type)
        AND (:category IS NULL OR categoryId = :category OR categoryId LIKE :category || '.%'
             OR id IN (SELECT transactionId FROM transaction_splits WHERE categoryId = :category OR categoryId LIKE :category || '.%'))
        AND (:accountId IS NULL OR accountId = :accountId OR toAccountId = :accountId)
        AND (:method IS NULL OR method = :method)
        AND (:minPaise IS NULL OR amountPaise >= :minPaise) AND (:maxPaise IS NULL OR amountPaise <= :maxPaise)
        AND (:q = '' OR merchantName LIKE '%' || :q || '%' OR note LIKE '%' || :q || '%' OR reference LIKE '%' || :q || '%'
             OR CAST(amountPaise / 100 AS TEXT) LIKE :q || '%')
        ORDER BY dateMillis DESC, id DESC""")
    fun observeFiltered(from: Long, to: Long, type: String?, q: String, category: String?, accountId: Long?, method: String?,
                        minPaise: Long?, maxPaise: Long?): Flow<List<TransactionEntity>>

    fun observeRange(from: Long, to: Long, type: String?, q: String): Flow<List<TransactionEntity>> =
        observeFiltered(from, to, type, q, null, null, null, null, null)

    @Query("""SELECT * FROM transactions WHERE dateMillis BETWEEN :from AND :to AND status IN $COUNTED ORDER BY dateMillis""")
    suspend fun countedBetween(from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE dateMillis < :before") suspend fun olderThan(before: Long): List<TransactionEntity>
    @Query("DELETE FROM transactions WHERE id IN (:ids)") suspend fun deleteIds(ids: List<Long>)
    @Query("DELETE FROM transactions WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM transactions WHERE status IN ('NEEDS_REVIEW','PENDING_VERIFICATION','SUSPECTED_DUPLICATE') ORDER BY dateMillis DESC")
    fun observeReviewQueue(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE status IN ('NEEDS_REVIEW','PENDING_VERIFICATION','SUSPECTED_DUPLICATE')")
    fun observeReviewCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE status != 'REJECTED' ORDER BY dateMillis DESC, id DESC LIMIT :n")
    fun observeRecent(n: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE amountPaise = :amount AND dateMillis BETWEEN :from AND :to AND status != 'REJECTED'")
    suspend fun nearby(amount: Long, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE reference = :ref AND status != 'REJECTED' LIMIT 5")
    suspend fun byReference(ref: String): List<TransactionEntity>

    @Query("""SELECT * FROM transactions WHERE type = 'EXPENSE' AND merchantKey = :merchantKey AND amountPaise >= :amount
        AND dateMillis BETWEEN :from AND :to AND status IN $COUNTED ORDER BY dateMillis DESC LIMIT 1""")
    suspend fun refundCandidate(merchantKey: String, amount: Long, from: Long, to: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE merchantKey = :merchantKey AND categoryId IN (:categoryIds) AND status != 'REJECTED'")
    suspend fun byMerchantInCategories(merchantKey: String, categoryIds: List<String>): List<TransactionEntity>

    @Query("SELECT type, SUM(amountPaise) AS total FROM transactions WHERE dateMillis BETWEEN :from AND :to AND status IN $COUNTED GROUP BY type")
    fun observeTypeTotals(from: Long, to: Long): Flow<List<TypeTotal>>

    @Query("""SELECT CASE WHEN instr(categoryId, '.') > 0 THEN substr(categoryId, 1, instr(categoryId, '.') - 1) ELSE IFNULL(categoryId, 'other') END AS topCategory,
        SUM(amountPaise) AS total FROM expense_lines
        WHERE type = 'EXPENSE' AND dateMillis BETWEEN :from AND :to AND status IN $COUNTED
        GROUP BY topCategory ORDER BY total DESC""")
    fun observeExpenseByCategory(from: Long, to: Long): Flow<List<CategoryTotal>>

    /** Month buckets in IST (+05:30 = 19800 s). */
    @Query("""SELECT strftime('%Y-%m', (dateMillis / 1000) + 19800, 'unixepoch') AS month, type, SUM(amountPaise) AS total
        FROM transactions WHERE dateMillis >= :from AND status IN $COUNTED GROUP BY month, type""")
    fun observeMonthlyTotals(from: Long): Flow<List<MonthTypeTotal>>

    /** Money in and out per account, including the receiving side of transfers (toAccountId). */
    @Query("""SELECT accountId, SUM(inflow) AS inflow, SUM(outflow) AS outflow FROM (
        SELECT accountId, CASE WHEN direction = 'CREDIT' THEN amountPaise ELSE 0 END AS inflow,
               CASE WHEN direction = 'DEBIT' THEN amountPaise ELSE 0 END AS outflow
        FROM transactions WHERE accountId IS NOT NULL AND status IN $COUNTED
        UNION ALL
        SELECT toAccountId, CASE WHEN direction = 'DEBIT' THEN amountPaise ELSE 0 END,
               CASE WHEN direction = 'CREDIT' THEN amountPaise ELSE 0 END
        FROM transactions WHERE toAccountId IS NOT NULL AND status IN $COUNTED
        ) GROUP BY accountId""")
    fun observeAccountFlows(): Flow<List<AccountFlow>>

    /** Every transaction touching an account (either side), newest first. */
    @Query("""SELECT * FROM transactions WHERE (accountId = :accountId OR toAccountId = :accountId) AND status != 'REJECTED'
        ORDER BY dateMillis DESC, id DESC LIMIT :limit""")
    fun observeForAccount(accountId: Long, limit: Int): Flow<List<TransactionEntity>>

    @Query("""SELECT * FROM transactions WHERE (accountId = :accountId OR toAccountId = :accountId) AND status IN $COUNTED
        AND dateMillis BETWEEN :from AND :to ORDER BY dateMillis""")
    suspend fun forAccountBetween(accountId: Long, from: Long, to: Long): List<TransactionEntity>

    // ---------------------------------------------------------------- Phase 2/3 queries

    /** Expense spend in a top-level category and its subcategories. */
    @Query("""SELECT IFNULL(SUM(amountPaise), 0) FROM expense_lines WHERE type = 'EXPENSE' AND status IN $COUNTED
        AND dateMillis BETWEEN :from AND :to AND (categoryId = :top OR categoryId LIKE :top || '.%')""")
    suspend fun spentInTop(top: String, from: Long, to: Long): Long

    @Query("""SELECT CASE WHEN instr(categoryId, '.') > 0 THEN substr(categoryId, 1, instr(categoryId, '.') - 1) ELSE IFNULL(categoryId, 'other') END AS topCategory,
        SUM(amountPaise) AS total FROM expense_lines
        WHERE type = 'EXPENSE' AND dateMillis BETWEEN :from AND :to AND status IN $COUNTED GROUP BY topCategory""")
    suspend fun expenseByTop(from: Long, to: Long): List<CategoryTotal>

    /** Recurring = matched to a recurring rule, or in a category (or parent) marked fixed. Month buckets in IST. */
    @Query("""SELECT strftime('%Y-%m', (t.dateMillis / 1000) + 19800, 'unixepoch') AS month, 'RECURRING' AS type, SUM(t.amountPaise) AS total
        FROM expense_lines t LEFT JOIN categories c ON c.id = t.categoryId LEFT JOIN categories p ON p.id = c.parentId
        WHERE t.type = 'EXPENSE' AND t.status IN $COUNTED AND t.dateMillis BETWEEN :from AND :to
        AND (t.recurringId IS NOT NULL OR c.isFixed = 1 OR p.isFixed = 1) GROUP BY month""")
    fun observeMonthlyRecurring(from: Long, to: Long): Flow<List<MonthTypeTotal>>

    @Query("""SELECT strftime('%Y-%m', (dateMillis / 1000) + 19800, 'unixepoch') AS month, type, SUM(amountPaise) AS total
        FROM transactions WHERE dateMillis BETWEEN :from AND :to AND status IN $COUNTED GROUP BY month, type""")
    fun observeMonthlyTotalsBetween(from: Long, to: Long): Flow<List<MonthTypeTotal>>

    @Query("""SELECT strftime('%Y-%m', (dateMillis / 1000) + 19800, 'unixepoch') AS month, 'BURN' AS type, SUM(amountPaise) AS total
        FROM expense_lines WHERE type = 'EXPENSE' AND status IN $COUNTED AND dateMillis BETWEEN :from AND :to
        AND categoryId IN (:categoryIds) GROUP BY month""")
    fun observeMonthlyInCategories(from: Long, to: Long, categoryIds: List<String>): Flow<List<MonthTypeTotal>>

    @Query("""SELECT merchantName AS categoryId, SUM(amountPaise) AS total FROM expense_lines
        WHERE type = 'EXPENSE' AND status IN $COUNTED AND dateMillis BETWEEN :from AND :to AND categoryId IN (:categoryIds)
        GROUP BY merchantName ORDER BY total DESC LIMIT 5""")
    fun observeTopMerchantsIn(from: Long, to: Long, categoryIds: List<String>): Flow<List<CategorySpend>>

    /** Investment payments from the bank that are not yet linked to an investment. */
    @Query("""SELECT * FROM transactions WHERE type = 'INVESTMENT' AND direction = 'DEBIT' AND status IN $COUNTED
        AND id NOT IN (SELECT transactionId FROM investment_entries WHERE transactionId IS NOT NULL) ORDER BY dateMillis DESC""")
    fun observeUnlinkedInvestments(): Flow<List<TransactionEntity>>

    @Query("""SELECT * FROM transactions WHERE recurringId IS NULL AND type = :type AND status IN $COUNTED
        AND dateMillis BETWEEN :from AND :to ORDER BY dateMillis DESC""")
    suspend fun unmatchedForRecurring(type: String, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions") suspend fun all(): List<TransactionEntity>
    @Insert suspend fun insertAll(items: List<TransactionEntity>)
    @Query("DELETE FROM transactions") suspend fun clear()
}

@Dao
interface RawMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: RawMessageEntity): Long
    @Update suspend fun update(m: RawMessageEntity)
    @Query("SELECT EXISTS(SELECT 1 FROM raw_messages WHERE hash = :hash)") suspend fun exists(hash: String): Boolean
    @Query("SELECT * FROM raw_messages WHERE transactionId = :txnId ORDER BY receivedAt") fun observeForTxn(txnId: Long): Flow<List<RawMessageEntity>>
    @Query("SELECT * FROM raw_messages WHERE outcome = 'IGNORED' ORDER BY receivedAt DESC LIMIT 300") fun observeIgnored(): Flow<List<RawMessageEntity>>
    @Query("SELECT * FROM raw_messages WHERE id = :id") suspend fun get(id: Long): RawMessageEntity?
    @Query("SELECT * FROM raw_messages WHERE outcome = 'IGNORED' AND body != '' ORDER BY receivedAt") suspend fun ignoredWithBody(): List<RawMessageEntity>
    @Query("SELECT * FROM raw_messages WHERE transactionId = :txnId") suspend fun forTxn(txnId: Long): List<RawMessageEntity>
    /** Retention: drop message text older than the cutoff but keep the hash, so re-imports are still skipped. */
    @Query("UPDATE raw_messages SET body = '' WHERE receivedAt < :cutoff AND body != ''") suspend fun purgeBodiesBefore(cutoff: Long): Int
    @Query("SELECT * FROM raw_messages WHERE transactionId IN (:ids)") suspend fun forTxns(ids: List<Long>): List<RawMessageEntity>
    @Query("DELETE FROM raw_messages WHERE transactionId IN (:ids)") suspend fun deleteForTxns(ids: List<Long>)
    @Query("SELECT * FROM raw_messages") suspend fun all(): List<RawMessageEntity>
    @Insert suspend fun insertAll(items: List<RawMessageEntity>)
    @Query("DELETE FROM raw_messages") suspend fun clear()
}

@Dao
interface AccountDao {
    @Insert suspend fun insert(a: AccountEntity): Long
    @Update suspend fun update(a: AccountEntity)
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY name") fun observeAll(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun get(id: Long): AccountEntity?
    @Query("SELECT * FROM accounts WHERE last4 = :last4 AND (bank = :bank OR :bank IS NULL) LIMIT 1") suspend fun find(bank: String?, last4: String): AccountEntity?
    @Query("SELECT * FROM accounts") suspend fun all(): List<AccountEntity>
    @Insert suspend fun insertAll(items: List<AccountEntity>)
    @Query("DELETE FROM accounts") suspend fun clear()
}

@Dao
interface CategoryDao {
    @Upsert suspend fun upsert(c: CategoryEntity)
    @Query("SELECT * FROM categories ORDER BY sortOrder, name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories") suspend fun all(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun get(id: String): CategoryEntity?
    @Query("SELECT COUNT(*) FROM categories") suspend fun count(): Int
    @Insert suspend fun insertAll(items: List<CategoryEntity>)
    @Query("DELETE FROM categories") suspend fun clear()
}

@Dao
interface MerchantDao {
    @Upsert suspend fun upsert(m: MerchantEntity)
    @Query("SELECT * FROM merchants WHERE `key` = :key") suspend fun get(key: String): MerchantEntity?
    @Query("SELECT * FROM merchants ORDER BY source DESC, displayName") fun observeAll(): Flow<List<MerchantEntity>>
    @Query("SELECT * FROM merchants") suspend fun all(): List<MerchantEntity>
    @Query("DELETE FROM merchants WHERE `key` = :key AND source = 'USER' AND aliases = ''") suspend fun deleteUserOnly(key: String)
    @Insert suspend fun insertAll(items: List<MerchantEntity>)
    @Query("DELETE FROM merchants") suspend fun clear()
}

@Dao
interface KeywordRuleDao {
    @Upsert suspend fun upsert(r: KeywordRuleEntity)
    @Query("DELETE FROM keyword_rules WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM keyword_rules ORDER BY sortOrder") fun observeAll(): Flow<List<KeywordRuleEntity>>
    @Query("SELECT * FROM keyword_rules ORDER BY sortOrder") suspend fun all(): List<KeywordRuleEntity>
    @Insert suspend fun insertAll(items: List<KeywordRuleEntity>)
    @Query("DELETE FROM keyword_rules") suspend fun clear()
}

@Dao
interface TaughtPatternDao {
    @Insert suspend fun insert(p: TaughtPatternEntity): Long
    @Query("DELETE FROM taught_patterns WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM taught_patterns ORDER BY createdAt DESC") fun observeAll(): Flow<List<TaughtPatternEntity>>
    @Query("SELECT * FROM taught_patterns") suspend fun all(): List<TaughtPatternEntity>
    @Query("UPDATE taught_patterns SET matchCount = matchCount + 1, lastMatchedAt = :at WHERE id = :id") suspend fun noteMatch(id: Long, at: Long)
    @Insert suspend fun insertAll(items: List<TaughtPatternEntity>)
    @Query("DELETE FROM taught_patterns") suspend fun clear()
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(a: AuditLogEntity)
    @Query("SELECT * FROM audit_log WHERE transactionId = :txnId ORDER BY at DESC") fun observeForTxn(txnId: Long): Flow<List<AuditLogEntity>>
    @Query("SELECT * FROM audit_log") suspend fun all(): List<AuditLogEntity>
    @Query("SELECT * FROM audit_log WHERE transactionId IN (:ids)") suspend fun forTxns(ids: List<Long>): List<AuditLogEntity>
    @Query("DELETE FROM audit_log WHERE transactionId IN (:ids)") suspend fun deleteForTxns(ids: List<Long>)
    @Insert suspend fun insertAll(items: List<AuditLogEntity>)
    @Query("DELETE FROM audit_log") suspend fun clear()
}

@Dao
interface BudgetDao {
    @Upsert suspend fun upsert(b: BudgetEntity)
    @Query("DELETE FROM budgets WHERE categoryId = :categoryId") suspend fun delete(categoryId: String)
    @Query("SELECT * FROM budgets") fun observeAll(): Flow<List<BudgetEntity>>
    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId") suspend fun get(categoryId: String): BudgetEntity?
    @Query("SELECT * FROM budgets") suspend fun all(): List<BudgetEntity>
    @Insert suspend fun insertAll(items: List<BudgetEntity>)
    @Query("DELETE FROM budgets") suspend fun clear()
}

@Dao
interface RecurringDao {
    @Upsert suspend fun upsert(r: RecurringRuleEntity): Long
    @Query("DELETE FROM recurring_rules WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM recurring_rules WHERE id = :id") suspend fun get(id: Long): RecurringRuleEntity?
    @Query("SELECT * FROM recurring_rules ORDER BY active DESC, nextDueDate") fun observeAll(): Flow<List<RecurringRuleEntity>>
    @Query("SELECT * FROM recurring_rules WHERE active = 1") suspend fun active(): List<RecurringRuleEntity>
    @Query("SELECT * FROM recurring_rules") suspend fun all(): List<RecurringRuleEntity>
    @Insert suspend fun insertAll(items: List<RecurringRuleEntity>)
    @Query("DELETE FROM recurring_rules") suspend fun clear()
}

@Dao
interface GoalDao {
    @Upsert suspend fun upsert(g: GoalEntity): Long
    @Insert suspend fun addContribution(c: GoalContributionEntity): Long
    @Query("DELETE FROM goal_contributions WHERE id = :id") suspend fun deleteContribution(id: Long)
    @Query("SELECT * FROM savings_goals WHERE archived = 0 ORDER BY id") fun observeActive(): Flow<List<GoalEntity>>
    @Query("SELECT goalId AS investmentId, SUM(CASE WHEN amountPaise > 0 THEN amountPaise ELSE 0 END) AS contributed, SUM(CASE WHEN amountPaise < 0 THEN -amountPaise ELSE 0 END) AS withdrawn, MAX(dateMillis) AS lastContribution, COUNT(*) AS count FROM goal_contributions GROUP BY goalId")
    fun observeTotals(): Flow<List<InvestmentTotal>>
    @Query("SELECT * FROM goal_contributions WHERE goalId = :goalId ORDER BY dateMillis DESC") fun observeContributions(goalId: Long): Flow<List<GoalContributionEntity>>
    @Query("SELECT * FROM savings_goals") suspend fun all(): List<GoalEntity>
    @Query("SELECT * FROM goal_contributions") suspend fun allContributions(): List<GoalContributionEntity>
    @Insert suspend fun insertAll(items: List<GoalEntity>)
    @Insert suspend fun insertAllContributions(items: List<GoalContributionEntity>)
    @Query("DELETE FROM savings_goals") suspend fun clear()
    @Query("DELETE FROM goal_contributions") suspend fun clearContributions()
}

@Dao
interface InvestmentDao {
    @Upsert suspend fun upsert(i: InvestmentEntity): Long
    @Query("SELECT * FROM investments WHERE id = :id") suspend fun get(id: Long): InvestmentEntity?
    @Query("SELECT * FROM investments WHERE id = :id") fun observe(id: Long): Flow<InvestmentEntity?>
    @Query("SELECT * FROM investments ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END, kind, name") fun observeAll(): Flow<List<InvestmentEntity>>
    @Query("SELECT * FROM investments WHERE status = 'ACTIVE'") suspend fun active(): List<InvestmentEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addEntry(e: InvestmentEntryEntity): Long
    @Query("DELETE FROM investment_entries WHERE id = :id") suspend fun deleteEntry(id: Long)
    @Query("SELECT * FROM investment_entries WHERE investmentId = :id ORDER BY dateMillis DESC") fun observeEntries(id: Long): Flow<List<InvestmentEntryEntity>>
    @Query("SELECT * FROM investment_entries WHERE investmentId = :id ORDER BY dateMillis DESC") suspend fun entries(id: Long): List<InvestmentEntryEntity>
    @Query("""SELECT investmentId, SUM(CASE WHEN kind IN ('CONTRIBUTION', 'OPENING') THEN amountPaise ELSE 0 END) AS contributed,
        SUM(CASE WHEN kind IN ('WITHDRAWAL', 'MATURITY') THEN amountPaise ELSE 0 END) AS withdrawn,
        MAX(CASE WHEN kind = 'CONTRIBUTION' THEN dateMillis END) AS lastContribution, COUNT(*) AS count
        FROM investment_entries GROUP BY investmentId""")
    fun observeTotals(): Flow<List<InvestmentTotal>>
    @Query("SELECT * FROM investments") suspend fun all(): List<InvestmentEntity>
    @Query("SELECT * FROM investment_entries") suspend fun allEntries(): List<InvestmentEntryEntity>
    @Insert suspend fun insertAll(items: List<InvestmentEntity>)
    @Insert suspend fun insertAllEntries(items: List<InvestmentEntryEntity>)
    @Query("DELETE FROM investments") suspend fun clear()
    @Query("DELETE FROM investment_entries") suspend fun clearEntries()
}

@Dao
interface ExtrasDao {
    // splits
    @Query("SELECT * FROM transaction_splits WHERE transactionId = :txnId ORDER BY id") fun observeSplits(txnId: Long): Flow<List<SplitEntity>>
    @Query("SELECT * FROM transaction_splits WHERE transactionId = :txnId ORDER BY id") suspend fun splits(txnId: Long): List<SplitEntity>
    @Insert suspend fun insertSplits(items: List<SplitEntity>)
    @Query("DELETE FROM transaction_splits WHERE transactionId = :txnId") suspend fun clearSplits(txnId: Long)
    @Query("SELECT * FROM transaction_splits") suspend fun allSplits(): List<SplitEntity>
    @Query("SELECT * FROM transaction_splits WHERE transactionId IN (:ids)") suspend fun splitsFor(ids: List<Long>): List<SplitEntity>
    @Query("DELETE FROM transaction_splits WHERE transactionId IN (:ids)") suspend fun deleteSplitsFor(ids: List<Long>)
    // summaries
    @Upsert suspend fun upsertSummary(s: SummaryEntity)
    @Query("SELECT * FROM summaries WHERE month = :month") suspend fun summary(month: String): SummaryEntity?
    @Query("SELECT * FROM summaries ORDER BY month DESC") fun observeSummaries(): Flow<List<SummaryEntity>>
    @Query("SELECT * FROM summaries") suspend fun allSummaries(): List<SummaryEntity>
    // documents
    @Insert suspend fun insertDocument(d: DocumentEntity): Long
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDocument(id: Long)
    @Query("SELECT * FROM documents WHERE investmentId = :investmentId ORDER BY addedAt DESC") fun observeDocuments(investmentId: Long): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents") suspend fun allDocuments(): List<DocumentEntity>
    // archive index
    @Upsert suspend fun upsertArchive(a: ArchiveIndexEntity)
    @Query("SELECT * FROM archive_index ORDER BY month DESC") fun observeArchive(): Flow<List<ArchiveIndexEntity>>
    @Query("SELECT * FROM archive_index") suspend fun allArchive(): List<ArchiveIndexEntity>
    @Query("SELECT * FROM archive_index WHERE month = :month") suspend fun archive(month: String): ArchiveIndexEntity?
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_outbox ORDER BY seq LIMIT :limit") suspend fun outbox(limit: Int): List<SyncOutboxEntity>
    @Query("DELETE FROM sync_outbox WHERE seq <= :maxSeq") suspend fun clearOutbox(maxSeq: Long)
    @Query("SELECT COUNT(*) FROM sync_outbox") suspend fun outboxCount(): Int
    @Query("SELECT COUNT(*) FROM sync_outbox") fun observeOutboxCount(): Flow<Int>
    @Query("SELECT * FROM sync_meta WHERE tbl = :tbl AND localKey = :localKey") suspend fun metaByLocal(tbl: String, localKey: String): SyncMetaEntity?
    @Query("SELECT * FROM sync_meta WHERE tbl = :tbl AND uid = :uid") suspend fun metaByUid(tbl: String, uid: String): SyncMetaEntity?
    @Upsert suspend fun upsertMeta(m: SyncMetaEntity)
    @Query("DELETE FROM sync_meta WHERE tbl = :tbl AND localKey = :localKey") suspend fun deleteMetaByLocal(tbl: String, localKey: String)
    @Query("UPDATE sync_flag SET suppress = :value WHERE id = 1") suspend fun setSuppress(value: Int)
    @Query("INSERT OR IGNORE INTO sync_flag (id, suppress) VALUES (1, 0)") suspend fun ensureFlag()
    @Query("DELETE FROM sync_outbox") suspend fun clearAllOutbox()
    @Query("DELETE FROM sync_meta") suspend fun clearAllMeta()
}
