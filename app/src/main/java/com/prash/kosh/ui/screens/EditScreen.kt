package com.prash.kosh.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.data.linkTransactionToInvestment
import com.prash.kosh.data.makeRecurringFrom
import com.prash.kosh.engine.MerchantNormalizer
import com.prash.kosh.engine.Money
import com.prash.kosh.engine.SeedData
import com.prash.kosh.engine.TxnStatus
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.CategoryPickerDialog
import com.prash.kosh.ui.components.IST
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.categoryPath
import com.prash.kosh.ui.components.formatDay
import com.prash.kosh.ui.components.formatDayTime
import com.prash.kosh.ui.components.statusLabel
import com.prash.kosh.ui.components.typeLabel
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private val TYPES = listOf("EXPENSE", "INCOME", "TRANSFER", "INVESTMENT", "REFUND")

private fun kindsFor(type: String) = when (type) {
    "EXPENSE" -> setOf("EXPENSE")
    "INCOME", "REFUND" -> setOf("INCOME")
    "TRANSFER" -> setOf("TRANSFER")
    else -> setOf("INVESTMENT")
}

private fun defaultCategory(type: String) = when (type) {
    "EXPENSE" -> SeedData.FALLBACK_EXPENSE
    "INCOME" -> SeedData.FALLBACK_INCOME
    "REFUND" -> SeedData.REFUND_CATEGORY
    "TRANSFER" -> SeedData.TRANSFER_CATEGORY
    else -> SeedData.INVESTMENT_CATEGORY
}

