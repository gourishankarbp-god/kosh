package com.prash.kosh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prash.kosh.engine.Money
import com.prash.kosh.ui.theme.Expense
import com.prash.kosh.ui.theme.forTheme

/** Progress bar that turns amber past 80% and red past 100%. */
@Composable
fun BudgetBar(percent: Int, modifier: Modifier = Modifier) {
    val color = when {
        percent >= 100 -> Expense.forTheme()
        percent >= 80 -> Color(0xFFD08A1E)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth((percent.coerceIn(0, 100)) / 100f).height(8.dp).background(color, RoundedCornerShape(4.dp)))
    }
}

/** One series of bars with value labels on top and period labels below; [highlight] marks the selected bar. */
@Composable
fun SimpleBars(bars: List<Pair<String, Long>>, color: Color, highlight: Int = -1, valueText: (Long) -> String = { Money.compact(it) }) {
    val max = bars.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val grid = MaterialTheme.colorScheme.outlineVariant
    val dim = color.copy(alpha = 0.45f)
    Column {
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { (_, v) ->
                Text(if (v > 0) valueText(v) else "", Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width / bars.size.coerceAtLeast(1)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            bars.forEachIndexed { i, (_, v) ->
                val h = size.height * v / max
                drawRect(if (highlight < 0 || i == highlight) color else dim, Offset(i * w + w * 0.2f, size.height - h), Size(w * 0.6f, h))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { (label, _) ->
                Text(label, Modifier.weight(1f), fontSize = 11.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** A big figure with a label and an optional line underneath. */
@Composable
fun Figure(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Label on the left, value on the right. */
@Composable
fun KeyValue(label: String, value: String, bold: Boolean = false, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, color = valueColor)
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Text(text, Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun RowSpaced(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
