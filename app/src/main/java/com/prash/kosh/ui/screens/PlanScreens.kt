package com.prash.kosh.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.data.BudgetRow
import com.prash.kosh.data.DueItem
import com.prash.kosh.data.GoalEntity
import com.prash.kosh.data.RecurringRuleEntity
import com.prash.kosh.data.addGoalContribution
import com.prash.kosh.data.averageMonthlyExpense
import com.prash.kosh.data.budgetRows
import com.prash.kosh.data.markInvestmentInstalmentPaid
import com.prash.kosh.data.markRulePaid
import com.prash.kosh.data.saveBudget
import com.prash.kosh.data.saveRecurringRule
import com.prash.kosh.data.skipRuleOccurrence
import com.prash.kosh.data.upcoming
import com.prash.kosh.engine.Frequency
import com.prash.kosh.engine.Metrics
import com.prash.kosh.engine.Money
import com.prash.kosh.notify.Notifications
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.BudgetBar
import com.prash.kosh.ui.components.CategoryPickerDialog
import com.prash.kosh.ui.components.EmptyState
import com.prash.kosh.ui.components.Figure
import com.prash.kosh.ui.components.IST
import com.prash.kosh.ui.components.KeyValue
import com.prash.kosh.ui.components.MonthSwitcher
import com.prash.kosh.ui.components.Pill
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.categoryPath
import com.prash.kosh.ui.components.endMillis
import com.prash.kosh.ui.components.startMillis
import com.prash.kosh.ui.theme.Expense
import com.prash.kosh.ui.theme.Income
import com.prash.kosh.ui.theme.forTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dueFmt = DateTimeFormatter.ofPattern("d MMM")
fun LocalDate.short(): String = format(dueFmt)
fun paiseText(p: Long?): String = p?.takeIf { it > 0 }?.let { BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString() } ?: ""

