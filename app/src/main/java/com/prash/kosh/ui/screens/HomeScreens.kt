package com.prash.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.prash.kosh.ui.components.endMillis
import com.prash.kosh.ui.components.startMillis
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.engine.Money
import com.prash.kosh.engine.TxnStatus
import com.prash.kosh.ui.DashboardViewModel
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.TransactionsViewModel
import com.prash.kosh.ui.components.DonutChart
import com.prash.kosh.ui.components.EmptyState
import com.prash.kosh.ui.components.MonthSwitcher
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.TxnRow
import com.prash.kosh.ui.components.formatDay
import com.prash.kosh.ui.components.formatDayTime
import com.prash.kosh.ui.components.MonthlyBars
import com.prash.kosh.ui.components.typeLabel
import com.prash.kosh.ui.theme.ChartPalette
import com.prash.kosh.ui.theme.Expense
import com.prash.kosh.ui.theme.Income
import com.prash.kosh.ui.theme.Invest
import com.prash.kosh.ui.theme.forTheme
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(main: MainViewModel, vm: DashboardViewModel, onOpenTxn: (Long) -> Unit, onOpenReview: () -> Unit, onOpen: (String) -> Unit = {}) {
    val s by vm.state.collectAsState()
    val month by main.month.collectAsState()
    val cats by main.categoriesById.collectAsState()
    val pending by main.reviewCount.collectAsState()
    val app = com.prash.kosh.KoshApp.get(androidx.compose.ui.platform.LocalContext.current)
    val name by app.userName
    var now by remember { mutableStateOf(java.time.LocalDateTime.now(com.prash.kosh.ui.components.IST)) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); now = java.time.LocalDateTime.now(com.prash.kosh.ui.components.IST) } }
    val thisMonth = java.time.YearMonth.now(com.prash.kosh.ui.components.IST)
    val budgets = rememberBudgetRows(main, thisMonth)
    val due = rememberUpcoming(main, 7)
    val dao = main.repo.db.transactions()
    val recurring by remember(thisMonth) { dao.observeMonthlyRecurring(thisMonth.startMillis(), thisMonth.endMillis()) }.collectAsState(initial = emptyList())
    val monthTotals by remember(thisMonth) { dao.observeTypeTotals(thisMonth.startMillis(), thisMonth.endMillis()) }.collectAsState(initial = emptyList())
    val burnCats = if (app.settings.burnIncludesFoodDelivery) com.prash.kosh.engine.SeedData.QUICK_COMMERCE + com.prash.kosh.engine.SeedData.FOOD_DELIVERY else com.prash.kosh.engine.SeedData.QUICK_COMMERCE
    val burn by remember(thisMonth) { dao.observeMonthlyInCategories(thisMonth.startMillis(), thisMonth.endMillis(), burnCats) }.collectAsState(initial = emptyList())

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                Text(com.prash.kosh.engine.Metrics.greeting(now.toLocalTime()) + if (name.isNotBlank()) ", $name" else "",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Overview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                MonthSwitcher(month) { main.month.value = it }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Income", Money.format(s.income, showPaise = false), Income.forTheme(), Modifier.weight(1f))
                Stat("Expenses", Money.format(s.expenses, showPaise = false), Expense.forTheme(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("Saved", Money.format(s.savings, showPaise = false), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f),
                    sub = s.savingsRate?.let { "$it% savings rate" } ?: "No income yet")
                Stat("Invested", Money.format(s.invested, showPaise = false), Invest.forTheme(), Modifier.weight(1f), sub = "Not counted as expense")
            }
        }
        if (pending > 0) item {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            Card(
                Modifier.fillMaxWidth().clickable(onClick = onOpenReview),
                colors = CardDefaults.cardColors(containerColor = if (dark) Color(0xFF4D3A00) else Color(0xFFFFF4D6)),
            ) {
                Text("$pending transaction${if (pending == 1) "" else "s"} waiting for your review",
                    Modifier.padding(16.dp), color = if (dark) Color(0xFFFFE08A) else Color(0xFF7A5300), fontWeight = FontWeight.Medium)
            }
        }
        item {
            val accounts by main.accounts.collectAsState()
            val flows by main.repo.db.transactions().observeAccountFlows().collectAsState(initial = emptyList())
            val m = moneySummary(accounts, flows)
            SectionCard(Modifier.clickable { onOpen("accounts") }) {
                Row {
                    com.prash.kosh.ui.components.Figure("In banks", if (m.bankTotal < 0) "Set up" else Money.format(m.bankTotal, showPaise = false), Modifier.weight(1f),
                        sub = if (m.bankTotal < 0) "Add opening balances" else null)
                    com.prash.kosh.ui.components.Figure("Cash", Money.format(m.cash, showPaise = false), Modifier.weight(1f).clickable { onOpen("cash") })
                    com.prash.kosh.ui.components.Figure("Cards owed", Money.format(m.cardOutstanding, showPaise = false), Modifier.weight(1f),
                        color = if (m.cardOutstanding > 0) Expense.forTheme() else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        item {
            SectionCard(title = "This month at a glance") {
                val today = java.time.LocalDate.now(com.prash.kosh.ui.components.IST)
                val exp = ((monthTotals.firstOrNull { it.type == "EXPENSE" }?.total ?: 0) - (monthTotals.firstOrNull { it.type == "REFUND" }?.total ?: 0)).coerceAtLeast(0)
                val ratio = com.prash.kosh.engine.Metrics.recurringExpenseRatio(recurring.sumOf { it.total }, exp)
                val rate = com.prash.kosh.engine.Metrics.burnRate(burn.sumOf { it.total }, thisMonth.atDay(1), thisMonth.atEndOfMonth(), today)
                GlanceRow("Recurring expense ratio", ratio?.let { "$it%" } ?: "—") { onOpen("insights") }
                GlanceRow("Quick-commerce burn", "${Money.format(rate.perDayPaise, showPaise = false)}/day") { onOpen("insights") }
                if (budgets.isNotEmpty()) {
                    val used = com.prash.kosh.engine.Metrics.percent(budgets.sumOf { it.spentPaise }, budgets.sumOf { it.availablePaise }) ?: 0
                    GlanceRow("Budget used", "$used%" + budgets.count { it.percent >= 100 }.let { if (it > 0) " · $it over" else "" }) { onOpen("budgets") }
                } else GlanceRow("Budgets", "Not set") { onOpen("budgets") }
                due.firstOrNull()?.let { d -> GlanceRow("Next payment", "${d.name} · ${dueLabel(d.dueDate, today)}") { onOpen("upcoming") } }
                    ?: GlanceRow("Next payment", "Nothing due this week") { onOpen("upcoming") }
            }
        }
        item {
            SectionCard(title = "Where the money went") {
                if (s.byCategory.isEmpty()) EmptyState("No expenses this month")
                else DonutChart(s.byCategory, ChartPalette, Money.compact(s.expenses))
                if (s.refunds > 0) Text("Shown net of ${Money.format(s.refunds)} refunds", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item { SectionCard(title = "Last 6 months") { MonthlyBars(s.trend) } }
        item {
            SectionCard(title = "Recent") {
                if (s.recent.isEmpty()) EmptyState("Nothing yet. Turn on SMS capture in More › Capture & import, or add one with +.")
                s.recent.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider()
                    TxnRow(t, cats) { onOpenTxn(t.id) }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier, sub: String? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (sub != null) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TransactionsScreen(main: MainViewModel, vm: TransactionsViewModel, onOpenTxn: (Long) -> Unit) {
    val items by vm.items.collectAsState()
    val month by main.month.collectAsState()
    val q by vm.query.collectAsState()
    val type by vm.type.collectAsState()
    val allTime by vm.allTime.collectAsState()
    val cats by main.categoriesById.collectAsState()
    val filters by vm.filters.collectAsState()
    var showFilters by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!allTime) MonthSwitcher(month) { main.month.value = it }
            }
            OutlinedTextField(
                value = q, onValueChange = { vm.query.value = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search merchant, note, reference or amount") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item { FilterChip(selected = filters.active > 0, onClick = { showFilters = true }, label = { Text(if (filters.active > 0) "Filters (${filters.active})" else "Filters") }) }
                item { FilterChip(selected = allTime, onClick = { vm.allTime.value = !allTime }, label = { Text("All time") }) }
                val types = listOf(null, "EXPENSE", "INCOME", "TRANSFER", "INVESTMENT", "REFUND")
                items(types) { t ->
                    FilterChip(selected = type == t, onClick = { vm.type.value = t }, label = { Text(t?.let(::typeLabel) ?: "All") })
                }
            }
        }
        val total = items.filter { it.type == "EXPENSE" && it.status == TxnStatus.POSTED.name }.sumOf { it.amountPaise }
        Text("${items.size} transactions · ${Money.format(total)} spent", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) EmptyState(if (q.isBlank()) "No transactions in this period" else "Nothing matches “$q”")
        if (showFilters) FilterDialog(main, filters, onApply = { vm.filters.value = it; showFilters = false }, onDismiss = { showFilters = false })
        LazyColumn(Modifier.fillMaxSize()) {
            var lastDay = ""
            items.forEach { t ->
                val day = formatDay(t.dateMillis)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "h-${t.id}") {
                        Text(day, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                item(key = t.id) { TxnRow(t, cats) { onOpenTxn(t.id) } }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun ReviewScreen(main: MainViewModel, onOpenTxn: (Long) -> Unit) {
    val repo = main.repo
    val queue by repo.db.transactions().observeReviewQueue().collectAsState(initial = emptyList())
    val cats by main.categoriesById.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Review", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Low-confidence captures and possible duplicates wait here. Items marked Check are already counted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (queue.isEmpty()) item { EmptyState("All clear. Nothing needs your attention.") }
        items(queue, key = { it.id }) { t ->
            ReviewCard(t, cats, main, onOpenTxn,
                onApprove = { scope.launch { repo.setStatus(t.id, TxnStatus.POSTED) } },
                onReject = { scope.launch { repo.setStatus(t.id, TxnStatus.REJECTED) } },
                onDuplicate = { scope.launch { repo.confirmDuplicate(t.id) } })
        }
    }
}

@Composable
private fun ReviewCard(
    t: TransactionEntity, cats: Map<String, com.prash.kosh.data.CategoryEntity>, main: MainViewModel, onOpenTxn: (Long) -> Unit,
    onApprove: () -> Unit, onReject: () -> Unit, onDuplicate: () -> Unit,
) {
    val raw by main.repo.db.rawMessages().observeForTxn(t.id).collectAsState(initial = emptyList())
    val isDup = t.status == TxnStatus.SUSPECTED_DUPLICATE.name
    SectionCard {
        TxnRow(t, cats) { onOpenTxn(t.id) }
        raw.lastOrNull()?.let {
            Text("${it.sender} · ${formatDayTime(it.receivedAt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Text(it.body, fontSize = 12.sp, maxLines = 5, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        }
        if (isDup) Text("Same amount as another transaction within 15 minutes.", fontSize = 12.sp, color = Expense.forTheme())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (isDup) {
                Button(onClick = onDuplicate) { Text("Duplicate") }
                OutlinedButton(onClick = onApprove) { Text("Keep both") }
            } else {
                Button(onClick = onApprove) { Text("Approve") }
                OutlinedButton(onClick = { onOpenTxn(t.id) }) { Text("Edit") }
                OutlinedButton(onClick = onReject) { Text("Reject") }
            }
        }
    }
}

@Composable
private fun GlanceRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun FilterDialog(main: MainViewModel, current: com.prash.kosh.ui.TxnFilters, onApply: (com.prash.kosh.ui.TxnFilters) -> Unit, onDismiss: () -> Unit) {
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    val accounts by main.accounts.collectAsState()
    var f by remember { mutableStateOf(current) }
    var min by remember { mutableStateOf(paiseText(f.minPaise)) }
    var max by remember { mutableStateOf(paiseText(f.maxPaise)) }
    var picking by remember { mutableStateOf(false) }
    val ist = com.prash.kosh.ui.components.IST
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter transactions") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }) { Text("Category: " + (f.category?.let { com.prash.kosh.ui.components.categoryPath(it, byId) } ?: "Any")) }
                Text("Account", fontSize = 13.sp)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(f.accountId == null, { f = f.copy(accountId = null) }, label = { Text("Any") })
                    accounts.forEach { a -> FilterChip(f.accountId == a.id, { f = f.copy(accountId = a.id) }, label = { Text(a.name) }) }
                }
                Text("Payment method", fontSize = 13.sp)
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(f.method == null, { f = f.copy(method = null) }, label = { Text("Any") })
                    listOf("UPI", "CARD", "ATM", "CASH", "NEFT", "IMPS", "NETBANKING").forEach { m ->
                        FilterChip(f.method == m, { f = f.copy(method = m) }, label = { Text(m.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyField("Min ₹", min, { min = it }, Modifier.weight(1f)); MoneyField("Max ₹", max, { max = it }, Modifier.weight(1f))
                }
                DateField("From", f.fromMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(ist).toLocalDate() },
                    { d -> f = f.copy(fromMillis = d?.atStartOfDay(ist)?.toInstant()?.toEpochMilli()) }, allowClear = true)
                DateField("To", f.toMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(ist).toLocalDate() },
                    { d -> f = f.copy(toMillis = d?.plusDays(1)?.atStartOfDay(ist)?.toInstant()?.toEpochMilli()?.minus(1)) }, allowClear = true)
                Text("A date range overrides the month shown.", fontSize = 12.sp)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onApply(f.copy(minPaise = Money.parseToPaise(min), maxPaise = Money.parseToPaise(max))) }) { Text("Apply") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { onApply(com.prash.kosh.ui.TxnFilters()) }) { Text("Clear all") } },
    )
    if (picking) com.prash.kosh.ui.components.CategoryPickerDialog(cats, setOf("EXPENSE", "INCOME", "TRANSFER", "INVESTMENT"),
        onPick = { f = f.copy(category = it.id); picking = false }, onDismiss = { picking = false })
}
