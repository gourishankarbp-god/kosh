package com.prash.kosh.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.prash.kosh.KoshApp
import com.prash.kosh.backup.BackupCrypto
import com.prash.kosh.backup.BackupData
import com.prash.kosh.capture.PaymentNotificationListener
import com.prash.kosh.capture.SmsInboxImporter
import com.prash.kosh.capture.openNotificationAccessSettings
import com.prash.kosh.data.AccountEntity
import com.prash.kosh.data.CategoryEntity
import com.prash.kosh.data.IngestOutcome
import com.prash.kosh.data.KeywordRuleEntity
import com.prash.kosh.data.MerchantEntity
import com.prash.kosh.engine.IgnoreReason
import com.prash.kosh.engine.Money
import com.prash.kosh.security.KeyManager
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.CategoryPickerDialog
import com.prash.kosh.ui.components.EmptyState
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.categoryPath
import com.prash.kosh.ui.components.formatDayTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MoreScreen(onOpen: (String) -> Unit) {
    val entries = listOf(
        "sync" to ("Sync & archive" to "Google Drive sync with the web app; months older than a year"),
        "assistant" to ("AI assistant" to "Ask questions, monthly summary, read unknown messages"),
        "capture" to ("Capture & import" to "SMS, app alerts, screenshots, CSV statements"),
        "accounts" to ("Accounts" to "Banks, cards, cash and balances"),
        "cash" to ("Cash" to "Cash in hand, withdrawals and cash spending"),
        "reports" to ("Reports" to "Monthly, yearly, category, merchant, cash, transfers, LIC & bonds"),
        "recognition" to ("Recognition & data" to "Confidence thresholds and how long SMS text is kept"),
        "categories" to ("Categories" to "Rename, add subcategories, hide"),
        "merchants" to ("Merchant rules" to "Which merchant goes in which category"),
        "keywords" to ("Keyword rules" to "Fallback words used when the merchant is unknown"),
        "ignored" to ("Ignored messages" to "OTPs, promotions and other skipped messages"),
        "backup" to ("Backup & restore" to "Encrypted copy to Google Drive or OneDrive"),
        "security" to ("App lock" to "Fingerprint or PIN on open"),
        "appearance" to ("Appearance & name" to "Theme and the name in your greeting"),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text("More", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(entries) { (route, labels) ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(route) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(labels.first, fontWeight = FontWeight.Medium)
                    Text(labels.second, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
            HorizontalDivider()
        }
        item {
            Text("Kosh ${com.prash.kosh.BuildConfig.VERSION_NAME} · encrypted on this phone and in your Drive", Modifier.padding(16.dp), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ------------------------------------------------------------------ capture */

@Composable
fun CaptureScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var smsGranted by remember { mutableStateOf(SmsInboxImporter.hasPermission(ctx)) }
    var notifEnabled by remember { mutableStateOf(PaymentNotificationListener.isEnabled(ctx)) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var summary by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            smsGranted = SmsInboxImporter.hasPermission(ctx)
            notifEnabled = PaymentNotificationListener.isEnabled(ctx)
        }
    }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        smsGranted = r.values.all { it }
    }

    fun runImport(days: Int) {
        importing = true; summary = null; progress = 0
        scope.launch {
            val s = withContext(Dispatchers.IO) { SmsInboxImporter.import(ctx, days) { progress = it } }
            val c = s.counts
            summary = "Read ${s.scanned} bank messages from the last $days days.\n" +
                "Saved: ${(c[IngestOutcome.POSTED] ?: 0) + (c[IngestOutcome.PENDING] ?: 0)} · " +
                "To review: ${(c[IngestOutcome.NEEDS_REVIEW] ?: 0) + (c[IngestOutcome.SUSPECTED_DUPLICATE] ?: 0)} · " +
                "Merged: ${c[IngestOutcome.MERGED] ?: 0} · Ignored: ${c[IngestOutcome.IGNORED] ?: 0} · Already imported: ${c[IngestOutcome.ALREADY_SEEN] ?: 0}"
            importing = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Capture & import", onBack) }
        item {
            SectionCard(title = "Bank & UPI SMS") {
                Text("Kosh reads messages from bank and UPI senders (headers like VM-HDFCBK) and turns them into transactions on this phone. " +
                    "OTPs are discarded unread by the parser. Personal messages are never imported.", fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (smsGranted) "On" else "Off", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (!smsGranted) Button(onClick = { askSms.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }) { Text("Allow SMS access") }
                }
            }
        }
        item {
            SectionCard(title = "Payment-app notifications") {
                Text("Reads alerts from Google Pay, PhonePe, Paytm, BHIM, CRED, Amazon Pay and major bank apps only. " +
                    "When both an SMS and an app alert arrive for one payment, they are merged into one transaction.", fontSize = 13.sp)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (notifEnabled) "On" else "Off", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { openNotificationAccessSettings(ctx) }) { Text(if (notifEnabled) "Settings" else "Turn on") }
                }
            }
        }
        item { com.prash.kosh.ui.screens.ImportExtrasSection() }
        if (!smsGranted || !notifEnabled) item {
            SectionCard(title = "If a permission is greyed out") {
                Text("Android 15 and later blocks SMS and notification access for apps installed from an APK until you allow it once: " +
                    "tap Allow SMS access (or Turn on) first, then open App info, tap ⋮ (top right), choose “Allow restricted settings”, " +
                    "confirm with your PIN, and come back here.", fontSize = 13.sp)
                OutlinedButton(modifier = Modifier.padding(top = 8.dp), onClick = {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                }) { Text("Open App info") }
            }
        }
        item {
            SectionCard(title = "Import past messages") {
                Text("Scan your SMS inbox once to fill in history. Safe to repeat: messages already imported are skipped.", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf(30, 90, 180).forEach { d ->
                        OutlinedButton(onClick = { runImport(d) }, enabled = smsGranted && !importing) { Text("$d days") }
                    }
                }
                if (importing) Text("Importing… $progress messages", Modifier.padding(top = 8.dp))
                summary?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 13.sp) }
            }
        }
        item {
            SectionCard(title = "Without permissions") {
                Text("In any messaging app, long-press a bank message, tap Share and choose “Add to Kosh”. You can also add transactions by hand with the + button.", fontSize = 13.sp)
            }
        }
    }
}

