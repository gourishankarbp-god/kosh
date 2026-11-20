package com.prash.kosh.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.KoshApp
import com.prash.kosh.ai.AiAgents
import com.prash.kosh.ai.AiSuggestion
import com.prash.kosh.data.ArchiveIndexEntity
import com.prash.kosh.data.IngestOutcome
import com.prash.kosh.engine.CaptureSource
import com.prash.kosh.engine.Money
import com.prash.kosh.engine.RawInput
import com.prash.kosh.engine.StatementParser
import com.prash.kosh.sync.GoogleAuth
import com.prash.kosh.sync.SyncCrypto
import com.prash.kosh.sync.SyncEngine
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.EmptyState
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.formatDay
import com.prash.kosh.ui.components.formatDayTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.YearMonth

private fun errText(e: Throwable) = when (e) {
    is SyncCrypto.WrongPassphrase -> "That passphrase does not match the one used on your other device."
    is com.prash.kosh.sync.AuthRequired -> "Google sign-in is needed. Tap Connect Google Drive."
    is java.net.UnknownHostException -> "No internet connection."
    else -> e.message ?: e.javaClass.simpleName
}

/* ------------------------------------------------------------------ sync */

@Composable
fun SyncScreen(main: MainViewModel, onBack: () -> Unit, onOpenArchive: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = KoshApp.get(ctx)
    val scope = rememberCoroutineScope()
    val status by app.sync.status.collectAsState()
    val archive by app.db.extras().observeArchive().collectAsState(initial = emptyList())
    val pending by app.db.sync().observeOutboxCount().collectAsState(initial = 0)
    var askPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmOff by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val token = GoogleAuth.tokenFromResult(ctx as Activity, r.data)
        if (token != null) { app.sync.useToken(token); askPass = true } else message = "Google access was not granted."
    }

    fun connect() {
        scope.launch {
            busy = true
            try {
                val (token, intent) = GoogleAuth.begin(ctx as Activity)
                if (token != null) { app.sync.useToken(token); askPass = true }
                else intent?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
            } catch (e: Exception) { message = "Could not start Google sign-in: ${errText(e)}" }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Sync & archive", onBack) }
        item {
            SectionCard(title = "Google Drive sync") {
                Text("Keeps this phone and the Kosh web app in step through a hidden Kosh folder in your Google Drive. " +
                    "Everything is encrypted on the device with your sync passphrase before upload; Google cannot read it, and Kosh cannot see the rest of your Drive.", fontSize = 13.sp)
                if (!status.enabled) {
                    Button(onClick = ::connect, enabled = !busy, modifier = Modifier.padding(top = 10.dp)) { Text("Connect Google Drive") }
                } else {
                    Text(if (status.running) "Syncing…" else "Last synced: " + (if (status.lastSyncAt > 0) formatDayTime(status.lastSyncAt) else "never"),
                        Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
                    if (pending > 0) Text("$pending local change(s) waiting to upload", fontSize = 13.sp)
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !status.running, onClick = {
                            scope.launch {
                                try { val r = withContext(Dispatchers.IO) { app.sync.syncNow() }; message = "Sent ${r?.pushed ?: 0}, received ${r?.pulled ?: 0} change(s)." }
                                catch (e: com.prash.kosh.sync.AuthRequired) { connect() }
                                catch (e: Exception) { message = errText(e) }
                            }
                        }) { Text("Sync now") }
                        OutlinedButton(onClick = { confirmOff = true }) { Text("Turn off") }
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp))
                message?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp) }
            }
        }
        item {
            SectionCard(title = "Battery") {
                Text("Uploads happen once, about two minutes after your last change, and only with internet. " +
                    "New data from your other devices is fetched when you open Kosh and every 6 hours when the battery is not low. " +
                    "Archiving and clean-up run weekly, only while charging.", fontSize = 13.sp)
            }
        }
        item {
            SectionCard(title = "Older than 12 months") {
                Text("Kosh keeps the last 12 months on this phone. Older months move to your Drive (encrypted) while charging; " +
                    "their totals stay here, so yearly insights still work. Open a month to fetch its transactions from Drive.", fontSize = 13.sp)
                if (status.enabled) OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = {
                    scope.launch {
                        busy = true
                        message = try { val m = withContext(Dispatchers.IO) { app.sync.archive() }; if (m.isEmpty()) "Nothing older than 12 months." else "Archived ${m.joinToString()}." }
                        catch (e: Exception) { errText(e) }
                        busy = false
                    }
                }) { Text("Archive now") }
            }
        }
        if (archive.isEmpty()) item { EmptyState("No archived months yet.") }
        items(archive, key = { it.month }) { a -> ArchiveRow(a) { onOpenArchive(a.month) } }
    }

    if (askPass) PassphraseDialog(onDismiss = { askPass = false }) { pass ->
        askPass = false
        scope.launch {
            busy = true
            message = try {
                when (withContext(Dispatchers.IO) { app.sync.setup(pass) }) {
                    SyncEngine.SetupResult.CreatedNew -> "Sync is on. Use the same passphrase in the web app."
                    SyncEngine.SetupResult.JoinedExisting -> "Connected to your existing Kosh data."
                }
            } catch (e: Exception) { errText(e) }
            busy = false
        }
    }
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false },
        title = { Text("Turn off sync on this phone?") },
        text = { Text("Your data stays on this phone and in Drive. You can connect again later with the same passphrase.") },
        confirmButton = { TextButton({ confirmOff = false; scope.launch { app.sync.disconnect(); message = "Sync is off." } }) { Text("Turn off") } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } })
}

