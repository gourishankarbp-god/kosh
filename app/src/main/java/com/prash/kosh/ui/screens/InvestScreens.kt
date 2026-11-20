@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.prash.kosh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.data.InvestmentEntity
import com.prash.kosh.data.InvestmentEntryEntity
import com.prash.kosh.data.InvestmentTotal
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.data.addInvestmentEntry
import com.prash.kosh.data.linkTransactionToInvestment
import com.prash.kosh.data.nextInvestmentDue
import com.prash.kosh.data.noonMillis
import com.prash.kosh.data.saveInvestment
import com.prash.kosh.engine.Frequency
import com.prash.kosh.engine.Money
import com.prash.kosh.ui.MainViewModel
import com.prash.kosh.ui.components.EmptyState
import com.prash.kosh.ui.components.Figure
import com.prash.kosh.ui.components.IST
import com.prash.kosh.ui.components.KeyValue
import com.prash.kosh.ui.components.Pill
import com.prash.kosh.ui.components.SectionCard
import com.prash.kosh.ui.components.formatDay
import com.prash.kosh.ui.theme.Invest
import com.prash.kosh.ui.theme.forTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** What can be tracked: LIC and other insurance, bonds, and new fixed investments (FD, RD...) you start. */
val INVESTMENT_KINDS = listOf(
    "LIC" to "LIC policy", "INSURANCE" to "Other insurance policy", "BOND" to "Bond",
    "FD" to "Fixed deposit", "RD" to "Recurring deposit", "OTHER_FIXED" to "Other investment",
)
private val LEGACY_KINDS = mapOf("SIP" to "SIP (older entry)", "PPF" to "PPF", "EPF" to "EPF", "NPS" to "NPS")
fun investmentKindLabel(k: String) = INVESTMENT_KINDS.firstOrNull { it.first == k }?.second ?: LEGACY_KINDS[k] ?: k
private fun group(k: String) = when (k) { "LIC", "INSURANCE" -> "LIC & insurance policies"; "BOND" -> "Bonds"; else -> "Other investments" }
private val GROUPS = listOf("LIC & insurance policies", "Bonds", "Other investments")
private val longFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun InvestmentsScreen(main: MainViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val db = main.repo.db
    val list by db.investments().observeAll().collectAsState(initial = emptyList())
    val totals by db.investments().observeTotals().collectAsState(initial = emptyList())
    val unlinked by db.transactions().observeUnlinkedInvestments().collectAsState(initial = emptyList())
    val byId = totals.associateBy { it.investmentId }
    val scope = rememberCoroutineScope()
    var linking by remember { mutableStateOf<TransactionEntity?>(null) }
    val today = LocalDate.now(IST)
    val active = list.filter { it.status == "ACTIVE" }
    fun net(i: InvestmentEntity) = byId[i.id]?.let { it.contributed - it.withdrawn } ?: 0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("LIC, insurance & bonds", onBack) }
        item {
            SectionCard(title = "Total paid") {
                Figure("Active policies & investments", Money.format(active.sumOf(::net), showPaise = false), color = Invest.forTheme(),
                    sub = "Premiums and instalments paid, minus money taken out. Returns and sum assured are not counted as value.")
                Spacer(Modifier.height(8.dp))
                GROUPS.forEach { g ->
                    val inGroup = active.filter { group(it.kind) == g }
                    if (inGroup.isNotEmpty()) KeyValue("$g (${inGroup.size})", Money.format(inGroup.sumOf(::net), showPaise = false))
                }
            }
        }
        if (unlinked.isNotEmpty()) item {
            SectionCard(title = "Investment payments to link") {
                Text("These bank payments look like investments but did not match one automatically.", fontSize = 13.sp)
                unlinked.take(5).forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(t.merchantName ?: "Investment payment", fontWeight = FontWeight.Medium)
                            Text("${formatDay(t.dateMillis)} · ${Money.format(t.amountPaise, showPaise = false)}", fontSize = 12.sp)
                        }
                        TextButton(onClick = { linking = t }) { Text("Link") }
                    }
                }
            }
        }
        if (list.isEmpty()) item { EmptyState("Add your LIC and insurance policies and bonds. Premium payments from your bank then link here automatically. Add FDs or RDs here when you start new ones.") }
        GROUPS.forEach { g ->
            val inGroup = list.filter { group(it.kind) == g }
            if (inGroup.isNotEmpty()) {
                item(key = "h-$g") { Text(g, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                items(inGroup, key = { it.id }) { i -> InvestmentCard(i, byId[i.id], today) { onOpen(i.id) } }
            }
        }
        item { Button(onClick = onAdd) { Text("Add policy or investment") } }
    }

    linking?.let { t ->
        val similar = unlinked.filter { it.id != t.id && t.merchantKey != null && it.merchantKey == t.merchantKey }
        var all by remember(t) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text("Link ${Money.format(t.amountPaise, showPaise = false)} to…") },
            text = {
                Column {
                    if (active.isEmpty()) Text("Add the investment first, then link this payment.")
                    if (similar.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { all = !all }) {
                        androidx.compose.material3.Checkbox(all, { all = it })
                        Text("Also link the ${similar.size} other payment${if (similar.size == 1) "" else "s"} to ${t.merchantName ?: "this payee"} " +
                            "(${Money.format(similar.sumOf { it.amountPaise }, showPaise = false)})", fontSize = 13.sp)
                    }
                    active.forEach { i ->
                        Text("${i.name} · ${investmentKindLabel(i.kind)}", Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                main.repo.linkTransactionToInvestment(t, i.id)
                                if (all) similar.forEach { main.repo.linkTransactionToInvestment(it, i.id) }
                                linking = null
                            }
                        }.padding(vertical = 10.dp))
                        HorizontalDivider()
                    }
                    Text("If you link past payments, don't also count them in “invested so far”.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = { linking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InvestmentCard(i: InvestmentEntity, t: InvestmentTotal?, today: LocalDate, onClick: () -> Unit) {
    val net = t?.let { it.contributed - it.withdrawn } ?: 0
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(i.name, fontWeight = FontWeight.Medium)
                Text(listOfNotNull(investmentKindLabel(i.kind), i.institution).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Money.format(net, showPaise = false), fontWeight = FontWeight.SemiBold, color = Invest.forTheme())
        }
        val line = when {
            i.status != "ACTIVE" -> if (i.status == "MATURED") "Matured" else "Closed"
            else -> {
                val due = nextInvestmentDue(i, t?.lastContribution, today)
                val maturity = i.maturityDate?.let(LocalDate::parse)
                listOfNotNull(
                    i.installmentPaise?.let { "${Money.format(it, showPaise = false)} ${Frequency.valueOf(i.frequency).label.lowercase()}" },
                    due?.let { "next ${dueLabel(it, today)}" },
                    maturity?.let { m -> val d = ChronoUnit.DAYS.between(today, m); if (d >= 0) "matures ${m.format(longFmt)}" else "maturity date passed" },
                    i.ratePercent?.let { "$it%" },
                ).joinToString(" · ")
            }
        }
        if (line.isNotBlank()) Text(line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/* ------------------------------------------------------------------ detail */

@Composable
fun InvestmentDetailScreen(main: MainViewModel, id: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val db = main.repo.db
    val inv by remember(id) { db.investments().observe(id) }.collectAsState(initial = null)
    val entries by remember(id) { db.investments().observeEntries(id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<String?>(null) }
    val i = inv ?: return
    val today = LocalDate.now(IST)
    val contributed = entries.filter { it.kind == "CONTRIBUTION" || it.kind == "OPENING" }.sumOf { it.amountPaise }
    val out = entries.filter { it.kind == "WITHDRAWAL" || it.kind == "MATURITY" }.sumOf { it.amountPaise }
    val last = entries.filter { it.kind == "CONTRIBUTION" }.maxOfOrNull { it.dateMillis }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(i.name, onBack) }
        item {
            SectionCard {
                Row {
                    Figure(if (i.kind == "LIC") "Premiums paid" else "Invested", Money.format(contributed, showPaise = false), Modifier.weight(1f), color = Invest.forTheme())
                    Figure("Taken out", Money.format(out, showPaise = false), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                KeyValue("Type", investmentKindLabel(i.kind))
                i.institution?.let { KeyValue("With", it) }
                i.refLast4?.let { KeyValue("Number", "••$it") }
                KeyValue("Started", LocalDate.parse(i.startDate).format(longFmt))
                i.installmentPaise?.let { KeyValue(if (i.kind == "LIC") "Premium" else "Instalment", "${Money.format(it, showPaise = false)} ${Frequency.valueOf(i.frequency).label.lowercase()}") }
                nextInvestmentDue(i, last, today)?.let { KeyValue("Next due", "${it.format(longFmt)} (${dueLabel(it, today)})") }
                com.prash.kosh.data.remainingPayments(i, last, today)?.let { (n, amt) ->
                    KeyValue("Remaining payments", if (n == 0) "None" else "$n (${Money.format(amt, showPaise = false)})")
                }
                i.ratePercent?.let { KeyValue("Interest rate", "$it% a year") }
                i.maturityDate?.let { KeyValue("Maturity date", LocalDate.parse(it).format(longFmt)) }
                i.maturityAmountPaise?.let { KeyValue(if (i.kind == "LIC") "Projected maturity benefit" else "Maturity amount", Money.format(it, showPaise = false)) }
                i.sumAssuredPaise?.let { KeyValue("Sum assured (cover, not value)", Money.format(it, showPaise = false)) }
                KeyValue("Status", i.status.lowercase().replaceFirstChar { it.uppercase() })
                i.note?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { adding = "CONTRIBUTION" }) { Text(if (i.kind == "LIC") "Add premium" else "Add money") }
                OutlinedButton(onClick = { adding = "WITHDRAWAL" }) { Text("Partial withdrawal") }
                OutlinedButton(onClick = { adding = "MATURITY" }) { Text("Matured / closed") }
                OutlinedButton(onClick = onEdit) { Text("Edit") }
            }
        }
        item { DocumentsSection(main, i.id) }
        item {
            SectionCard(title = "Payment history") {
                if (entries.isEmpty()) Text("No entries yet.", fontSize = 13.sp)
                entries.forEachIndexed { n, e ->
                    if (n > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(when (e.kind) { "OPENING" -> "Invested before tracking"; "CONTRIBUTION" -> if (i.kind == "LIC") "Premium paid" else "Money in"
                                "WITHDRAWAL" -> "Partial withdrawal"; else -> "Maturity payout" }, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(formatDay(e.dateMillis) + (e.note?.let { " · $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((if (e.kind == "CONTRIBUTION" || e.kind == "OPENING") "+" else "−") + Money.format(e.amountPaise, showPaise = false), fontWeight = FontWeight.SemiBold)
                        if (e.transactionId == null) TextButton(onClick = { scope.launch { db.investments().deleteEntry(e.id) } }) { Text("Delete", fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    adding?.let { kind ->
        var amount by remember(kind) { mutableStateOf(if (kind == "CONTRIBUTION") paiseText(i.installmentPaise) else "") }
        var date by remember(kind) { mutableStateOf<LocalDate?>(today) }
        var note by remember(kind) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(when (kind) { "CONTRIBUTION" -> if (i.kind == "LIC") "Premium paid" else "Money in"; "WITHDRAWAL" -> "Partial withdrawal"; else -> "Matured / closed" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyField(if (kind == "MATURITY") "Amount received (₹)" else "Amount (₹)", amount, { amount = it })
                    DateField("Date", date, { date = it })
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
                    if (kind == "MATURITY") Text("This marks the investment as matured. It stays in your history.", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = Money.parseToPaise(amount) ?: return@TextButton
                    scope.launch {
                        main.repo.addInvestmentEntry(InvestmentEntryEntity(investmentId = i.id, kind = kind, amountPaise = p,
                            dateMillis = (date ?: today).noonMillis(), note = note.ifBlank { null }))
                        adding = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } },
        )
    }
}

/* ------------------------------------------------------------------ add / edit */

@Composable
fun InvestmentEditScreen(main: MainViewModel, id: Long?, onDone: (Long?) -> Unit) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(IST)
    var original by remember { mutableStateOf<InvestmentEntity?>(null) }
    var loaded by remember { mutableStateOf(id == null) }
    var kind by remember { mutableStateOf("FD") }
    var name by remember { mutableStateOf("") }
    var institution by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var start by remember { mutableStateOf<LocalDate?>(today) }
    var maturity by remember { mutableStateOf<LocalDate?>(null) }
    var rate by remember { mutableStateOf("") }
    var instalment by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("ONE_TIME") }
    var sumAssured by remember { mutableStateOf("") }
    var maturityAmount by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id != null) main.repo.db.investments().get(id)?.let { i ->
            original = i; kind = i.kind; name = i.name; institution = i.institution.orEmpty(); ref = i.refLast4.orEmpty()
            start = LocalDate.parse(i.startDate); maturity = i.maturityDate?.let(LocalDate::parse); rate = i.ratePercent?.toString().orEmpty()
            instalment = paiseText(i.installmentPaise); frequency = i.frequency; sumAssured = paiseText(i.sumAssuredPaise)
            maturityAmount = paiseText(i.maturityAmountPaise); keywords = i.matchKeywords; note = i.note.orEmpty()
        }
        loaded = true
    }
    if (!loaded) return
    val scheduled = kind in setOf("LIC", "INSURANCE", "RD", "BOND", "OTHER_FIXED")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader(if (original == null) "Add policy or investment" else "Edit") { onDone(null) }
        Text("Type", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            INVESTMENT_KINDS.forEach { (k, l) ->
                FilterChip(kind == k, {
                    kind = k
                    if (frequency == "ONE_TIME" && k in setOf("LIC", "INSURANCE", "RD")) frequency = if (k == "RD") "MONTHLY" else "YEARLY"
                    if (k == "FD") frequency = "ONE_TIME"
                }, label = { Text(l) })
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(when (kind) {
            "LIC" -> "Plan name (e.g. Jeevan Anand)"; "INSURANCE" -> "Policy name (e.g. Star Health family)"; "BOND" -> "Bond name (e.g. RBI Floating Rate)"; else -> "Name (e.g. HDFC FD 2027)" }) }, singleLine = true)
        OutlinedTextField(institution, { institution = it }, Modifier.fillMaxWidth(), label = { Text("Bank, insurer or fund house") }, singleLine = true)
        OutlinedTextField(ref, { ref = it.filter(Char::isDigit).takeLast(4) }, Modifier.fillMaxWidth(), label = { Text("Last 4 digits of policy / bond / account number (optional)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        DateField("Start date", start, { start = it })
        if (scheduled) {
            MoneyField(if (kind == "LIC" || kind == "INSURANCE") "Premium (₹)" else "Instalment (₹, leave empty if none)", instalment, { instalment = it })
            Text("How often", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.HALF_YEARLY, Frequency.YEARLY, Frequency.ONE_TIME).forEach { f ->
                    FilterChip(frequency == f.name, { frequency = f.name }, label = { Text(if (f == Frequency.ONE_TIME) "No schedule" else f.label) })
                }
            }
        }
        run {
            OutlinedTextField(rate, { rate = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(),
                label = { Text(if (kind == "LIC" || kind == "INSURANCE") "Bonus rate % (optional, for your notes)" else "Interest / coupon rate % a year (optional)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            DateField("Maturity date", maturity, { maturity = it }, allowClear = true)
            MoneyField(if (kind == "LIC" || kind == "INSURANCE") "Stated maturity benefit (₹, optional)" else "Maturity amount (₹, optional)", maturityAmount, { maturityAmount = it })
        }
        if (kind == "LIC" || kind == "INSURANCE") MoneyField("Sum assured (₹)", sumAssured, { sumAssured = it })
        if (original == null) {
            MoneyField(when (kind) { "FD" -> "Amount invested (₹)"; "LIC", "INSURANCE" -> "Premiums paid so far (₹)"; else -> "Paid so far (₹)" }, opening, { opening = it })
            if (scheduled) Text("Instalments due before today are treated as paid. Future ones appear in Upcoming payments.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth(), label = { Text("Words in bank SMS to match (optional, comma separated)") },
            supportingText = { Text("Already matched: " + (com.prash.kosh.data.DEFAULT_INVESTMENT_KEYWORDS[kind]?.take(4)?.joinToString() ?: "none")) })
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(onClick = {
            val s = start ?: return@Button
            if (name.isBlank()) { error = "Give it a name"; return@Button }
            val inst = if (scheduled) Money.parseToPaise(instalment) else null
            if (scheduled && frequency != "ONE_TIME" && inst == null && kind != "BOND" && kind != "OTHER_FIXED") { error = "Enter the premium or instalment amount"; return@Button }
            scope.launch {
                val saved = main.repo.saveInvestment((original ?: InvestmentEntity(kind = kind, name = name, startDate = s.toString())).copy(
                    kind = kind, name = name.trim(), institution = institution.ifBlank { null }, refLast4 = ref.ifBlank { null },
                    startDate = s.toString(), maturityDate = maturity?.toString(), ratePercent = rate.toDoubleOrNull(),
                    installmentPaise = inst, frequency = if (scheduled) frequency else "ONE_TIME",
                    sumAssuredPaise = if (kind == "LIC" || kind == "INSURANCE") Money.parseToPaise(sumAssured) else null,
                    maturityAmountPaise = Money.parseToPaise(maturityAmount), matchKeywords = keywords.trim(), note = note.ifBlank { null },
                ), if (original == null) Money.parseToPaise(opening) else null)
                onDone(saved)
            }
        }) { Text("Save") }
        Spacer(Modifier.height(40.dp))
    }
}

/* ------------------------------------------------------------------ documents */

/** Policy bonds, receipts and statements attached to a policy or investment. Stored privately; synced to Drive. */
@Composable
fun DocumentsSection(main: MainViewModel, investmentId: Long) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by remember(investmentId) { main.repo.db.extras().observeDocuments(investmentId) }.collectAsState(initial = emptyList())
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { com.prash.kosh.data.Documents.attach(ctx, main.repo, investmentId, uri) }
    }
    SectionCard(title = "Documents") {
        if (docs.isEmpty()) Text("Attach policy documents, bond certificates or receipts (PDF or photo).", fontSize = 13.sp)
        docs.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, Modifier.weight(1f).clickable { com.prash.kosh.data.Documents.open(ctx, d) }, fontSize = 14.sp, maxLines = 1)
                Text("${d.sizeBytes / 1024} KB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { scope.launch { com.prash.kosh.data.Documents.delete(ctx, main.repo, d) } }) { Text("Remove", fontSize = 12.sp) }
            }
        }
        OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.padding(top = 6.dp)) { Text("Attach file") }
    }
}
