package com.kibot.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kibot.android.ui.theme.*
import java.text.NumberFormat
import java.util.Locale

// Currency formatters
val currencyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale("id", "ID")).apply {
    maximumFractionDigits = 0
}

val percentFormat: NumberFormat = NumberFormat.getPercentInstance().apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}

val decimalFormat: NumberFormat = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}

fun formatRupiah(value: Double): String {
    return "Rp ${NumberFormat.getNumberInstance(Locale("id", "ID")).format(value.toLong())}"
}

fun formatPercent(value: Double, showSign: Boolean = true): String {
    val sign = if (showSign && value >= 0) "+" else ""
    return "$sign${String.format("%.2f", value)}%"
}

@Composable
fun StatusDot(
    status: String,
    modifier: Modifier = Modifier
) {
    val color = when (status.lowercase()) {
        "online" -> StatusOnline
        "degraded" -> StatusDegraded
        else -> StatusOffline
    }
    
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (status == "online") alpha else 1f))
    )
}

@Composable
fun PingIndicator(
    pingMs: Long,
    modifier: Modifier = Modifier
) {
    val color = when {
        pingMs < 100 -> PingExcellent
        pingMs < 500 -> PingGood
        else -> PingPoor
    }
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.NetworkCheck,
            contentDescription = "Ping",
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "${pingMs}ms",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
fun BalanceCard(
    totalBalance: Double,
    totalReturn: Double,
    pnlToday: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Total Balance",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = formatRupiah(totalBalance),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Total Return",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Text(
                        text = formatPercent(totalReturn),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (totalReturn >= 0) ProfitGreen else LossRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Divider(
                    modifier = Modifier
                        .height(40.dp)
                        .width(1.dp),
                    color = DarkSurfaceVariant
                )
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PnL Today",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Text(
                        text = formatRupiah(pnlToday),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (pnlToday >= 0) ProfitGreen else LossRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun BotStatusCard(
    name: String,
    subtitle: String,
    status: String,
    pingMs: Long,
    aiStatus: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(status = status)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
                
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { newState ->
                        // Request confirmation from parent before actually toggling
                        onToggle(newState)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ProfitGreen,
                        checkedTrackColor = ProfitGreen.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextDisabled,
                        uncheckedTrackColor = DarkSurfaceVariant
                    ),
                    modifier = Modifier.scale(1.1f)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PingIndicator(pingMs = pingMs)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (aiStatus == "active") Icons.Default.Psychology else Icons.Default.PsychologyAlt,
                            contentDescription = "AI Status",
                            tint = if (aiStatus == "active") NeutralBlue else TextDisabled,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = aiStatus.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (aiStatus == "active") NeutralBlue else TextDisabled
                        )
                    }
                }
                
                Text(
                    text = status.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (status.lowercase()) {
                        "online" -> StatusOnline
                        "degraded" -> StatusDegraded
                        else -> StatusOffline
                    },
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Additional content (holdings, etc.)
            content()
        }
    }
}

@Composable
fun MiniLineChart(
    data: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = ChartLine,
    fillColor: Color = ChartFill
) {
    if (data.isEmpty()) return
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        val max = data.maxOrNull() ?: 0.0
        val min = data.minOrNull() ?: 0.0
        val range = if (max != min) max - min else 1.0
        
        val path = Path()
        val fillPath = Path()
        
        data.forEachIndexed { index, value ->
            val x = (index.toFloat() / (data.size - 1).coerceAtLeast(1)) * width
            val y = height - ((value - min) / range * height).toFloat()
            
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        
        fillPath.lineTo(width, height)
        fillPath.close()
        
        // Draw fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(fillColor, Color.Transparent)
            )
        )
        
        // Draw line
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun HoldingItem(
    coin: String,
    amount: Double,
    price: Double,
    pnl: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(KiBotBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = coin.take(2).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = KiBotBlue,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = coin.uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${decimalFormat.format(amount)} @ ${formatRupiah(price)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        }
        
        Text(
            text = formatPercent(pnl),
            style = MaterialTheme.typography.bodyMedium,
            color = if (pnl >= 0) ProfitGreen else LossRed,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun LoadingOverlay(
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(DarkBackground.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = KiBotBlue,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
fun ConnectionBanner(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) ProfitGreen.copy(alpha = 0.15f) else LossRed.copy(alpha = 0.15f),
        animationSpec = tween(300),
        label = "bannerColor"
    )
    
    val textColor by animateColorAsState(
        targetValue = if (isConnected) ProfitGreen else LossRed,
        animationSpec = tween(300),
        label = "textColor"
    )
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isConnected) "Connected to KiDax" else "Disconnected - Reconnecting...",
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}