@Composable
private fun ArchiveRow(a: ArchiveIndexEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(YearMonth.parse(a.month).let { "${it.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.year}" }, fontWeight = FontWeight.Medium)
            Text("${a.transactionCount} transactions · in ${Money.compact(a.income)} · out ${Money.compact(a.expense)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("View", color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider()
}

@Composable
fun PassphraseDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Sync passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("First device: choose a passphrase (at least 10 characters). Other devices: enter the same one. " +
                    "It is never uploaded and cannot be recovered — write it down somewhere safe.", fontSize = 13.sp)
                OutlinedTextField(a, { a = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(b, { b = it }, label = { Text("Repeat") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton(enabled = a.length >= 10 && a == b, onClick = { onOk(a) }) { Text("Continue") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** An archived month, read from Drive on request (nothing is written back to the phone). */
@Composable
fun ArchiveMonthScreen(month: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = KoshApp.get(ctx)
    var rows by remember { mutableStateOf<List<Triple<Long, String, Pair<String, Long>>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(month) {
        try {
            rows = withContext(Dispatchers.IO) {
                app.sync.fetchArchive(month).filter { it["t"]?.jsonPrimitive?.content == "transactions" }.mapNotNull { rec ->
                    val r = rec["r"]?.jsonObject ?: return@mapNotNull null
                    val date = r["dateMillis"]?.jsonPrimitive?.longOrNull ?: 0
                    val name = r["merchantName"]?.jsonPrimitive?.content?.takeIf { it != "null" } ?: r["categoryId"]?.jsonPrimitive?.content ?: "—"
                    Triple(date, name, (r["type"]?.jsonPrimitive?.content ?: "") to (r["amountPaise"]?.jsonPrimitive?.longOrNull ?: 0))
                }.sortedBy { it.first }
            }
        } catch (e: Exception) { error = errText(e) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { ScreenHeader("Archive · $month", onBack) }
        item { Text("Fetched from your Google Drive. Read-only.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        if (rows == null && error == null) item { CircularProgressIndicator() }
        items(rows.orEmpty()) { (d, name, ta) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Medium); Text("${formatDay(d)} · ${ta.first.lowercase()}", fontSize = 12.sp) }
                Text(Money.format(ta.second))
            }
            HorizontalDivider()
        }
    }
}

/* ------------------------------------------------------------------ AI assistant */

@Composable
fun AssistantScreen(main: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = KoshApp.get(ctx)
    val ai = app.ai
    val scope = rememberCoroutineScope()
    var hasKey by remember { mutableStateOf(ai.hasKey) }
    var keyText by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(ai.model) }
    var enabled by remember { mutableStateOf(ai.enabled) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<com.prash.kosh.data.SummaryEntity?>(null) }
    var unknownCount by remember { mutableStateOf(0) }
    val suggestions = remember { mutableStateListOf<Pair<AiSuggestion, Boolean>>() }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        unknownCount = withContext(Dispatchers.IO) { ai.unknownMessages().size }
        summary = withContext(Dispatchers.IO) { app.db.extras().summary(YearMonth.now().minusMonths(1).toString()) }
    }
    fun run(label: String, block: suspend () -> Unit) {
        scope.launch { busy = label; info = null; try { block() } catch (e: Exception) { info = errText(e) }; busy = null }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("AI assistant", onBack) }
        item {
            SectionCard(title = "Ask about your money") {
                Text("Answers use monthly totals, categories and top merchants only — never account numbers, names or message text.", fontSize = 13.sp)
                OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("e.g. How much did I spend on food delivery this year?") })
                Button(enabled = hasKey && question.isNotBlank() && busy == null, modifier = Modifier.padding(top = 8.dp),
                    onClick = { run("ask") { answer = withContext(Dispatchers.IO) { ai.ask(question) } } }) { Text(if (busy == "ask") "Thinking…" else "Ask") }
                answer?.let { Text(it, Modifier.padding(top = 10.dp)) }
            }
        }
        item {
            SectionCard(title = "Monthly summary") {
                val month = YearMonth.now().minusMonths(1)
                summary?.let { Text(it.text, fontSize = 14.sp); Text(if (it.source == "AI") "Written by Gemini from your totals" else "Basic summary (add a Gemini key for a richer one)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(enabled = busy == null, modifier = Modifier.padding(top = 8.dp), onClick = {
                    run("sum") { summary = withContext(Dispatchers.IO) { ai.monthlySummary(month, force = true) } }
                }) { Text(if (busy == "sum") "Writing…" else if (summary == null) "Summarise ${month.month.name.lowercase().replaceFirstChar(Char::uppercase)}" else "Write again") }
            }
        }
        item {
            SectionCard(title = "Messages the rules could not read") {
                Text("$unknownCount skipped message(s) mention an amount. Gemini can read them (with account numbers, phone numbers, UPI IDs and names masked). " +
                    "Anything it finds goes to Review for your approval — nothing is added to your totals automatically.", fontSize = 13.sp)
                Button(enabled = hasKey && unknownCount > 0 && busy == null, modifier = Modifier.padding(top = 8.dp), onClick = {
                    run("read") {
                        val s = withContext(Dispatchers.IO) { ai.readUnknown() }
                        suggestions.clear(); suggestions.addAll(s.map { it to true })
                        if (s.isEmpty()) info = "No transactions found in those messages."
                    }
                }) { Text(if (busy == "read") "Reading…" else "Read with AI") }
                suggestions.forEachIndexed { i, (s, on) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { suggestions[i] = s to it })
                        Column(Modifier.weight(1f)) {
                            Text("${s.merchant ?: "Unknown"} · ${Money.format(s.amountPaise)}", fontWeight = FontWeight.Medium)
                            Text("${s.type.lowercase()} · ${formatDay(s.dateMillis)} · ${s.categoryId ?: "no category"}", fontSize = 12.sp)
                        }
                    }
                }
                if (suggestions.isNotEmpty()) Button(modifier = Modifier.padding(top = 4.dp), onClick = {
                    run("save") {
                        val n = withContext(Dispatchers.IO) { ai.saveSuggestions(suggestions.filter { it.second }.map { it.first }) }
                        suggestions.clear(); unknownCount = withContext(Dispatchers.IO) { ai.unknownMessages().size }
                        info = "$n added to Review."
                    }
                }) { Text("Add selected to Review") }
            }
        }
        info?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item {
            SectionCard(title = "Gemini settings") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use AI", Modifier.weight(1f)); Switch(enabled, { enabled = it; ai.enabled = it })
                }
                Text(if (hasKey) "API key saved (encrypted on this phone)." else "Paste a free API key from Google AI Studio (aistudio.google.com → Get API key).", fontSize = 13.sp)
                OutlinedTextField(keyText, { keyText = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Model", Modifier.padding(top = 8.dp), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    AiAgents.MODELS.take(2).forEach { m -> FilterChip(model == m, { model = m; ai.model = m }, label = { Text(m.removePrefix("gemini-"), fontSize = 11.sp) }) }
                }
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model name (if Google renames models)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        if (keyText.isNotBlank()) { ai.setKey(keyText); keyText = ""; hasKey = true }
                        ai.model = model
                        run("test") { info = "Gemini replied: " + withContext(Dispatchers.IO) { ai.test() } }
                    }) { Text("Save & test") }
                    if (hasKey) OutlinedButton(onClick = { ai.setKey(null); hasKey = false }) { Text("Remove key") }
                }
                Text("Free tier: Google may use free-tier requests to improve its products, which is why Kosh only sends masked or summarised data.",
                    Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ------------------------------------------------------------------ screenshot + statement import (shown inside Capture) */

@Composable
fun ImportExtrasSection() {
    val ctx = LocalContext.current
    val app = KoshApp.get(ctx)
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf<List<StatementParser.Row>?>(null) }
    val accounts by app.db.accounts().observeAll().collectAsState(initial = emptyList())

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            result = "Reading screenshot…"
            result = try {
                val text = withContext(Dispatchers.IO) { com.prash.kosh.capture.ScreenshotReader.read(ctx, uri) }
                if (text.isBlank()) "No text found in that image." else {
                    val o = withContext(Dispatchers.IO) { app.repo.ingest(RawInput(CaptureSource.SHARED, "SCREENSHOT", text, System.currentTimeMillis())) }
                    when (o) {
                        IngestOutcome.POSTED, IngestOutcome.PENDING -> "Saved a transaction from the screenshot."
                        IngestOutcome.NEEDS_REVIEW, IngestOutcome.SUSPECTED_DUPLICATE -> "Found a transaction — check it in Review."
                        IngestOutcome.MERGED -> "Matched a transaction already recorded."
                        IngestOutcome.ALREADY_SEEN -> "This screenshot was already read."
                        IngestOutcome.IGNORED -> "No payment found by the rules. Try AI assistant → Read with AI."
                    }
                }
            } catch (e: Exception) { "Could not read the image: ${e.message}" }
        }
    }
    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val rows = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { StatementParser.parseCsv(it.reader().readText()) }.orEmpty() }
            if (rows.isEmpty()) result = "No transactions found. Download the statement as CSV (Excel and PDF statements can be imported in the Kosh web app)."
            else pendingCsv = rows
        }
    }

    SectionCard(title = "Screenshot of a payment") {
        Text("Pick a screenshot (UPI receipt, card alert, bill). Text is read on this phone by Google ML Kit — the image never leaves the device.", fontSize = 13.sp)
        OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = { pickImage.launch("image/*") }) { Text("Choose screenshot") }
    }
    SectionCard(title = "Bank or card statement (CSV)") {
        Text("Import a statement downloaded from net banking as CSV. Rows already captured from SMS are merged, not doubled. " +
            "For Excel or PDF statements, use the Kosh web app on your computer.", fontSize = 13.sp)
        OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = { pickCsv.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) }) { Text("Choose CSV file") }
    }
    result?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }

    pendingCsv?.let { rows ->
        AlertDialog(onDismissRequest = { pendingCsv = null },
            title = { Text("${rows.size} rows found") },
            text = {
                Column {
                    Text("Which account is this statement for?", fontSize = 13.sp)
                    accounts.filter { it.kind != "CASH" }.forEach { a ->
                        TextButton({
                            pendingCsv = null
                            scope.launch {
                                result = "Importing…"
                                val c = withContext(Dispatchers.IO) { app.repo.importStatement(rows, a.id) }
                                result = "Imported: saved ${(c[IngestOutcome.POSTED] ?: 0) + (c[IngestOutcome.PENDING] ?: 0)}, " +
                                    "to review ${(c[IngestOutcome.NEEDS_REVIEW] ?: 0) + (c[IngestOutcome.SUSPECTED_DUPLICATE] ?: 0)}, " +
                                    "merged with SMS ${c[IngestOutcome.MERGED] ?: 0}, skipped ${(c[IngestOutcome.IGNORED] ?: 0) + (c[IngestOutcome.ALREADY_SEEN] ?: 0)}."
                            }
                        }) { Text(a.name) }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton({ pendingCsv = null }) { Text("Cancel") } })
    }
}