/* ------------------------------------------------------------------ categories */

@Composable
fun CategoriesScreen(main: MainViewModel, onBack: () -> Unit) {
    val cats by main.categories.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    val tops = cats.filter { it.parentId == null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { ScreenHeader("Categories", onBack) }
        tops.forEach { top ->
            item(key = top.id) {
                Row(Modifier.fillMaxWidth().clickable { editing = top }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(top.name + (if (top.isFixed) " · fixed" else "") + if (top.hidden) " (hidden)" else "", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(top.kind.lowercase(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        editing = CategoryEntity("${top.id}.u${System.currentTimeMillis() % 1_000_000}", "", top.id, top.kind, top.icon, 999)
                    }) { Text("+ Sub") }
                }
            }
            items(cats.filter { it.parentId == top.id }, key = { it.id }) { s ->
                Text(s.name + if (s.hidden) " (hidden)" else "", Modifier.fillMaxWidth().clickable { editing = s }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
            }
            item(key = "d-${top.id}") { HorizontalDivider() }
        }
        item {
            OutlinedButton(onClick = { editing = CategoryEntity("u${System.currentTimeMillis()}", "", null, "EXPENSE", "category", 500) },
                modifier = Modifier.padding(top = 12.dp)) { Text("Add category") }
        }
    }

    editing?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        var hidden by remember(c) { mutableStateOf(c.hidden) }
        var fixed by remember(c) { mutableStateOf(c.isFixed) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (c.name.isEmpty()) "New category" else "Edit category") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(hidden, { hidden = it }); Text("  Hide from pickers")
                    }
                    Text("Hiding keeps past transactions and reports intact.", fontSize = 12.sp)
                    if (c.kind == "EXPENSE") Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(fixed, { fixed = it }); Text("  Fixed expense (rent, bills, EMIs)")
                    }
                    if (c.kind == "EXPENSE") Text("Fixed expenses count towards the recurring expense ratio. Subcategories inherit it.", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) scope.launch { main.repo.saveCategory(c.copy(name = name.trim(), hidden = hidden, isFixed = fixed)); editing = null }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

/* ------------------------------------------------------------------ merchant + keyword rules */

@Composable
fun MerchantRulesScreen(main: MainViewModel, onBack: () -> Unit) {
    val merchants by main.repo.db.merchants().observeAll().collectAsState(initial = emptyList())
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<MerchantEntity?>(null) }
    val shown = merchants.filter { q.isBlank() || it.displayName.contains(q, true) || it.key.contains(q, true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { ScreenHeader("Merchant rules", onBack) }
        item {
            Text("Your rules (marked ★) beat the built-in list. Tap any merchant to change its category.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), placeholder = { Text("Search ${merchants.size} merchants") }, singleLine = true)
        }
        items(shown, key = { it.key }) { m ->
            Row(Modifier.fillMaxWidth().clickable { picking = m }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (m.source == "USER") "★ " else "") + m.displayName, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text(categoryPath(m.categoryId, byId), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
    picking?.let { m ->
        CategoryPickerDialog(cats, setOf("EXPENSE", "INCOME", "TRANSFER", "INVESTMENT"),
            onPick = { c -> scope.launch { main.repo.setMerchantCategory(m, c.id) }; picking = null }, onDismiss = { picking = null })
    }
}

@Composable
fun KeywordRulesScreen(main: MainViewModel, onBack: () -> Unit) {
    val rules by main.repo.db.keywordRules().observeAll().collectAsState(initial = emptyList())
    val cats by main.categories.collectAsState()
    val byId by main.categoriesById.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<KeywordRuleEntity?>(null) }
    var picking by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { ScreenHeader("Keyword rules", onBack) }
        item {
            Text("Used only when no merchant rule matches. Whole words, first match wins.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { editing = KeywordRuleEntity(0, "", "other", (rules.maxOfOrNull { it.sortOrder } ?: 0) + 1) },
                modifier = Modifier.padding(vertical = 8.dp)) { Text("Add keyword") }
        }
        items(rules, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 10.dp)) {
                Text("“${r.keyword}”", Modifier.weight(1f))
                Text(categoryPath(r.categoryId, byId), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
    editing?.let { r ->
        var kw by remember(r) { mutableStateOf(r.keyword) }
        var cat by remember(r) { mutableStateOf(r.categoryId) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Keyword rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(kw, { kw = it }, label = { Text("Word or phrase") }, singleLine = true)
                    OutlinedButton(onClick = { picking = true }) { Text(categoryPath(cat, byId)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (kw.isNotBlank()) scope.launch { main.repo.saveKeywordRule(r.copy(keyword = kw.trim().lowercase(), categoryId = cat)); editing = null }
                }) { Text("Save") }
            },
            dismissButton = {
                if (r.id != 0L) TextButton(onClick = { scope.launch { main.repo.deleteKeywordRule(r.id); editing = null } }) { Text("Delete") }
            },
        )
        if (picking) CategoryPickerDialog(cats, setOf("EXPENSE", "INCOME", "TRANSFER", "INVESTMENT"),
            onPick = { cat = it.id; picking = false }, onDismiss = { picking = false })
    }
}

/* ------------------------------------------------------------------ ignored messages */

@Composable
fun IgnoredScreen(main: MainViewModel, onBack: () -> Unit) {
    val items by main.repo.db.rawMessages().observeIgnored().collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader("Ignored messages", onBack) }
        item {
            Text("Kept so you can check nothing real was skipped. If one is a real transaction, share it to Kosh from your messages app, or add it with +.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (items.isEmpty()) item { EmptyState("Nothing ignored yet") }
        items(items, key = { it.id }) { m ->
            SectionCard {
                val reason = m.ignoreReason?.let { r -> runCatching { IgnoreReason.valueOf(r).label }.getOrDefault(r) } ?: ""
                Text("$reason · ${m.sender} · ${formatDayTime(m.receivedAt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (m.ignoreReason == IgnoreReason.OTP.name) "(one-time password hidden)" else m.body, fontSize = 12.sp, maxLines = 6)
            }
        }
    }
}

/* ------------------------------------------------------------------ backup */

@Composable
fun BackupScreen(main: MainViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = KoshApp.get(ctx)
    val scope = rememberCoroutineScope()
    var hasPassword by remember { mutableStateOf(KeyManager.backupPassword(ctx) != null) }
    var location by remember { mutableStateOf(app.settings.backupUri) }
    var status by remember { mutableStateOf(lastBackupText(app)) }
    var busy by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<BackupData?>(null) }

    val pickLocation = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.settings.backupUri = uri; location = uri
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Backup & restore", onBack) }
        item {
            SectionCard(title = "1. Backup password") {
                Text("Backups are encrypted with this password so they can be restored on a new phone. Kosh cannot recover it if you forget it.", fontSize = 13.sp)
                OutlinedButton(onClick = { askPassword = true }, modifier = Modifier.padding(top = 8.dp)) { Text(if (hasPassword) "Change password" else "Set password") }
            }
        }
        item {
            SectionCard(title = "2. Where to save") {
                Text("Choose a file in Google Drive or OneDrive (both appear in Android's file picker). Kosh overwrites it once a day and keeps 7 copies on the phone.", fontSize = 13.sp)
                Text(location?.let { "Saving to: ${it.lastPathSegment}" } ?: "Not chosen yet", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                OutlinedButton(onClick = { pickLocation.launch("kosh-backup.kosh") }, modifier = Modifier.padding(top = 8.dp)) { Text("Choose file") }
            }
        }
        item {
            SectionCard(title = "3. Back up") {
                Text(status, fontSize = 13.sp)
                Button(enabled = hasPassword && !busy, modifier = Modifier.padding(top = 8.dp), onClick = {
                    busy = true
                    scope.launch {
                        status = try { "Backed up: " + withContext(Dispatchers.IO) { app.backup.backupNow() } } catch (e: Exception) { "Backup failed: ${e.message}" }
                        busy = false
                    }
                }) { Text(if (busy) "Working…" else "Back up now") }
            }
        }
        item {
            SectionCard(title = "Restore") {
                Text("Replaces everything on this phone with a backup. You will see what the backup contains before anything changes.", fontSize = 13.sp)
                OutlinedButton(onClick = { pickRestore.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Choose backup file") }
            }
        }
    }

    if (askPassword) PasswordDialog("Backup password", confirm = true, onDismiss = { askPassword = false }) { pw ->
        KeyManager.saveBackupPassword(ctx, pw); hasPassword = true; askPassword = false
    }

    restoreUri?.let { uri ->
        if (preview == null) PasswordDialog("Password for this backup", confirm = false, onDismiss = { restoreUri = null }) { pw ->
            scope.launch {
                try {
                    preview = withContext(Dispatchers.IO) { app.backup.readBackup(uri, pw) }
                } catch (e: BackupCrypto.WrongPasswordException) {
                    Toast.makeText(ctx, "Wrong password", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, e.message ?: "Could not read backup", Toast.LENGTH_LONG).show(); restoreUri = null
                }
            }
        }
    }
    preview?.let { data ->
        AlertDialog(
            onDismissRequest = { preview = null; restoreUri = null },
            title = { Text("Restore this backup?") },
            text = { Text("Made ${formatDayTime(data.createdAt)}.\nContains ${data.summary()}.\n\nEverything currently on this phone will be replaced.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.backup.restore(data) }
                        Toast.makeText(ctx, "Restored", Toast.LENGTH_SHORT).show()
                        preview = null; restoreUri = null
                    }
                }) { Text("Replace and restore") }
            },
            dismissButton = { TextButton(onClick = { preview = null; restoreUri = null }) { Text("Cancel") } },
        )
    }
}

private fun lastBackupText(app: KoshApp): String {
    val at = app.settings.lastBackupAt
    val err = app.settings.lastBackupError
    return buildString {
        append(if (at == 0L) "No backup yet." else "Last backup: ${formatDayTime(at)}.")
        if (err != null) append("\nLast automatic attempt failed: $err")
    }
}

@Composable
private fun PasswordDialog(title: String, confirm: Boolean, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val ok = pw.length >= 8 && (!confirm || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (confirm) OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onOk(pw) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/* ------------------------------------------------------------------ app lock */

@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val app = KoshApp.get(LocalContext.current)
    var on by remember { mutableStateOf(app.settings.appLockEnabled) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("App lock", onBack)
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Require fingerprint, face or PIN", fontWeight = FontWeight.Medium)
                    Text("On opening, and after 2 minutes in the background", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { on = it; app.settings.appLockEnabled = it })
            }
        }
        Text("The database itself is always encrypted (SQLCipher, AES-256) with a key held by Android's secure hardware, whether or not the lock is on.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ------------------------------------------------------------------ appearance */

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val app = KoshApp.get(LocalContext.current)
    val mode by app.themeMode
    val options = listOf(
        "LIGHT" to ("Light" to "White background"),
        "DARK" to ("Dark" to "Dark background, easier at night"),
        "SYSTEM" to ("Follow phone" to "Matches your phone's dark mode setting"),
    )
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Appearance", onBack)
        SectionCard(title = "Theme") {
            options.forEach { (value, labels) ->
                Row(
                    Modifier.fillMaxWidth().clickable { app.setThemeMode(value) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = mode == value, onClick = { app.setThemeMode(value) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(labels.first, fontWeight = FontWeight.Medium)
                        Text(labels.second, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text("Changes apply straight away.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val savedName by app.userName
        var name by remember { mutableStateOf(savedName) }
        SectionCard(title = "Your name") {
            Text("Used in the greeting on Home (“Good morning, …”).", fontSize = 13.sp)
            OutlinedTextField(name, { name = it; app.setUserName(it) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Name") }, singleLine = true)
        }
    }
}