/** Add (txnId == null) or edit a transaction. Every save of an existing one is audited. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionScreen(main: MainViewModel, txnId: Long?, onDone: () -> Unit) {
    val repo = main.repo
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val categories by main.categories.collectAsState()
    val catById by main.categoriesById.collectAsState()
    val accounts by main.accounts.collectAsState()

    var original by remember { mutableStateOf<TransactionEntity?>(null) }
    var loaded by remember { mutableStateOf(txnId == null) }
    var amount by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("EXPENSE") }
    var merchant by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>(SeedData.FALLBACK_EXPENSE) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var toAccountId by remember { mutableStateOf<Long?>(null) }
    var showToAcct by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var note by remember { mutableStateOf("") }
    var showCat by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showAcct by remember { mutableStateOf(false) }
    var learnPrompt by remember { mutableStateOf<TransactionEntity?>(null) }

    LaunchedEffect(txnId) {
        if (txnId != null) repo.db.transactions().get(txnId)?.let { t ->
            original = t
            amount = BigDecimal(t.amountPaise).movePointLeft(2).stripTrailingZeros().toPlainString()
            type = t.type; merchant = t.merchantName.orEmpty(); categoryId = t.categoryId
            accountId = t.accountId; toAccountId = t.toAccountId; dateMillis = t.dateMillis; note = t.note.orEmpty()
        }
        loaded = true
    }
    if (!loaded) return

    fun build(): TransactionEntity? {
        val paise = Money.parseToPaise(amount) ?: return null
        val direction = if (type == "INCOME" || type == "REFUND") "CREDIT" else original?.direction?.takeIf { type == "TRANSFER" } ?: "DEBIT"
        val key = merchant.takeIf { it.isNotBlank() }?.let { m ->
            original?.merchantKey?.takeIf { original?.merchantName == m } ?: MerchantNormalizer.cleanWords(m)
        }
        val now = System.currentTimeMillis()
        val acct = accounts.firstOrNull { it.id == accountId }
        return (original ?: TransactionEntity(type = type, direction = direction, status = TxnStatus.POSTED.name, amountPaise = paise,
            dateMillis = dateMillis, createdAt = now, updatedAt = now)).copy(
            type = type, direction = direction, amountPaise = paise, dateMillis = dateMillis,
            merchantKey = key, merchantName = merchant.ifBlank { null }, categoryId = categoryId,
            accountId = accountId, accountLast4 = acct?.last4 ?: original?.accountLast4, note = note.ifBlank { null },
            toAccountId = if (type == "TRANSFER") toAccountId else null,
        )
    }

    fun save() {
        val t = build()
        if (t == null) { Toast.makeText(ctx, "Enter an amount above zero", Toast.LENGTH_SHORT).show(); return }
        scope.launch {
            if (original == null) repo.addManual(t) else repo.saveEdit(t)
            val categoryChanged = original != null && original!!.categoryId != t.categoryId
            val newWithCategory = original == null && t.categoryId != defaultCategory(t.type)
            if (t.merchantKey != null && t.categoryId != null && (categoryChanged || newWithCategory)) learnPrompt = t else onDone()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(if (original == null) "Add transaction" else "Edit transaction", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        original?.let { o ->
            if (o.status != TxnStatus.POSTED.name) Text("Status: ${statusLabel(o.status)} · confidence ${o.confidence}%. Saving approves it.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(),
            label = { Text("Amount (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TYPES.forEachIndexed { i, t ->
                SegmentedButton(selected = type == t, onClick = {
                    if (type != t) { type = t; if (categoryId == null || catById[categoryId!!]?.kind !in kindsFor(t)) categoryId = defaultCategory(t) }
                }, shape = SegmentedButtonDefaults.itemShape(i, TYPES.size)) { Text(typeLabel(t), fontSize = 11.sp, maxLines = 1) }
            }
        }
        OutlinedTextField(merchant, { merchant = it }, Modifier.fillMaxWidth(), label = { Text("Merchant or payee") }, singleLine = true)
        Field("Category", categoryPath(categoryId, catById)) { showCat = true }
        Field("Date", formatDay(dateMillis)) { showDate = true }
        Field("Account", accounts.firstOrNull { it.id == accountId }?.name ?: "None") { showAcct = true }
        DropdownMenu(expanded = showAcct, onDismissRequest = { showAcct = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { accountId = null; showAcct = false })
            accounts.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { accountId = a.id; showAcct = false }) }
        }
        if (type == "TRANSFER") {
            Field("To / from account", accounts.firstOrNull { it.id == toAccountId }?.name ?: "Outside Kosh") { showToAcct = true }
            DropdownMenu(expanded = showToAcct, onDismissRequest = { showToAcct = false }) {
                DropdownMenuItem(text = { Text("Outside Kosh") }, onClick = { toAccountId = null; showToAcct = false })
                accounts.filter { it.id != accountId }.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { toAccountId = a.id; showToAcct = false }) }
            }
            Text("Transfers between your own accounts, card bill payments and ATM cash are never counted as income or spending.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::save) { Text("Save") }
            if (original != null && original!!.status != TxnStatus.REJECTED.name) OutlinedButton(onClick = {
                scope.launch { repo.setStatus(original!!.id, TxnStatus.REJECTED); onDone() }
            }) { Text("Reject") }
            if (original != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        original?.let { o -> if (o.type == "EXPENSE") SplitSection(main, o) }
        original?.let { o -> PlanActions(main, o) }
        original?.let { HistorySection(main, it) }
        Spacer(Modifier.height(40.dp))
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this transaction?") },
        text = { Text("It is removed from all totals and from your other devices. Its history entry is kept. Use Reject instead if you might want it back.") },
        confirmButton = { TextButton(onClick = { scope.launch { repo.deleteTransaction(original!!.id); confirmDelete = false; onDone() } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )

    if (showCat) CategoryPickerDialog(categories, kindsFor(type), onPick = { categoryId = it.id; showCat = false }, onDismiss = { showCat = false })

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = Instant.ofEpochMilli(dateMillis).atZone(IST).toLocalDate()
            .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { utc ->
                    val d = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                    val time = if (d == LocalDate.now(IST)) LocalTime.now(IST) else LocalTime.NOON
                    dateMillis = d.atTime(time).atZone(IST).toInstant().toEpochMilli()
                }
                showDate = false
            }) { Text("OK") }
        }) { DatePicker(state) }
    }

    learnPrompt?.let { t ->
        var applyPast by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { learnPrompt = null; onDone() },
            title = { Text("Remember this?") },
            text = {
                Column {
                    Text("Always put ${t.merchantName} in ${categoryPath(t.categoryId, catById)}?")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { applyPast = !applyPast }) {
                        Checkbox(applyPast, { applyPast = it })
                        Text("Also update its past transactions that are in Other", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val n = repo.rememberMerchantCategory(t.merchantKey!!, t.merchantName ?: t.merchantKey, t.categoryId!!, applyPast)
                        if (n > 0) Toast.makeText(ctx, "Updated $n past transaction${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                        learnPrompt = null; onDone()
                    }
                }) { Text("Always") }
            },
            dismissButton = { TextButton(onClick = { learnPrompt = null; onDone() }) { Text("Just this once") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, onClick: () -> Unit) {
    OutlinedTextField(value, {}, Modifier.fillMaxWidth().clickable(onClick = onClick), label = { Text(label) }, readOnly = true, enabled = false,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledBorderColor = MaterialTheme.colorScheme.outline,
        ))
}

@Composable
private fun HistorySection(main: MainViewModel, t: TransactionEntity) {
    val raw by main.repo.db.rawMessages().observeForTxn(t.id).collectAsState(initial = emptyList())
    val audit by main.repo.db.audit().observeForTxn(t.id).collectAsState(initial = emptyList())
    SectionCard(title = "Source messages") {
        if (raw.isEmpty()) Text("Entered by hand", fontSize = 13.sp)
        raw.forEach {
            Text("${it.source} · ${it.sender} · ${formatDayTime(it.receivedAt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(it.body, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        t.reference?.let { Text("Reference: $it", fontSize = 12.sp) }
    }
    SectionCard(title = "History") {
        audit.forEach { Text("${formatDayTime(it.at)} · ${it.action.lowercase().replace('_', ' ')}", fontSize = 12.sp) }
    }
}

/** "Make recurring" and "Link to investment" for an existing transaction. */
@Composable
private fun PlanActions(main: MainViewModel, t: TransactionEntity) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var askFreq by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    val investments by main.repo.db.investments().observeAll().collectAsState(initial = emptyList())
    val unlinked by main.repo.db.transactions().observeUnlinkedInvestments().collectAsState(initial = emptyList())
    val canRecur = t.recurringId == null && t.type in setOf("EXPENSE", "INCOME", "TRANSFER") && t.status != TxnStatus.REJECTED.name
    val canLink = t.type == "INVESTMENT" && unlinked.any { it.id == t.id }
    if (!canRecur && !canLink && t.recurringId == null) return
    SectionCard(title = "Plan") {
        if (t.recurringId != null) Text("Part of a recurring payment. Manage it in Plan › Upcoming payments.", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRecur) OutlinedButton(onClick = { askFreq = true }) { Text("Make recurring") }
            if (canLink) OutlinedButton(onClick = { linking = true }) { Text("Link to investment") }
        }
    }
    if (askFreq) AlertDialog(
        onDismissRequest = { askFreq = false },
        title = { Text("How often does this repeat?") },
        text = {
            Column {
                listOf(com.prash.kosh.engine.Frequency.MONTHLY, com.prash.kosh.engine.Frequency.QUARTERLY, com.prash.kosh.engine.Frequency.YEARLY).forEach { f ->
                    Text(f.label, Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            main.repo.makeRecurringFrom(t, f)
                            Toast.makeText(ctx, "Added to Upcoming payments", Toast.LENGTH_SHORT).show()
                            askFreq = false
                        }
                    }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { askFreq = false }) { Text("Cancel") } },
    )
    if (linking) AlertDialog(
        onDismissRequest = { linking = false },
        title = { Text("Link to…") },
        text = {
            Column {
                val active = investments.filter { it.status == "ACTIVE" }
                if (active.isEmpty()) Text("No investments yet. Add one in Plan › Investments.")
                active.forEach { i ->
                    Text(i.name, Modifier.fillMaxWidth().clickable { scope.launch { main.repo.linkTransactionToInvestment(t, i.id); linking = false } }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { linking = false }) { Text("Cancel") } },
    )
}

/** Split one payment across several categories (e.g. a supermarket bill: groceries + household). */
@Composable
private fun SplitSection(main: MainViewModel, t: TransactionEntity) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val saved by remember(t.id) { main.repo.db.extras().observeSplits(t.id) }.collectAsState(initial = emptyList())
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    var editing by remember { mutableStateOf(false) }
    SectionCard(title = "Split") {
        if (saved.isEmpty()) Text("Not split. Split it to count parts under different categories.", fontSize = 13.sp)
        saved.forEach { Row { Text(categoryPath(it.categoryId, byId), Modifier.weight(1f), fontSize = 13.sp); Text(Money.format(it.amountPaise), fontSize = 13.sp) } }
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.padding(top = 6.dp)) { Text(if (saved.isEmpty()) "Split" else "Change split") }
    }
    if (editing) {
        val lines = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().apply {
            if (saved.isEmpty()) { add((t.categoryId ?: "other") to paiseText2(t.amountPaise)); add("other" to "") }
            else saved.forEach { add(it.categoryId to paiseText2(it.amountPaise)) }
        } }
        var picking by remember { mutableStateOf(-1) }
        val total = lines.sumOf { Money.parseToPaise(it.second) ?: 0 }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Split ${Money.format(t.amountPaise)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEachIndexed { i, (cat, amt) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { picking = i }, modifier = Modifier.weight(1f)) { Text(categoryPath(cat, byId), maxLines = 1, fontSize = 12.sp) }
                            OutlinedTextField(amt, { v -> lines[i] = cat to v.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(0.8f).padding(start = 6.dp),
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        }
                    }
                    TextButton(onClick = { lines.add("other" to "") }) { Text("+ Add part") }
                    val left = t.amountPaise - total
                    Text(if (left == 0L) "Adds up." else "Remaining: ${Money.format(left)}", fontSize = 13.sp,
                        color = if (left == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = total == t.amountPaise, onClick = {
                    scope.launch {
                        val parts = lines.mapNotNull { (c, a) -> Money.parseToPaise(a)?.let { com.prash.kosh.data.SplitEntity(transactionId = t.id, categoryId = c, amountPaise = it) } }
                        if (main.repo.saveSplits(t.id, parts)) editing = false else Toast.makeText(ctx, "Parts must add up to the total", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { scope.launch { main.repo.saveSplits(t.id, emptyList()); editing = false } }) { Text("Remove split") }
            },
        )
        if (picking >= 0) CategoryPickerDialog(cats, setOf("EXPENSE"), onPick = { c -> lines[picking] = c.id to lines[picking].second; picking = -1 }, onDismiss = { picking = -1 })
    }
}

private fun paiseText2(p: Long): String = BigDecimal(p).movePointLeft(2).stripTrailingZeros().toPlainString()
