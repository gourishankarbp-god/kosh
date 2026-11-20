package com.prash.kosh.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.KoshApp
import com.prash.kosh.data.MonthTypeTotal
import com.prash.kosh.engine.Metrics
import com.prash.kosh.engine.Money
import com.prash.kosh.engine.SeedData
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.Figure
import com.prash.kosh.ui.components.IST
import com.prash.kosh.ui.components.KeyValue
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.SimpleBars
import com.prash.kosh.ui.components.endMillis
import com.prash.kosh.ui.components.label
import com.prash.kosh.ui.components.startMillis
import com.prash.kosh.ui.theme.Expense
import com.prash.kosh.ui.theme.Income
import com.prash.kosh.ui.theme.Invest
import com.prash.kosh.ui.theme.forTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val mon = DateTimeFormatter.ofPattern("MMM")

/**
 * Advanced metrics for a month or a year:
 *  - Recurring expense ratio: recurring spending ÷ all spending.
 *  - Discretionary burn rate: quick-commerce spending per day, and where the month is heading.
 *  - Fixed vs variable, and (yearly) a month-by-month overview.
 */
@Composable
fun InsightsScreen(main: MainViewModel, onBack: () -> Unit) {
    val app = KoshApp.get(LocalContext.current)
    var yearly by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now(IST)) }
    var fy by remember { mutableStateOf(Metrics.fyStartYear(LocalDate.now(IST))) }
    var includeFood by remember { mutableStateOf(app.settings.burnIncludesFoodDelivery) }
    val today = LocalDate.now(IST)

    // Months shown in the charts: 6 ending at the chosen month, or April-March of the chosen financial year.
    val months: List<YearMonth> = if (yearly) Metrics.fyMonths(fy) else (5 downTo 0).map { month.minusMonths(it.toLong()) }
    val from = months.first().startMillis(); val to = months.last().endMillis()
    val burnCats = if (includeFood) SeedData.QUICK_COMMERCE + SeedData.FOOD_DELIVERY else SeedData.QUICK_COMMERCE
    val dao = main.repo.db.transactions()
    val totals by remember(from, to) { dao.observeMonthlyTotalsBetween(from, to) }.collectAsState(initial = emptyList())
    val recurring by remember(from, to) { dao.observeMonthlyRecurring(from, to) }.collectAsState(initial = emptyList())
    val burn by remember(from, to, includeFood) { dao.observeMonthlyInCategories(from, to, burnCats) }.collectAsState(initial = emptyList())
    // Previous period, for comparisons.
    val prevFrom = if (yearly) Metrics.fyMonths(fy - 1).first().startMillis() else month.minusMonths(1).startMillis()
    val prevTo = if (yearly) Metrics.fyMonths(fy - 1).last().endMillis() else month.minusMonths(1).endMillis()
    val prevBurn by remember(prevFrom, prevTo, includeFood) { dao.observeMonthlyInCategories(prevFrom, prevTo, burnCats) }.collectAsState(initial = emptyList())
    val periodFrom = if (yearly) months.first().startMillis() else month.startMillis()
    val periodTo = if (yearly) months.last().endMillis() else month.endMillis()
    val archived by main.repo.db.extras().observeArchive().collectAsState(initial = emptyList())
    val arch = archived.associateBy { YearMonth.parse(it.month) }
    val topMerchants by remember(periodFrom, periodTo, includeFood) { dao.observeTopMerchantsIn(periodFrom, periodTo, burnCats) }.collectAsState(initial = emptyList())

    // Months moved to the Drive archive contribute their stored totals.
    fun archivedValue(m: YearMonth, kind: String): Long = arch[m]?.let { a ->
        when (kind) { "INCOME" -> a.income; "EXPENSE" -> a.expense; "REFUND" -> a.refunds; "INVESTMENT" -> a.invested; "TRANSFER" -> a.transfers
            "RECURRING" -> a.recurring; "BURN" -> a.quickCommerce; else -> 0 } } ?: 0
    fun sum(list: List<MonthTypeTotal>, ms: List<YearMonth>, type: String? = null, archKind: String? = type): Long =
        list.filter { (type == null || it.type == type) && YearMonth.parse(it.month) in ms }.sumOf { it.total } +
            (archKind?.let { k -> ms.sumOf { m -> archivedValue(m, k) } } ?: 0L)
    fun expenses(ms: List<YearMonth>) = (sum(totals, ms, "EXPENSE") - sum(totals, ms, "REFUND")).coerceAtLeast(0)
    val period = if (yearly) months else listOf(month)
    val periodExpense = expenses(period)
    val periodRecurring = sum(recurring, period, archKind = "RECURRING")
    val periodIncome = sum(totals, period, "INCOME")
    val ratio = Metrics.recurringExpenseRatio(periodRecurring, periodExpense)
    val burnSpent = sum(burn, period, archKind = "BURN")
    val prevBurnSpent = prevBurn.sumOf { it.total }
    val (pStart, pEnd) = if (yearly) months.first().atDay(1) to months.last().atEndOfMonth() else month.atDay(1) to month.atEndOfMonth()
    val rate = Metrics.burnRate(burnSpent, pStart, pEnd, today)
    val periodName = if (yearly) Metrics.fyLabel(fy) else month.label()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Insights", onBack) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!yearly, { yearly = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Month") }
                SegmentedButton(yearly, { yearly = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Financial year") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (yearly) fy -= 1 else month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                Text(periodName, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val canNext = if (yearly) fy < Metrics.fyStartYear(LocalDate.now(IST)) else month < YearMonth.now(IST)
                IconButton(onClick = { if (yearly) fy += 1 else month = month.plusMonths(1) }, enabled = canNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
            }
        }

        // ---------------------------------------------------------- recurring expense ratio
        item {
            SectionCard(title = "Recurring expense ratio") {
                Row {
                    Figure(periodName, ratio?.let { "$it%" } ?: "—", Modifier.weight(1f),
                        sub = "of spending went to recurring payments")
                    Figure("Recurring", Money.format(periodRecurring, showPaise = false), Modifier.weight(1f),
                        sub = "of ${Money.format(periodExpense, showPaise = false)} spent")
                }
                Metrics.percent(periodRecurring, periodIncome)?.let {
                    Text("That is $it% of your income.", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                SimpleBars(months.map { m -> m.format(mon) to (Metrics.recurringExpenseRatio(sum(recurring, listOf(m), archKind = "RECURRING"), expenses(listOf(m))) ?: 0).toLong() },
                    Invest.forTheme(), highlight = if (yearly) -1 else months.lastIndex) { "$it%" }
                Text("Lower means more of your spending is flexible. Recurring = categories marked fixed plus payments matched to a recurring payment.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---------------------------------------------------------- discretionary burn rate
        item {
            SectionCard(title = "Discretionary burn rate · quick commerce") {
                Row {
                    Figure("Per day", Money.format(rate.perDayPaise, showPaise = false), Modifier.weight(1f), color = Expense.forTheme(),
                        sub = "over ${rate.daysElapsed} of ${rate.daysInPeriod} days")
                    Figure(if (yearly) "This financial year" else "This month", Money.format(burnSpent, showPaise = false), Modifier.weight(1f),
                        sub = if (rate.daysElapsed < rate.daysInPeriod && rate.daysElapsed > 0) "heading for ${Money.format(rate.projectedPaise, showPaise = false)}" else null)
                }
                val prevLabel = if (yearly) "last financial year" else "last month"
                if (prevBurnSpent > 0) {
                    val change = Metrics.percent(rate.projectedPaise - prevBurnSpent, prevBurnSpent)
                    Text(when {
                        change == null -> ""
                        change > 0 -> "On pace for $change% more than $prevLabel (${Money.format(prevBurnSpent, showPaise = false)})."
                        change < 0 -> "On pace for ${-change}% less than $prevLabel (${Money.format(prevBurnSpent, showPaise = false)})."
                        else -> "Same pace as $prevLabel."
                    }, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Metrics.percent(burnSpent, (periodExpense - periodRecurring).coerceAtLeast(0))?.let {
                    Text("$it% of your variable spending.", fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                SimpleBars(months.map { m -> m.format(mon) to sum(burn, listOf(m), archKind = "BURN") }, Expense.forTheme(), highlight = if (yearly) -1 else months.lastIndex)
                if (topMerchants.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Where it went", style = MaterialTheme.typography.labelLarge)
                    topMerchants.forEach { KeyValue(it.categoryId ?: "Unknown", Money.format(it.total, showPaise = false)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).clickable {
                    includeFood = !includeFood; app.settings.burnIncludesFoodDelivery = includeFood
                }) {
                    Switch(includeFood, { includeFood = it; app.settings.burnIncludesFoodDelivery = it })
                    Text("  Include food delivery (Swiggy, Zomato)", fontSize = 13.sp)
                }
                Text("Quick commerce = Blinkit, Zepto, Instamart, BB Now, Flipkart Minutes, Amazon Now and anything you put in Groceries › Quick commerce.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------------------------------------------------------- fixed vs variable
        item {
            SectionCard(title = "Fixed vs variable") {
                KeyValue("Fixed (recurring)", Money.format(periodRecurring, showPaise = false))
                KeyValue("Variable", Money.format((periodExpense - periodRecurring).coerceAtLeast(0), showPaise = false))
                KeyValue("Invested", Money.format(sum(totals, period, "INVESTMENT"), showPaise = false), valueColor = Invest.forTheme())
                KeyValue("Income", Money.format(periodIncome, showPaise = false), valueColor = Income.forTheme())
                KeyValue("Saved", Money.format(periodIncome - periodExpense, showPaise = false), bold = true)
                Money.savingsRatePercent(periodIncome, periodExpense)?.let { KeyValue("Savings rate", "$it%", bold = true) }
            }
        }

        // ---------------------------------------------------------- annual overview
        if (yearly) item {
            SectionCard(title = "Month by month") {
                Row { listOf("Month", "Income", "Spent", "Saved").forEachIndexed { i, h ->
                    Text(h, Modifier.weight(if (i == 0) 0.8f else 1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = if (i == 0) TextAlign.Start else TextAlign.End) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                months.forEach { m ->
                    val inc = sum(totals, listOf(m), "INCOME"); val exp = expenses(listOf(m))
                    if (m <= YearMonth.now(IST)) Row(Modifier.padding(vertical = 3.dp)) {
                        Text(m.format(mon), Modifier.weight(0.8f), fontSize = 13.sp)
                        Text(Money.compact(inc), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.End, color = Income.forTheme())
                        Text(Money.compact(exp), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.End, color = Expense.forTheme())
                        Text(Money.compact(inc - exp), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.End)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row {
                    Text("Total", Modifier.weight(0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(Money.compact(periodIncome), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
                    Text(Money.compact(periodExpense), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
                    Text(Money.compact(periodIncome - periodExpense), Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
