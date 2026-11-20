package com.prash.kosh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.data.CategoryEntity
import com.prash.kosh.data.TransactionEntity
import com.prash.kosh.engine.Money
import com.prash.kosh.engine.TxnStatus
import com.prash.kosh.ui.theme.Expense
import com.prash.kosh.ui.theme.Income
import com.prash.kosh.ui.theme.Invest
import com.prash.kosh.ui.theme.Neutral
import com.prash.kosh.ui.theme.forTheme
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
private val dayTimeFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

fun formatDay(millis: Long): String = Instant.ofEpochMilli(millis).atZone(IST).format(dayFmt)
fun formatDayTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(IST).format(dayTimeFmt)
fun YearMonth.label(): String = format(monthFmt)
fun YearMonth.startMillis(): Long = atDay(1).atStartOfDay(IST).toInstant().toEpochMilli()
fun YearMonth.endMillis(): Long = plusMonths(1).atDay(1).atStartOfDay(IST).toInstant().toEpochMilli() - 1

fun typeLabel(type: String) = when (type) {
    "EXPENSE" -> "Expense"; "INCOME" -> "Income"; "TRANSFER" -> "Transfer"; "INVESTMENT" -> "Investment"; "REFUND" -> "Refund"; else -> type
}

fun statusLabel(status: String) = when (status) {
    TxnStatus.POSTED.name -> "Posted"
    TxnStatus.PENDING_VERIFICATION.name -> "Check"
    TxnStatus.NEEDS_REVIEW.name -> "Review"
    TxnStatus.SUSPECTED_DUPLICATE.name -> "Duplicate?"
    TxnStatus.REJECTED.name -> "Rejected"
    else -> status
}

@Composable
fun amountColor(type: String): Color = when (type) {
    "INCOME", "REFUND" -> Income
    "EXPENSE" -> Expense
    "INVESTMENT" -> Invest
    else -> Neutral
}.forTheme()

/** "Food & Dining › Delivery" for a category id. */
fun categoryPath(id: String?, byId: Map<String, CategoryEntity>): String {
    if (id == null) return "Uncategorised"
    val c = byId[id] ?: return id
    val parent = c.parentId?.let { byId[it]?.name }
    return if (parent != null) "$parent › ${c.name}" else c.name
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
        Text(month.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = month < YearMonth.now(IST)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

@Composable
fun StatusChip(status: String, confidence: Int) {
    if (status == TxnStatus.POSTED.name) return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bg, fg) = when (status) {
        TxnStatus.SUSPECTED_DUPLICATE.name, TxnStatus.NEEDS_REVIEW.name ->
            if (dark) Color(0xFF5C1F1A) to Color(0xFFFFDAD5) else Color(0xFFFCE8E6) to Color(0xFF8C1D18)
        TxnStatus.REJECTED.name -> if (dark) Color(0xFF3C4043) to Color(0xFFE8EAED) else Color(0xFFE8EAED) to Color(0xFF5F6368)
        else -> if (dark) Color(0xFF4D3A00) to Color(0xFFFFE08A) else Color(0xFFFFF4D6) to Color(0xFF7A5300)
    }
    Text(
        "${statusLabel(status)} · $confidence%",
        modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium,
    )
}

@Composable
fun TxnRow(t: TransactionEntity, categories: Map<String, CategoryEntity>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(amountColor(t.type).copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text((t.merchantName ?: typeLabel(t.type)).take(1).uppercase(), color = amountColor(t.type), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.merchantName ?: t.note ?: typeLabel(t.type), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${formatDay(t.dateMillis)} · ${categoryPath(t.categoryId, categories)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                StatusChip(t.status, t.confidence)
            }
        }
        Spacer(Modifier.width(8.dp))
        val sign = if (t.direction == "CREDIT") "+" else "−"
        Text("$sign${Money.format(t.amountPaise)}", color = amountColor(t.type), fontWeight = FontWeight.SemiBold)
    }
}

/** Ring chart of category shares, with an Indian-format legend. */
@Composable
fun DonutChart(slices: List<Pair<String, Long>>, colors: List<Color>, centerLabel: String) {
    val total = slices.sumOf { it.second }.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(140.dp)) {
                val stroke = 22.dp.toPx()
                val d = size.minDimension - stroke
                var start = -90f
                slices.forEachIndexed { i, (_, v) ->
                    val sweep = 360f * v / total
                    drawArc(colors[i % colors.size], start, (sweep - 1.2f).coerceAtLeast(0.5f), false,
                        topLeft = Offset(stroke / 2, stroke / 2), size = Size(d, d), style = Stroke(stroke))
                    start += sweep
                }
            }
            Text(centerLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { i, (name, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(colors[i % colors.size], CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("$name  ${(100 * v / total)}%", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Income vs expense bars for each month. */
@Composable
fun MonthlyBars(months: List<Triple<String, Long, Long>>) {
    val max = months.maxOfOrNull { maxOf(it.second, it.third) }?.coerceAtLeast(1) ?: 1
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val groupW = size.width / months.size.coerceAtLeast(1)
            val barW = groupW * 0.28f
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            months.forEachIndexed { i, (_, inc, exp) ->
                val x = i * groupW + groupW * 0.18f
                val hi = size.height * inc / max
                val he = size.height * exp / max
                drawRect(Income, Offset(x, size.height - hi), Size(barW, hi))
                drawRect(Expense, Offset(x + barW + 4.dp.toPx(), size.height - he), Size(barW, he))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            months.forEach { (label, _, _) ->
                Text(label, Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(Income, "Income"); LegendDot(Expense, "Expenses")
        }
    }
}

@Composable
private fun LegendDot(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

/**
 * Pick a category: top-level rows, with their subcategories expandable.
 * [kinds] limits the list (e.g. only EXPENSE categories for an expense).
 */
@Composable
fun CategoryPickerDialog(
    categories: List<CategoryEntity>, kinds: Set<String>, onPick: (CategoryEntity) -> Unit, onDismiss: () -> Unit,
) {
    val visible = categories.filter { !it.hidden && it.kind in kinds }
    val tops = visible.filter { it.parentId == null }
    var expanded by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Category") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(tops, key = { it.id }) { top ->
                    val subs = visible.filter { it.parentId == top.id }
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(top) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(top.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        if (subs.isNotEmpty()) {
                            IconButton(onClick = { expanded = if (expanded == top.id) null else top.id }) {
                                Icon(Icons.Default.ExpandMore, "Subcategories")
                            }
                        }
                    }
                    if (expanded == top.id) subs.forEach { s ->
                        Text(s.name, Modifier.fillMaxWidth().clickable { onPick(s) }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp))
                    }
                    HorizontalDivider()
                }
            }
        },
    )
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
