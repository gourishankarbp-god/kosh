package com.prash.kosh.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.KoshApp
import com.prash.kosh.data.AccountEntity
import com.prash.kosh.data.AccountFlow
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.data.noonMillis
import com.prash.kosh.engine.Money
import com.prash.kosh.engine.TxnStatus
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.EmptyState
import com.prash.kosh.ui.components.Figure
import com.prash.kosh.ui.components.IST
import com.prash.kosh.ui.components.KeyValue
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.TxnRow
import com.prash.kosh.ui.components.categoryPath
import com.prash.kosh.ui.components.endMillis
import com.prash.kosh.ui.components.formatDay
import com.prash.kosh.ui.components.startMillis
import com.prash.kosh.ui.theme.Expense
import com.prash.kosh.ui.theme.Income
import com.prash.kosh.ui.theme.forTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/* ------------------------------------------------------------------ balances */

/** Balance of an account: opening + money in - money out. For a card, a positive "outstanding" means you owe. */
fun balanceOf(a: AccountEntity, f: AccountFlow?): Long = a.openingBalancePaise + (f?.inflow ?: 0) - (f?.outflow ?: 0)
fun outstandingOf(a: AccountEntity, f: AccountFlow?): Long = -balanceOf(a, f)

data class MoneySummary(val bankTotal: Long, val cash: Long, val cardOutstanding: Long)

fun moneySummary(accounts: List<AccountEntity>, flows: List<AccountFlow>): MoneySummary {
    val byId = flows.associateBy { it.accountId }
    return MoneySummary(
        bankTotal = accounts.filter { it.kind == "BANK" || it.kind == "WALLET" }.sumOf { balanceOf(it, byId[it.id]) },
        cash = accounts.filter { it.kind == "CASH" }.sumOf { balanceOf(it, byId[it.id]) },
        cardOutstanding = accounts.filter { it.kind == "CARD" }.sumOf { outstandingOf(it, byId[it.id]) },
    )
}

/* ------------------------------------------------------------------ accounts */

