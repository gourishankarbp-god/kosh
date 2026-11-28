package com.prash.kosh.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.data.TaughtPatternEntity
import com.prash.kosh.data.toPattern
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.Direction
import com.prash.kosh.engine.Money
import com.prash.kosh.engine.RawInput
import com.prash.kosh.engine.Taught
import com.prash.kosh.engine.TxnType
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.CategoryPickerDialog
import com.prash.kosh.ui.components.EmptyState
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.categoryPath
import com.prash.kosh.ui.components.formatDay
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ list of taught formats */

@Composable
fun TaughtListScreen(main: MainViewModel, onBack: () -> Unit, onTeachNew: () -> Unit) {
    val items by main.repo.db.taught().observeAll().collectAsState(initial = emptyList())
    val byId by main.categoriesById.collectAsState()
    val accounts by main.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<TaughtPatternEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("Taught messages", onBack) }
        item {
            Text("Message types you taught Kosh to read, such as salary or OPD reimbursement. New messages that match go to Check so you can confirm them. " +
                "To teach one, open Ignored messages or a transaction's source message and tap Teach Kosh, or paste a message here.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onTeachNew, modifier = Modifier.padding(vertical = 8.dp)) { Text("Teach from a pasted message") }
        }
        if (items.isEmpty()) item { EmptyState("Nothing taught yet") }
        items(items, key = { it.id }) { p ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text("${if (p.direction == "CREDIT") "Money in" else "Money out"} · ${categoryPath(p.categoryId, byId)}" +
                            (p.accountId?.let { id -> accounts.firstOrNull { it.id == id }?.let { " · ${it.name}" } } ?: ""),
                            fontSize = 13.sp)
                    }
                    TextButton(onClick = { deleting = p }) { Text("Delete") }
                }
                Text("Words: ${p.keywordList().joinToString(", ")}" + (p.sender?.let { " · only from $it" } ?: " · any sender"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (p.matchCount == 0) "No matches yet" else "Matched ${p.matchCount} time${if (p.matchCount == 1) "" else "s"}" +
                    (p.lastMatchedAt?.let { ", last on ${formatDay(it)}" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.sample, fontSize = 11.sp, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Forget “${p.name}”?") },
            text = { Text("Kosh will stop recognising this message type. Transactions it already created stay as they are.") },
            confirmButton = { TextButton(onClick = { scope.launch { main.repo.deleteTaught(p.id) }; deleting = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/* ------------------------------------------------------------------ teach one message */

/**
 * Teach Kosh a message: from a captured message ([rawId] > 0) or a pasted one ([rawId] = 0).
 * The owner picks the amount, money in/out, what it is, category, account and the words
 * that identify the message type.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeachScreen(main: MainViewModel, rawId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    val accounts by main.accounts.collectAsState()

    var loaded by remember { mutableStateOf(rawId == 0L) }
    var body by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(CaptureSource.SHARED) }
    var linkedToTxn by remember { mutableStateOf(false) }
    LaunchedEffect(rawId) {
        if (rawId > 0) main.repo.db.rawMessages().get(rawId)?.let {
            body = it.body; sender = it.sender; linkedToTxn = it.transactionId != null
            source = runCatching { CaptureSource.valueOf(it.source) }.getOrDefault(CaptureSource.SMS)
        }
        loaded = true
    }

    val amounts = remember(body) { Taught.amounts(body) }
    val words = remember(body) { Taught.candidateWords(body) }
    val picked = remember(body) { mutableStateListOf<String>().apply { addAll(words.filter { it in Taught.hintWords }) } }
    var amountIndex by remember(body) { mutableStateOf(0) }
    val parsedDirection = remember(body) {
        if (Regex("""\b(?:credited|received|deposited|cr)\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)) Direction.CREDIT else Direction.DEBIT
    }
    var direction by remember(body) { mutableStateOf(parsedDirection) }
    var type by remember(body) { mutableStateOf(if (parsedDirection == Direction.CREDIT) TxnType.INCOME else TxnType.EXPENSE) }
    var categoryId by remember(body) { mutableStateOf(com.prash.kosh.engine.SeedData.FALLBACK_INCOME) }
    var categoryTouched by remember(body) { mutableStateOf(false) }
    var name by remember(body) { mutableStateOf("") }
    var nameTouched by remember(body) { mutableStateOf(false) }
    var accountId by remember(body) { mutableStateOf<Long?>(null) }
    var onlySender by remember(body) { mutableStateOf(true) }
    var pickingCategory by remember { mutableStateOf(false) }
    var pickingAccount by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Suggestions follow the ticked words until the owner sets them by hand.
    val suggestedCategory = when (type) {
        TxnType.TRANSFER -> "transfers"
        TxnType.INVESTMENT -> "investments"
        else -> Taught.suggestCategory(picked, direction)
    }
    val cat = if (categoryTouched) categoryId else suggestedCategory
    val shownName = if (nameTouched) name else byId[cat]?.name ?: if (direction == Direction.CREDIT) "Income" else "Payment"

    val senderCode = Taught.senderCode(sender).takeIf { sender.isNotBlank() }
    val pattern = TaughtPatternEntity(
        name = shownName.trim().ifBlank { "Taught message" },
        sender = if (onlySender) senderCode else null,
        keywords = picked.joinToString("|"),
        direction = direction.name, type = type.name, categoryId = cat,
        amountIndex = amountIndex, accountId = accountId,
        sample = body.take(500), createdAt = System.currentTimeMillis(),
    )
    val input = RawInput(source, sender.ifBlank { "PASTED" }, body, System.currentTimeMillis())
    val matchesItself = picked.isNotEmpty() && Taught.matches(pattern.toPattern(), input)
    val kindsForType = when (type) {
        TxnType.INCOME, TxnType.REFUND -> setOf("INCOME")
        TxnType.EXPENSE -> setOf("EXPENSE")
        TxnType.TRANSFER -> setOf("TRANSFER")
        TxnType.INVESTMENT -> setOf("INVESTMENT")
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Teach Kosh a message", onBack) }
        item {
            Text("Show Kosh one example and it will read every future message like it. Matches go to Check so you can confirm them.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard(title = "1 · The message") {
                if (rawId == 0L) {
                    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Paste the SMS text") }, minLines = 4)
                    OutlinedTextField(sender, { sender = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
                        label = { Text("Sender (optional, e.g. VM-HDFCBK-S)") })
                } else if (loaded) {
                    Text(sender, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(body, fontSize = 13.sp)
                }
            }
        }
        if (body.isNotBlank()) {
            item {
                SectionCard(title = "2 · Which amount is it?") {
                    if (amounts.isEmpty()) Text("Kosh can't find an amount written with Rs, INR or ₹ in this message, so it can't learn it.",
                        color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        amounts.forEachIndexed { i, a ->
                            FilterChip(amountIndex == i, { amountIndex = i }, label = { Text(Money.format(a.paise)) })
                        }
                    }
                    if (amounts.size > 1) Text("Pick the transaction amount, not the balance.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = "3 · What is it?") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(direction == Direction.CREDIT, { direction = Direction.CREDIT; categoryTouched = false; if (type != TxnType.TRANSFER) type = TxnType.INCOME }, label = { Text("Money in") })
                        FilterChip(direction == Direction.DEBIT, { direction = Direction.DEBIT; categoryTouched = false; if (type != TxnType.TRANSFER) type = TxnType.EXPENSE }, label = { Text("Money out") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val options = if (direction == Direction.CREDIT) listOf(TxnType.INCOME to "Income", TxnType.TRANSFER to "Transfer")
                            else listOf(TxnType.EXPENSE to "Expense", TxnType.TRANSFER to "Transfer", TxnType.INVESTMENT to "Investment")
                        options.forEach { (t, label) ->
                            FilterChip(type == t, { type = t; categoryTouched = false }, label = { Text(label) })
                        }
                    }
                    OutlinedButton(onClick = { pickingCategory = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Category: " + categoryPath(cat, byId)) }
                    OutlinedTextField(shownName, { name = it; nameTouched = true }, Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true,
                        label = { Text("Show as (e.g. Salary, OPD reimbursement)") })
                    OutlinedButton(onClick = { pickingAccount = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Account: " + (accountId?.let { id -> accounts.firstOrNull { it.id == id }?.name } ?: "the one named in the message"))
                    }
                }
            }
            item {
                SectionCard(title = "4 · Words that identify this message") {
                    Text("Tick words that appear in every message of this type (like SALARY or OPD), not words that change each time (like names or dates).",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        words.forEach { w ->
                            val on = w in picked
                            FilterChip(on, { if (on) picked.remove(w) else picked.add(w) }, label = { Text(w) })
                        }
                    }
                    if (senderCode != null) Row(Modifier.fillMaxWidth().clickable { onlySender = !onlySender }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(onlySender, { onlySender = it })
                        Text("Only messages from $senderCode", fontSize = 13.sp)
                    }
                    Text(
                        when {
                            picked.isEmpty() -> "Tick at least one word."
                            !matchesItself -> "These words don't all appear in the message."
                            else -> "✓ This message matches: ${picked.joinToString(" + ")}${if (onlySender && senderCode != null) " from $senderCode" else ""}"
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (matchesItself) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Button(
                    enabled = !saving && matchesItself && amounts.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        saving = true
                        scope.launch {
                            val result = main.repo.teach(pattern, rawId.takeIf { it > 0 })
                            // A pasted message is also added (it lands in Check), unless Kosh already had it.
                            val pasted = if (rawId == 0L) main.repo.ingest(input) else null
                            val parts = buildList {
                                if (result.updated > 0) add("this transaction updated")
                                if (result.readNow > 0) add("${result.readNow} earlier message${if (result.readNow == 1) "" else "s"} moved to Check")
                                if (pasted == com.prash.kosh.data.IngestOutcome.PENDING) add("this message added to Check")
                            }
                            Toast.makeText(ctx, "Saved." + if (parts.isEmpty()) " Kosh will read new messages like this." else " " + parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ".",
                                Toast.LENGTH_LONG).show()
                            onBack()
                        }
                    },
                ) { Text("Save and use from now on") }
                if (linkedToTxn) Text("This message already has a transaction. Saving also changes that transaction to what you chose here.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    if (pickingCategory) CategoryPickerDialog(cats, kindsForType, onPick = { categoryId = it.id; categoryTouched = true; pickingCategory = false },
        onDismiss = { pickingCategory = false })
    if (pickingAccount) AlertDialog(
        onDismissRequest = { pickingAccount = false },
        title = { Text("Account") },
        text = {
            LazyColumn {
                item { Text("The one named in the message", Modifier.fillMaxWidth().clickable { accountId = null; pickingAccount = false }.padding(vertical = 10.dp)) }
                items(accounts.filter { !it.archived }, key = { it.id }) { a ->
                    Text(a.name, Modifier.fillMaxWidth().clickable { accountId = a.id; pickingAccount = false }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickingAccount = false }) { Text("Cancel") } },
    )
}
