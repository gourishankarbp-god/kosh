package com.prash.kosh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prash.kosh.data.AccountEntity
import com.prash.kosh.data.CategoryEntity
import com.prash.kosh.data.Repository
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.engine.Money
import com.prash.kosh.ui.components.IST
import com.prash.kosh.ui.components.endMillis
import com.prash.kosh.ui.components.startMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private fun <T> kotlinx.coroutines.flow.Flow<T>.state(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

/** Shared across tabs: the selected month and reference data every screen needs. */
class MainViewModel(val repo: Repository) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now(IST))
    val categories: StateFlow<List<CategoryEntity>> = repo.db.categories().observeAll().state(this, emptyList())
    val categoriesById: StateFlow<Map<String, CategoryEntity>> = categories.map { l -> l.associateBy { it.id } }.state(this, emptyMap())
    val accounts: StateFlow<List<AccountEntity>> = repo.db.accounts().observeAll().state(this, emptyList())
    val reviewCount: StateFlow<Int> = repo.db.transactions().observeReviewCount().state(this, 0)
}

data class DashboardState(
    val income: Long = 0,
    val expenses: Long = 0,          // net of refunds
    val refunds: Long = 0,
    val invested: Long = 0,
    val transfers: Long = 0,
    val savings: Long = 0,
    val savingsRate: Int? = null,
    val byCategory: List<Pair<String, Long>> = emptyList(),
    val trend: List<Triple<String, Long, Long>> = emptyList(),
    val recent: List<TransactionEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(repo: Repository, month: StateFlow<YearMonth>, categoriesById: StateFlow<Map<String, CategoryEntity>>) : ViewModel() {
    private val dao = repo.db.transactions()

    val state: StateFlow<DashboardState> = month.flatMapLatest { m ->
        val from = m.startMillis(); val to = m.endMillis()
        val trendFrom = m.minusMonths(5).startMillis()
        combine(
            dao.observeTypeTotals(from, to),
            dao.observeExpenseByCategory(from, to),
            dao.observeMonthlyTotals(trendFrom),
            dao.observeRecent(6),
            categoriesById,
        ) { totals, cats, monthly, recent, byId ->
            val t = totals.associate { it.type to it.total }
            val income = t["INCOME"] ?: 0
            val refunds = t["REFUND"] ?: 0
            val expenses = ((t["EXPENSE"] ?: 0) - refunds).coerceAtLeast(0)
            val top = cats.take(6).map { (byId[it.topCategory]?.name ?: it.topCategory) to it.total }
            val rest = cats.drop(6).sumOf { it.total }
            val labelFmt = DateTimeFormatter.ofPattern("MMM")
            val trend = (5 downTo 0).map { back ->
                val ym = m.minusMonths(back.toLong())
                val key = ym.toString()
                val inc = monthly.filter { it.month == key && it.type == "INCOME" }.sumOf { it.total }
                val exp = monthly.filter { it.month == key && it.type == "EXPENSE" }.sumOf { it.total } -
                    monthly.filter { it.month == key && it.type == "REFUND" }.sumOf { it.total }
                Triple(ym.format(labelFmt), inc, exp.coerceAtLeast(0))
            }
            DashboardState(
                income = income, expenses = expenses, refunds = refunds,
                invested = t["INVESTMENT"] ?: 0, transfers = t["TRANSFER"] ?: 0,
                savings = income - expenses, savingsRate = Money.savingsRatePercent(income, expenses),
                byCategory = if (rest > 0) top + ("Everything else" to rest) else top,
                trend = trend, recent = recent,
            )
        }
    }.state(this, DashboardState())
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(repo: Repository, month: StateFlow<YearMonth>) : ViewModel() {
    val query = MutableStateFlow("")
    val type = MutableStateFlow<String?>(null)
    val allTime = MutableStateFlow(false)
    val filters = MutableStateFlow(TxnFilters())

    val items: StateFlow<List<TransactionEntity>> = combine(month, query, type, allTime, filters) { m, q, t, all, f -> Args(m, q, t, all, f) }
        .flatMapLatest { a ->
            val f = a.f
            val from = f.fromMillis ?: if (a.all) 0L else a.m.startMillis()
            val to = f.toMillis ?: if (a.all) Long.MAX_VALUE else a.m.endMillis()
            repo.db.transactions().observeFiltered(from, to, a.t, a.q.trim(), f.category, f.accountId, f.method, f.minPaise, f.maxPaise)
        }.state(this, emptyList())

    private data class Args(val m: YearMonth, val q: String, val t: String?, val all: Boolean, val f: TxnFilters)
}

/** Extra filters on the Activity tab. Null means "any". */
data class TxnFilters(
    val category: String? = null, val accountId: Long? = null, val method: String? = null,
    val minPaise: Long? = null, val maxPaise: Long? = null, val fromMillis: Long? = null, val toMillis: Long? = null,
) { val active: Int get() = listOf(category, accountId, method, minPaise, maxPaise, fromMillis).count { it != null } }
