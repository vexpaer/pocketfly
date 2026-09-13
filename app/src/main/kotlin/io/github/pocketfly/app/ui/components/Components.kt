package io.github.pocketfly.app.ui.components

import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Compact metric card used across Home and Brain dashboards. */
@Composable
fun StatCard(
    label: String,
    value: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
)

/** Minimal multi-series line chart for activity timelines. */
@Composable
fun ActivityLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yMax: Float = 1f,
    gridLines: Int = 4,
) {
    val gridColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stepY = h / gridLines
        for (i in 0..gridLines) {
            drawLine(
                color = gridColor.copy(alpha = 0.25f),
                start = Offset(0f, i * stepY),
                end = Offset(w, i * stepY),
                strokeWidth = 1f,
            )
        }
        val maxPoints = series.maxOfOrNull { it.values.size } ?: 0
        if (maxPoints < 2) return@Canvas
        for (s in series) {
            if (s.values.size < 2) continue
            val n = s.values.size
            val path = androidx.compose.ui.graphics.Path()
            for ((i, v) in s.values.withIndex()) {
                val x = w * (i.toFloat() / (maxPoints - 1).coerceAtLeast(1)) +
                    w * (1f - (n - 1f) / (maxPoints - 1).coerceAtLeast(1)) * 0f
                val xx = w - w * (n - 1 - i).toFloat() / (maxPoints - 1).coerceAtLeast(1)
                val y = h - (v.coerceAtLeast(0f) / yMax).coerceAtMost(1f) * h
                if (i == 0) path.moveTo(xx, y) else path.lineTo(xx, y)
            }
            drawPath(path, color = s.color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
    }
}

/** Horizontal level meter for a single channel. */
@Composable
fun ChannelBar(
    label: String,
    value: Float,
    max: Float = 1f,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val fraction = (value / max).coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(4.dp),
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
        }
        Text(
            String.format("%.2f", value),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    format: String = "%.2f",
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                String.format(format, value),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

/** Small status dot with optional pulse, used on Home and Runtime Manager. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, modifier: Modifier = Modifier) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), RoundedCornerShape(4.dp)),
    )
}