@Composable
fun AccountsScreen(main: MainViewModel, onBack: () -> Unit, onOpenAccount: (Long) -> Unit = {}) {
    val accounts by main.accounts.collectAsState()
    val flows by main.repo.db.transactions().observeAccountFlows().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    val byId = flows.associateBy { it.accountId }
    val sum = moneySummary(accounts, flows)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("Accounts", onBack) }
        item {
            SectionCard {
                Row {
                    Figure("In banks", Money.format(sum.bankTotal, showPaise = false), Modifier.weight(1f))
                    Figure("Cash", Money.format(sum.cash, showPaise = false), Modifier.weight(1f))
                    Figure("Cards owed", Money.format(sum.cardOutstanding, showPaise = false), Modifier.weight(1f),
                        color = if (sum.cardOutstanding > 0) Expense.forTheme() else MaterialTheme.colorScheme.onSurface)
                }
                Text("Balances are opening balance plus everything Kosh has recorded since. Set opening balances for accurate figures.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        listOf("BANK" to "Bank accounts", "CARD" to "Credit cards", "CASH" to "Cash", "WALLET" to "Wallets").forEach { (kind, title) ->
            val list = accounts.filter { it.kind == kind }
            if (list.isNotEmpty()) {
                item(key = "h$kind") { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                items(list, key = { it.id }) { a ->
                    val f = byId[a.id]
                    SectionCard(Modifier.clickable { onOpenAccount(a.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontWeight = FontWeight.Medium)
                                Text("In ${Money.format(f?.inflow ?: 0, showPaise = false)} · out ${Money.format(f?.outflow ?: 0, showPaise = false)}",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (kind == "CARD") Text("Owed ${Money.format(outstandingOf(a, f), showPaise = false)}", fontWeight = FontWeight.SemiBold)
                            else Text(Money.format(balanceOf(a, f), showPaise = false), fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { editing = a }) { Text("Edit") }
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { editing = AccountEntity(name = "", kind = "BANK") }) { Text("Add account") } }
    }

    editing?.let { a ->
        var name by remember(a) { mutableStateOf(a.name) }
        var last4 by remember(a) { mutableStateOf(a.last4.orEmpty()) }
        var opening by remember(a) { mutableStateOf(paiseText(kotlin.math.abs(a.openingBalancePaise))) }
        var kind by remember(a) { mutableStateOf(a.kind) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (a.id == 0L) "New account" else "Edit account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    MoneyField(if (kind == "CARD") "Amount owed when you started (₹)" else "Opening balance (₹)", opening, { opening = it })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("BANK" to "Bank", "CARD" to "Credit card", "WALLET" to "Wallet", "CASH" to "Cash").forEach { (k, l) ->
                            FilterChip(kind == k, { kind = k }, label = { Text(l) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val o = Money.parseToPaise(opening) ?: 0
                    scope.launch {
                        main.repo.saveAccount(a.copy(name = name.ifBlank { "Account" }, last4 = last4.ifBlank { null }, kind = kind,
                            openingBalancePaise = if (kind == "CARD") -o else o))
                        editing = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                if (a.id != 0L) TextButton(onClick = { scope.launch { main.repo.saveAccount(a.copy(archived = true)); editing = null } }) { Text("Archive") }
            },
        )
    }
}

/** Every transaction on one account, either side of a transfer. */
@Composable
fun AccountHistoryScreen(main: MainViewModel, accountId: Long, onBack: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val accounts by main.accounts.collectAsState()
    val cats by main.categoriesById.collectAsState()
    val items by remember(accountId) { main.repo.db.transactions().observeForAccount(accountId, 300) }.collectAsState(initial = emptyList())
    val a = accounts.firstOrNull { it.id == accountId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { ScreenHeader(a?.name ?: "Account", onBack) }
        if (items.isEmpty()) item { EmptyState("No transactions on this account yet") }
        items(items, key = { it.id }) { t -> TxnRow(t.asSeenFrom(accountId), cats) { onOpenTxn(t.id) } }
    }
}

/** For the receiving side of a transfer, show the money arriving (CREDIT) and vice versa. */
fun TransactionEntity.asSeenFrom(accountId: Long): TransactionEntity =
    if (toAccountId == accountId && this.accountId != accountId) copy(direction = if (direction == "DEBIT") "CREDIT" else "DEBIT") else this

/* ------------------------------------------------------------------ cash */

@Composable
fun CashScreen(main: MainViewModel, onBack: () -> Unit, onOpenTxn: (Long) -> Unit) {
    val repo = main.repo
    val accounts by main.accounts.collectAsState()
    val flows by repo.db.transactions().observeAccountFlows().collectAsState(initial = emptyList())
    val cats by main.categoriesById.collectAsState()
    val scope = rememberCoroutineScope()
    var cashId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { cashId = repo.cashAccountId() }
    val id = cashId ?: return
    val cash = accounts.firstOrNull { it.id == id } ?: return
    val balance = balanceOf(cash, flows.firstOrNull { it.accountId == id })
    val history by remember(id) { repo.db.transactions().observeForAccount(id, 200) }.collectAsState(initial = emptyList())
    val month = YearMonth.now(IST)
    val thisMonth = history.filter { it.dateMillis >= month.startMillis() && it.status == TxnStatus.POSTED.name }
    val withdrawn = thisMonth.filter { it.toAccountId == id && it.direction == "DEBIT" }.sumOf { it.amountPaise }
    val spent = thisMonth.filter { it.accountId == id && it.type == "EXPENSE" }.sumOf { it.amountPaise }
    var action by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Cash", onBack) }
        item {
            SectionCard {
                Figure("Cash in hand", Money.format(balance, showPaise = false), color = if (balance < 0) Expense.forTheme() else MaterialTheme.colorScheme.onSurface,
                    sub = if (balance < 0) "Below zero: some cash spending is recorded but the withdrawal isn't. Adjust the balance." else null)
                Spacer(Modifier.height(8.dp))
                KeyValue("Withdrawn this month", Money.format(withdrawn, showPaise = false))
                KeyValue("Spent in cash this month", Money.format(spent, showPaise = false))
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { action = "SPEND" }) { Text("Spent cash") }
                OutlinedButton(onClick = { action = "WITHDRAW" }) { Text("Withdrew") }
                OutlinedButton(onClick = { action = "DEPOSIT" }) { Text("Deposited") }
                OutlinedButton(onClick = { action = "ADJUST" }) { Text("Adjust") }
            }
        }
        item {
            Text("ATM withdrawals from bank messages move money into Cash automatically. They are transfers, not spending; only what you spend in cash counts as an expense.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text("History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
        if (history.isEmpty()) item { EmptyState("No cash transactions yet") }
        items(history, key = { it.id }) { t -> TxnRow(t.asSeenFrom(id), cats) { onOpenTxn(t.id) } }
    }

    action?.let { kind -> CashActionDialog(main, kind, cash, balance) { action = null } }
}

@Composable
private fun CashActionDialog(main: MainViewModel, kind: String, cash: AccountEntity, balance: Long, onDone: () -> Unit) {
    val accounts by main.accounts.collectAsState()
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    val scope = rememberCoroutineScope()
    val banks = accounts.filter { it.kind == "BANK" }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now(IST)) }
    var bank by remember { mutableStateOf(banks.firstOrNull()?.id) }
    var category by remember { mutableStateOf("other") }
    var picking by remember { mutableStateOf(false) }
    val title = when (kind) { "SPEND" -> "Spent cash"; "WITHDRAW" -> "Withdrew cash"; "DEPOSIT" -> "Deposited cash"; else -> "Cash in hand now" }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(if (kind == "ADJUST") "Actual cash in hand (₹)" else "Amount (₹)", amount, { amount = it })
                if (kind == "ADJUST") Text("Recorded balance: ${Money.format(balance)}. The difference is saved as an adjustment, not income or spending.", fontSize = 12.sp)
                if (kind == "SPEND") OutlinedButton(onClick = { picking = true }) { Text(categoryPath(category, byId)) }
                if (kind == "WITHDRAW" || kind == "DEPOSIT") {
                    Text(if (kind == "WITHDRAW") "From account" else "Into account", fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        banks.forEach { b -> FilterChip(bank == b.id, { bank = b.id }, label = { Text(b.name) }) }
                        FilterChip(bank == null, { bank = null }, label = { Text("Not listed") })
                    }
                }
                if (kind != "ADJUST") DateField("Date", date, { date = it })
                OutlinedTextField(note, { note = it }, label = { Text(if (kind == "SPEND") "What for (e.g. vegetables)" else "Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = Money.parseToPaise(amount) ?: if (kind == "ADJUST" && amount.trim() == "0") 0L else return@TextButton
                val d = (date ?: LocalDate.now(IST)).noonMillis()
                val now = System.currentTimeMillis()
                val t = when (kind) {
                    "SPEND" -> TransactionEntity(type = "EXPENSE", direction = "DEBIT", status = "POSTED", amountPaise = p, dateMillis = d,
                        accountId = cash.id, merchantName = note.ifBlank { null }, merchantKey = note.ifBlank { null }?.lowercase(),
                        categoryId = category, method = "CASH", createdAt = now, updatedAt = now)
                    "WITHDRAW" -> TransactionEntity(type = "TRANSFER", direction = "DEBIT", status = "POSTED", amountPaise = p, dateMillis = d,
                        accountId = bank, toAccountId = cash.id, categoryId = "transfers.cash", method = "ATM", note = note.ifBlank { "Cash withdrawal" },
                        createdAt = now, updatedAt = now)
                    "DEPOSIT" -> TransactionEntity(type = "TRANSFER", direction = "DEBIT", status = "POSTED", amountPaise = p, dateMillis = d,
                        accountId = cash.id, toAccountId = bank, categoryId = "transfers.cashdep", method = "CASH", note = note.ifBlank { "Cash deposit" },
                        createdAt = now, updatedAt = now)
                    else -> {
                        val diff = p - balance
                        if (diff == 0L) { onDone(); return@TextButton }
                        TransactionEntity(type = "TRANSFER", direction = if (diff > 0) "CREDIT" else "DEBIT", status = "POSTED",
                            amountPaise = kotlin.math.abs(diff), dateMillis = now, accountId = cash.id, categoryId = "transfers.adjust",
                            method = "CASH", note = note.ifBlank { "Cash balance adjustment" }, createdAt = now, updatedAt = now)
                    }
                }
                scope.launch { main.repo.addManual(t); onDone() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
    if (picking) com.prash.kosh.ui.components.CategoryPickerDialog(cats, setOf("EXPENSE"), onPick = { category = it.id; picking = false }, onDismiss = { picking = false })
}

/* ------------------------------------------------------------------ recognition & data settings */

@Composable
fun RecognitionSettingsScreen(onBack: () -> Unit) {
    val app = KoshApp.get(LocalContext.current)
    var auto by remember { mutableFloatStateOf(app.settings.autoSaveThreshold.toFloat()) }
    var review by remember { mutableFloatStateOf(app.settings.reviewThreshold.toFloat()) }
    var retention by remember { mutableIntStateOf(app.settings.smsRetentionMonths) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScrollCompat(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Recognition & data", onBack)
        SectionCard(title = "Confidence thresholds") {
            Text("Save automatically at ${auto.toInt()}% or more", fontWeight = FontWeight.Medium)
            Slider(auto, { auto = it; if (review > it) review = it }, valueRange = 50f..100f, steps = 49,
                onValueChangeFinished = { app.settings.autoSaveThreshold = auto.toInt(); app.settings.reviewThreshold = review.toInt() })
            Text("Full review below ${review.toInt()}%", fontWeight = FontWeight.Medium)
            Slider(review, { review = minOf(it, auto) }, valueRange = 0f..100f, steps = 99,
                onValueChangeFinished = { app.settings.reviewThreshold = review.toInt() })
            Text("Between the two, a capture waits in Review as “Check”. Nothing below the auto-save level is counted until you approve it. " +
                "Suspected duplicates always wait for you. Defaults: 95% and 80%.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { auto = 95f; review = 80f; app.settings.autoSaveThreshold = 95; app.settings.reviewThreshold = 80 }) { Text("Reset to defaults") }
        }
        SectionCard(title = "Keep SMS text for") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3, 6, 12, 0).forEach { m ->
                    FilterChip(retention == m, { retention = m; app.settings.smsRetentionMonths = m }, label = { Text(if (m == 0) "Forever" else "$m months") })
                }
            }
            Text("After this, the text of imported messages is erased; the transactions themselves stay. Applied during the monthly tidy-up while charging.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.verticalScroll(rememberScrollState())

/* ------------------------------------------------------------------ reports */

private val REPORTS = listOf(
    "monthly" to "Monthly expenses", "annual" to "Annual expenses (FY)", "category" to "Category-wise spending",
    "merchant" to "Merchant-wise spending", "income" to "Income history", "cash" to "Cash transactions",
    "transfers" to "Account transfers", "lic" to "LIC & insurance premiums", "bonds" to "Bond instalments",
)

@Composable
fun ReportsScreen(main: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val byId by main.categoriesById.collectAsState()
    val accounts by main.accounts.collectAsState()
    var report by remember { mutableStateOf("monthly") }
    var fy by remember { mutableIntStateOf(com.prash.kosh.engine.Metrics.fyStartYear(LocalDate.now(IST))) }
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var header by remember { mutableStateOf<List<String>>(emptyList()) }
    val months = (0..11).map { YearMonth.of(fy, 4).plusMonths(it.toLong()) }
    val from = months.first().startMillis(); val to = months.last().endMillis()

    LaunchedEffect(report, fy, byId, accounts) {
        val repo = main.repo
        val txns = repo.db.transactions().countedBetween(from, to)
        val splitsByTxn = repo.db.extras().splitsFor(txns.map { it.id }).groupBy { it.transactionId }
        fun lines(t: TransactionEntity) = splitsByTxn[t.id]?.map { it.categoryId to it.amountPaise } ?: listOf((t.categoryId ?: "other") to t.amountPaise)
        fun ym(t: TransactionEntity) = YearMonth.from(java.time.Instant.ofEpochMilli(t.dateMillis).atZone(IST))
        val accName = accounts.associate { it.id to it.name }
        val cashIds = accounts.filter { it.kind == "CASH" }.map { it.id }.toSet()
        val inv = repo.db.investments().all().associateBy { it.id }
        val entries = repo.db.investments().allEntries().filter { it.dateMillis in from..to }
        when (report) {
            "monthly", "annual" -> {
                header = listOf("Month", "Spent", "Refunds", "Net")
                rows = months.map { m ->
                    val e = txns.filter { ym(it) == m && it.type == "EXPENSE" }.sumOf { it.amountPaise }
                    val r = txns.filter { ym(it) == m && it.type == "REFUND" }.sumOf { it.amountPaise }
                    listOf(m.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy")), Money.format(e, showPaise = false), Money.format(r, showPaise = false), Money.format(e - r, showPaise = false))
                } + listOf(listOf("Total", Money.format(txns.filter { it.type == "EXPENSE" }.sumOf { it.amountPaise }, showPaise = false),
                    Money.format(txns.filter { it.type == "REFUND" }.sumOf { it.amountPaise }, showPaise = false),
                    Money.format(txns.filter { it.type == "EXPENSE" }.sumOf { it.amountPaise } - txns.filter { it.type == "REFUND" }.sumOf { it.amountPaise }, showPaise = false)))
                if (report == "monthly") rows = rows.filter { r -> r[1] != "₹0" || r[0] == "Total" }
            }
            "category" -> {
                header = listOf("Category", "Spent", "Share")
                val all = txns.filter { it.type == "EXPENSE" }.flatMap(::lines)
                val total = all.sumOf { it.second }
                rows = all.groupBy { categoryPath(it.first, byId) }.map { (k, v) -> k to v.sumOf { it.second } }.sortedByDescending { it.second }
                    .map { (k, v) -> listOf(k, Money.format(v, showPaise = false), "${com.prash.kosh.engine.Metrics.percent(v, total) ?: 0}%") }
            }
            "merchant" -> {
                header = listOf("Merchant", "Payments", "Spent")
                rows = txns.filter { it.type == "EXPENSE" }.groupBy { it.merchantName ?: "Unknown" }
                    .map { (k, v) -> Triple(k, v.size, v.sumOf { it.amountPaise }) }.sortedByDescending { it.third }
                    .map { listOf(it.first, it.second.toString(), Money.format(it.third, showPaise = false)) }
            }
            "income" -> {
                header = listOf("Date", "From", "Category", "Amount")
                rows = txns.filter { it.type == "INCOME" }.sortedByDescending { it.dateMillis }
                    .map { listOf(formatDay(it.dateMillis), it.merchantName ?: "—", categoryPath(it.categoryId, byId), Money.format(it.amountPaise)) }
            }
            "cash" -> {
                header = listOf("Date", "What", "In", "Out")
                rows = txns.filter { it.accountId in cashIds || it.toAccountId in cashIds }.sortedByDescending { it.dateMillis }.map { t ->
                    val into = (t.toAccountId in cashIds && t.direction == "DEBIT") || (t.accountId in cashIds && t.direction == "CREDIT")
                    listOf(formatDay(t.dateMillis), t.merchantName ?: t.note ?: categoryPath(t.categoryId, byId),
                        if (into) Money.format(t.amountPaise) else "", if (!into) Money.format(t.amountPaise) else "")
                }
            }
            "transfers" -> {
                header = listOf("Date", "From", "To", "Amount")
                rows = txns.filter { it.type == "TRANSFER" }.sortedByDescending { it.dateMillis }.map { t ->
                    val a = t.accountId?.let { accName[it] } ?: "—"; val b = t.toAccountId?.let { accName[it] } ?: (t.merchantName ?: "—")
                    if (t.direction == "DEBIT") listOf(formatDay(t.dateMillis), a, b, Money.format(t.amountPaise))
                    else listOf(formatDay(t.dateMillis), b, a, Money.format(t.amountPaise))
                }
            }
            else -> {
                val kinds = if (report == "lic") setOf("LIC", "INSURANCE") else setOf("BOND")
                header = listOf("Date", "Policy / bond", "Type", "Amount")
                rows = entries.filter { inv[it.investmentId]?.kind in kinds }.sortedByDescending { it.dateMillis }.map { e ->
                    listOf(formatDay(e.dateMillis), inv[e.investmentId]?.name ?: "—",
                        when (e.kind) { "OPENING" -> "Paid before tracking"; "CONTRIBUTION" -> "Payment"; "WITHDRAWAL" -> "Withdrawal"; else -> "Maturity" },
                        Money.format(e.amountPaise))
                }
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val csv = (listOf(header) + rows).joinToString("\n") { r -> r.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } }
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
            Toast.makeText(ctx, "Exported", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Reports", onBack) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                REPORTS.forEach { (k, l) -> FilterChip(report == k, { report = k }, label = { Text(l) }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { fy-- }) { Text("‹") }
                Text("FY $fy-${(fy + 1) % 100} (Apr–Mar)", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { fy++ }, enabled = fy < com.prash.kosh.engine.Metrics.fyStartYear(LocalDate.now(IST))) { Text("›") }
            }
        }
        item {
            SectionCard {
                if (rows.isEmpty()) Text("Nothing to show for this year.", fontSize = 13.sp)
                else {
                    Row { header.forEachIndexed { i, h -> Text(h, Modifier.weight(if (i == 1 && header.size == 4) 1.4f else 1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = if (i == 0) TextAlign.Start else TextAlign.End) } }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    rows.take(300).forEach { r ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            r.forEachIndexed { i, c -> Text(c, Modifier.weight(if (i == 1 && header.size == 4) 1.4f else 1f), fontSize = 12.sp, maxLines = 2,
                                textAlign = if (i == 0) TextAlign.Start else TextAlign.End, fontWeight = if (r.first() == "Total") FontWeight.SemiBold else FontWeight.Normal) }
                        }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { export.launch("kosh-$report-FY$fy.csv") }, enabled = rows.isNotEmpty()) { Text("Export CSV") } }
        item { Spacer(Modifier.width(1.dp).height(24.dp)) }
    }
}