/* ------------------------------------------------------------------ shared live data */

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun rememberBudgetRows(main: MainViewModel, month: YearMonth): List<BudgetRow> {
    val db = main.repo.db
    val flow = remember(month) {
        combine(db.budgets().observeAll(), db.transactions().observeTypeTotals(month.minusMonths(1).startMillis(), month.endMillis())) { _, _ -> }
            .mapLatest { main.repo.budgetRows(month) }
    }
    return flow.collectAsState(initial = emptyList()).value
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun rememberUpcoming(main: MainViewModel, horizon: Long = 30): List<DueItem> {
    val db = main.repo.db
    val flow = remember(horizon) {
        combine(db.recurring().observeAll(), db.investments().observeAll(), db.investments().observeTotals()) { _, _, _ -> }
            .mapLatest { main.repo.upcoming(LocalDate.now(IST), horizon) }
    }
    return flow.collectAsState(initial = emptyList()).value
}

/** Asks for notification permission (Android 13+) where reminders or alerts matter. */
@Composable
fun NotificationPermissionCard(purpose: String) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(Notifications.allowed(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    if (allowed || Build.VERSION.SDK_INT < 33) return
    SectionCard(title = "Allow notifications") {
        Text("Kosh can notify you $purpose. Nothing leaves your phone.", fontSize = 13.sp)
        Button(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow notifications") }
    }
}

/** A tappable read-only field that opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, allowClear: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${date?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: "not set"}") }
        if (allowClear && date != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now(IST)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                open = false
            }) { Text("OK") }
        }) { DatePicker(state) }
    }
}

@Composable
fun MoneyField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, { onChange(it.filter { c -> c.isDigit() || c == '.' }) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

/* ------------------------------------------------------------------ Plan hub */

@Composable
fun PlanScreen(main: MainViewModel, onOpen: (String) -> Unit) {
    val month = YearMonth.now(IST)
    val rows = rememberBudgetRows(main, month)
    val due = rememberUpcoming(main)
    val invested by main.repo.db.investments().observeTotals().collectAsState(initial = emptyList())
    val investments by main.repo.db.investments().observeAll().collectAsState(initial = emptyList())
    val goals by main.repo.db.goals().observeActive().collectAsState(initial = emptyList())
    val goalTotals by main.repo.db.goals().observeTotals().collectAsState(initial = emptyList())
    val today = LocalDate.now(IST)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Plan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            HubCard("Budgets", onClick = { onOpen("budgets") }) {
                if (rows.isEmpty()) Text("No budgets yet. Set a monthly limit per category.", fontSize = 13.sp)
                else {
                    val spent = rows.sumOf { it.spentPaise }; val avail = rows.sumOf { it.availablePaise }
                    Text("${Money.format(spent, showPaise = false)} of ${Money.format(avail, showPaise = false)} used this month", fontSize = 14.sp)
                    BudgetBar(Metrics.percent(spent, avail) ?: 0, Modifier.padding(vertical = 6.dp))
                    val over = rows.count { it.percent >= 100 }
                    if (over > 0) Text("$over categor${if (over == 1) "y is" else "ies are"} over budget", fontSize = 12.sp, color = Expense.forTheme())
                }
            }
        }
        item {
            HubCard("Upcoming & fixed payments", onClick = { onOpen("upcoming") }) {
                if (due.isEmpty()) Text("Nothing due in the next 30 days.", fontSize = 13.sp)
                due.take(3).forEach { d -> KeyValue("${d.name} · ${dueLabel(d.dueDate, today)}", Money.format(d.amountPaise, showPaise = false)) }
                if (due.size > 3) Text("+${due.size - 3} more", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            HubCard("LIC, insurance & bonds", onClick = { onOpen("investments") }) {
                val active = investments.filter { it.status == "ACTIVE" }.map { it.id }.toSet()
                val net = invested.filter { it.investmentId in active }.sumOf { it.contributed - it.withdrawn }
                Text(if (investments.isEmpty()) "Add LIC and insurance policies, bonds, and new FDs or RDs." else "${Money.format(net, showPaise = false)} paid into ${active.size} active polic${if (active.size == 1) "y or investment" else "ies and investments"}", fontSize = 14.sp)
            }
        }
        item {
            HubCard("Cash", onClick = { onOpen("cash") }) { Text("Cash in hand, ATM withdrawals and cash spending.", fontSize = 13.sp) }
        }
        item {
            HubCard("Savings goals", onClick = { onOpen("goals") }) {
                if (goals.isEmpty()) Text("Emergency fund, a purchase, or any savings target.", fontSize = 13.sp)
                goals.take(3).forEach { g ->
                    val t = goalTotals.firstOrNull { it.investmentId == g.id }
                    val saved = (t?.contributed ?: 0) - (t?.withdrawn ?: 0)
                    KeyValue(g.name, "${Metrics.percent(saved, g.targetPaise) ?: 0}%")
                }
            }
        }
        item {
            HubCard("Insights", onClick = { onOpen("insights") }) {
                Text("Recurring expense ratio, quick-commerce burn rate, fixed vs variable, financial-year overview.", fontSize = 13.sp)
            }
        }
        item {
            HubCard("Reports", onClick = { onOpen("reports") }) {
                Text("Monthly and yearly expenses, categories, merchants, income, cash, transfers, LIC and bond payments. Export to CSV.", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun HubCard(title: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

fun dueLabel(d: LocalDate, today: LocalDate): String {
    val n = ChronoUnit.DAYS.between(today, d)
    return when {
        n < -1 -> "overdue ${-n} days"
        n == -1L -> "overdue since yesterday"
        n == 0L -> "today"
        n == 1L -> "tomorrow"
        n < 7 -> "in $n days"
        else -> d.short()
    }
}

/* ------------------------------------------------------------------ Budgets */

@Composable
fun BudgetsScreen(main: MainViewModel, onBack: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now(IST)) }
    val rows = rememberBudgetRows(main, month)
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    val split by remember(month) { main.repo.db.transactions().observeMonthlyRecurring(month.startMillis(), month.endMillis()) }.collectAsState(initial = emptyList())
    val totals by remember(month) { main.repo.db.transactions().observeTypeTotals(month.startMillis(), month.endMillis()) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }   // category id
    var picking by remember { mutableStateOf(false) }

    val totalExpense = totals.firstOrNull { it.type == "EXPENSE" }?.total ?: 0
    val fixed = split.sumOf { it.total }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Budgets", onBack) }
        item { MonthSwitcher(month) { month = it } }
        item { NotificationPermissionCard("when a category reaches 80% and 100% of its budget") }
        item {
            SectionCard(title = "This month") {
                val spent = rows.sumOf { it.spentPaise }; val avail = rows.sumOf { it.availablePaise }
                if (rows.isNotEmpty()) {
                    Row { Figure("Budgeted spend", Money.format(spent, showPaise = false), Modifier.weight(1f), sub = "of ${Money.format(avail, showPaise = false)}")
                        Figure("Left", Money.format(avail - spent, showPaise = false), Modifier.weight(1f), color = if (avail - spent < 0) Expense.forTheme() else Income.forTheme()) }
                    BudgetBar(Metrics.percent(spent, avail) ?: 0, Modifier.padding(vertical = 8.dp))
                }
                KeyValue("Fixed (recurring) expenses", Money.format(fixed, showPaise = false))
                KeyValue("Variable expenses", Money.format((totalExpense - fixed).coerceAtLeast(0), showPaise = false))
                KeyValue("All expenses", Money.format(totalExpense, showPaise = false), bold = true)
            }
        }
        if (rows.isEmpty()) item { EmptyState("No budgets yet. Tap “Set a budget” and choose a category.") }
        items(rows.sortedByDescending { it.percent }, key = { it.categoryId }) { r ->
            SectionCard(Modifier.clickable { editing = r.categoryId }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(byId[r.categoryId]?.name ?: r.categoryId, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text("${r.percent}%", fontWeight = FontWeight.SemiBold, color = if (r.percent >= 100) Expense.forTheme() else MaterialTheme.colorScheme.onSurface)
                }
                BudgetBar(r.percent, Modifier.padding(vertical = 6.dp))
                Text("${Money.format(r.spentPaise, showPaise = false)} of ${Money.format(r.availablePaise, showPaise = false)}" +
                    (if (r.availablePaise > r.limitPaise) " (incl. ${Money.format(r.availablePaise - r.limitPaise, showPaise = false)} rolled over)" else "") +
                    " · " + (if (r.remainingPaise >= 0) "${Money.format(r.remainingPaise, showPaise = false)} left" else "${Money.format(-r.remainingPaise, showPaise = false)} over"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Button(onClick = { picking = true }) { Text("Set a budget") } }
        item { Text("Fixed expenses are categories marked “fixed” (More › Categories) plus payments matched to a recurring payment.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    if (picking) CategoryPickerDialog(cats.filter { it.parentId == null }, setOf("EXPENSE"), onPick = { editing = it.id; picking = false }, onDismiss = { picking = false })

    editing?.let { catId ->
        val existing = rows.firstOrNull { it.categoryId == catId }
        var amount by remember(catId) { mutableStateOf(paiseText(existing?.limitPaise)) }
        var rollover by remember(catId) { mutableStateOf(existing?.rollover ?: false) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Budget: ${categoryPath(catId, byId)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyField("Monthly limit (₹)", amount, { amount = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(rollover, { rollover = it })
                        Text("  Carry unspent money to next month", fontSize = 14.sp)
                    }
                    Text("Covers this category and all its subcategories. Alerts at 80% and 100%.", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Money.parseToPaise(amount)?.let { p -> scope.launch { main.repo.saveBudget(catId, p, rollover, month); editing = null } }
                }) { Text("Save") }
            },
            dismissButton = {
                if (existing != null) TextButton(onClick = { scope.launch { main.repo.db.budgets().delete(catId); editing = null } }) { Text("Remove") }
            },
        )
    }
}

/* ------------------------------------------------------------------ Upcoming payments + recurring rules */

@Composable
fun UpcomingScreen(main: MainViewModel, onBack: () -> Unit) {
    val due = rememberUpcoming(main, 60)
    val rules by main.repo.db.recurring().observeAll().collectAsState(initial = emptyList())
    val byId by main.categoriesById.collectAsState()
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(IST)
    var paying by remember { mutableStateOf<DueItem?>(null) }
    var editing by remember { mutableStateOf<RecurringRuleEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Upcoming payments", onBack) }
        item { NotificationPermissionCard("before recurring payments, LIC and insurance premiums, bond and RD instalments are due") }
        item {
            SectionCard(title = "Next 60 days") {
                if (due.isEmpty()) Text("Nothing due. Add recurring payments below, or policies and bonds under LIC, insurance & bonds.", fontSize = 13.sp)
                due.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    val overdue = d.dueDate.isBefore(today)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(d.name, fontWeight = FontWeight.Medium)
                                Pill(kindLabel(d.kind), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(dueLabel(d.dueDate, today), fontSize = 12.sp, color = if (overdue) Expense.forTheme() else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text((if (d.isIncome) "+" else "") + Money.format(d.amountPaise, showPaise = false), fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { paying = d }) { Text(if (d.isIncome) "Received" else "Mark paid") }
                        }
                    }
                }
                Text("Payments that arrive by bank SMS are matched automatically and drop off this list.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recurring payments", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { editing = RecurringRuleEntity(name = "", type = "EXPENSE", amountPaise = 0, categoryId = "other",
                    frequency = "MONTHLY", startDate = today.toString(), nextDueDate = today.toString()) }) { Text("+ Add") }
            }
        }
        if (rules.isEmpty()) item { EmptyState("Rent, bills, subscriptions, EMIs, salary… Add them here, or open a transaction and tap “Make recurring”.") }
        items(rules, key = { it.id }) { r ->
            SectionCard(Modifier.clickable { editing = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name + if (!r.active) " (paused)" else "", fontWeight = FontWeight.Medium)
                        Text("${Frequency.valueOf(r.frequency).label} · ${categoryPath(r.categoryId, byId)} · next ${LocalDate.parse(r.nextDueDate).short()}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Money.format(r.amountPaise, showPaise = false), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    paying?.let { d ->
        var record by remember(d) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { paying = null },
            title = { Text("${d.name}: ${Money.format(d.amountPaise, showPaise = false)}") },
            text = {
                Column {
                    if (d.kind == "RECURRING") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { record = !record }) {
                            Switch(record, { record = it })
                            Text("  Also add it as a transaction", fontSize = 14.sp)
                        }
                        Text("Turn this off if the bank message is already in Kosh.", fontSize = 12.sp)
                    } else Text("Records this instalment as paid today. If the bank message arrives later it is linked automatically.", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (d.kind == "RECURRING") main.repo.markRulePaid(d.refId, today, record) else main.repo.markInvestmentInstalmentPaid(d.refId, today)
                        paying = null
                    }
                }) { Text(if (d.isIncome) "Received" else "Paid") }
            },
            dismissButton = {
                if (d.kind == "RECURRING") TextButton(onClick = { scope.launch { main.repo.skipRuleOccurrence(d.refId); paying = null } }) { Text("Skip this time") }
            },
        )
    }
    editing?.let { r -> RecurringRuleDialog(main, r) { editing = null } }
}

fun kindLabel(kind: String) = when (kind) {
    "RECURRING" -> "Recurring"; "LIC" -> "LIC premium"; "INSURANCE" -> "Insurance premium"; "BOND" -> "Bond instalment"; "RD" -> "RD"; else -> "Instalment"
}

@Composable
fun RecurringRuleDialog(main: MainViewModel, r: RecurringRuleEntity, onDone: () -> Unit) {
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(r.name) }
    var amount by remember { mutableStateOf(paiseText(r.amountPaise)) }
    var type by remember { mutableStateOf(r.type) }
    var cat by remember { mutableStateOf(r.categoryId) }
    var freq by remember { mutableStateOf(r.frequency) }
    var next by remember { mutableStateOf<LocalDate?>(LocalDate.parse(r.nextDueDate)) }
    var remind by remember { mutableIntStateOf(r.remindDaysBefore) }
    var active by remember { mutableStateOf(r.active) }
    var pickCat by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (r.id == 0L) "New recurring payment" else "Recurring payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Rent, Airtel)") }, singleLine = true)
                MoneyField("Amount (₹)", amount, { amount = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer").forEach { (k, l) ->
                        FilterChip(type == k, { type = k; cat = if (k == "INCOME") "income.salary" else if (k == "TRANSFER") "transfers" else "other" }, label = { Text(l) })
                    }
                }
                OutlinedButton(onClick = { pickCat = true }) { Text(categoryPath(cat, byId)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.YEARLY).forEach { f ->
                        FilterChip(freq == f.name, { freq = f.name }, label = { Text(f.label) })
                    }
                }
                DateField("Next due", next, { next = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remind ", fontSize = 14.sp)
                    listOf(0, 1, 3).forEach { d -> FilterChip(remind == d, { remind = d }, label = { Text(if (d == 0) "on the day" else "$d day${if (d > 1) "s" else ""} before") },
                        modifier = Modifier.padding(end = 4.dp)) }
                }
                if (r.id != 0L) Row(verticalAlignment = Alignment.CenterVertically) { Switch(active, { active = it }); Text("  Active", fontSize = 14.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = Money.parseToPaise(amount) ?: return@TextButton
                val due = next ?: return@TextButton
                if (name.isBlank()) return@TextButton
                scope.launch {
                    main.repo.saveRecurringRule(r.copy(name = name.trim(), amountPaise = p, type = type, categoryId = cat, frequency = freq,
                        startDate = if (r.id == 0L || r.frequency != freq) due.toString() else r.startDate,
                        nextDueDate = due.toString(), remindDaysBefore = remind, active = active))
                    onDone()
                }
            }) { Text("Save") }
        },
        dismissButton = {
            if (r.id != 0L) TextButton(onClick = { scope.launch { main.repo.db.recurring().delete(r.id); onDone() } }) { Text("Delete") }
        },
    )
    if (pickCat) CategoryPickerDialog(cats, setOf(if (type == "INCOME") "INCOME" else if (type == "TRANSFER") "TRANSFER" else "EXPENSE"),
        onPick = { cat = it.id; pickCat = false }, onDismiss = { pickCat = false })
}

/* ------------------------------------------------------------------ Savings goals */

@Composable
fun GoalsScreen(main: MainViewModel, onBack: () -> Unit) {
    val goals by main.repo.db.goals().observeActive().collectAsState(initial = emptyList())
    val totals by main.repo.db.goals().observeTotals().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(IST)
    var avgExpense by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { avgExpense = main.repo.averageMonthlyExpense(today) }
    var editing by remember { mutableStateOf<GoalEntity?>(null) }
    var adding by remember { mutableStateOf<GoalEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Savings goals", onBack) }
        if (goals.isEmpty()) item { EmptyState("Start with an emergency fund: most advice says 3–6 months of expenses.") }
        items(goals, key = { it.id }) { g ->
            val t = totals.firstOrNull { it.investmentId == g.id }
            val saved = (t?.contributed ?: 0) - (t?.withdrawn ?: 0)
            val pct = Metrics.percent(saved, g.targetPaise) ?: 0
            val target = g.targetDate?.let(LocalDate::parse)
            SectionCard(Modifier.clickable { editing = g }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Pill(when (g.kind) { "EMERGENCY" -> "Emergency fund"; "PURCHASE" -> "Purchase"; else -> "Savings" },
                        MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BudgetBar(pct.coerceAtMost(100), Modifier.padding(vertical = 6.dp))
                Text("${Money.format(saved, showPaise = false)} of ${Money.format(g.targetPaise, showPaise = false)} · $pct%", fontSize = 13.sp)
                if (g.kind == "EMERGENCY") Metrics.monthsCovered(saved, avgExpense)?.let {
                    Text("Covers $it months of your average spending (${Money.format(avgExpense, showPaise = false)}/month)", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Metrics.monthlyNeeded(g.targetPaise - saved, today, target)?.let { need ->
                    if (need > 0) Text("Save ${Money.format(need, showPaise = false)} a month to reach it by ${target!!.format(DateTimeFormatter.ofPattern("MMM yyyy"))}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = { adding = g }) { Text("Add or take out money") }
                }
            }
        }
        item {
            Button(onClick = { editing = GoalEntity(name = "", kind = "SAVINGS", targetPaise = 0, createdAt = System.currentTimeMillis()) }) { Text("New goal") }
        }
        item { Text("Goals track money you set aside. They do not move money between accounts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    editing?.let { g ->
        var name by remember(g) { mutableStateOf(g.name) }
        var kind by remember(g) { mutableStateOf(g.kind) }
        var target by remember(g) { mutableStateOf(paiseText(g.targetPaise)) }
        var date by remember(g) { mutableStateOf(g.targetDate?.let(LocalDate::parse)) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (g.id == 0L) "New goal" else "Edit goal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("EMERGENCY" to "Emergency", "PURCHASE" to "Purchase", "SAVINGS" to "Savings").forEach { (k, l) ->
                            FilterChip(kind == k, { kind = k; if (name.isBlank() && k == "EMERGENCY") name = "Emergency fund" }, label = { Text(l) })
                        }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. New laptop)") }, singleLine = true)
                    MoneyField("Target amount (₹)", target, { target = it })
                    DateField("Target date", date, { date = it }, allowClear = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = Money.parseToPaise(target) ?: return@TextButton
                    if (name.isBlank()) return@TextButton
                    scope.launch { main.repo.db.goals().upsert(g.copy(name = name.trim(), kind = kind, targetPaise = p, targetDate = date?.toString())); editing = null }
                }) { Text("Save") }
            },
            dismissButton = {
                if (g.id != 0L) TextButton(onClick = { scope.launch { main.repo.db.goals().upsert(g.copy(archived = true)); editing = null } }) { Text("Archive") }
            },
        )
    }
    adding?.let { g ->
        var amount by remember(g) { mutableStateOf("") }
        var note by remember(g) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(g.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyField("Amount (₹)", amount, { amount = it })
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { Money.parseToPaise(amount)?.let { p -> scope.launch { main.repo.addGoalContribution(g.id, p, today, note.ifBlank { null }); adding = null } } }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { Money.parseToPaise(amount)?.let { p -> scope.launch { main.repo.addGoalContribution(g.id, -p, today, note.ifBlank { null }); adding = null } } }) { Text("Take out") }
            },
        )
    }
}

