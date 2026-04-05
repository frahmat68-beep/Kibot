package com.kibot.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
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
    val isNegative = value < 0
    val absValue = kotlin.math.abs(value)
    val formatted = NumberFormat.getNumberInstance(Locale("id", "ID")).format(absValue.toLong())
    return if (isNegative) "-Rp $formatted" else "Rp $formatted"
}

fun formatPercent(value: Double, showSign: Boolean = true): String {
    val isNegative = value < 0
    val absValue = kotlin.math.abs(value)
    val sign = when {
        isNegative -> "-"
        showSign && value > 0 -> "+"
        else -> ""
    }
    return "$sign${String.format("%.2f", absValue)}%"
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
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    label: String = "Exchange"
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
            contentDescription = "$label Ping",
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = if (showLabel) "$label ${pingMs}ms" else "${pingMs}ms",
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
    val isPositive = pnlToday >= 0
    val borderColor = if (isPositive) ProfitGreen else LossRed
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = borderColor.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            ),
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
                text = "Today's Balance",
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
                        text = "Return Today",
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
                
                Divider(
                    modifier = Modifier
                        .height(40.dp)
                        .width(1.dp),
                    color = DarkSurfaceVariant
                )
                
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
            }
        }
    }
}

@Composable
fun BotStatusCard(
    name: String,
    subtitle: String,
    status: String,
    pingMs: Long? = null,
    aiStatus: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val isOnline = status.lowercase() == "online"
    val borderColor = if (isOnline) ProfitGreen else LossRed
    val borderOpacity = if (isOnline) 0.6f else 0.4f
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = borderColor.copy(alpha = borderOpacity),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row with status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Pulsing online indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = borderColor,
                                shape = CircleShape
                            )
                    )
                    
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
                        onToggle(newState)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ProfitGreen,
                        checkedTrackColor = ProfitGreen.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextDisabled,
                        uncheckedTrackColor = DarkSurfaceVariant
                    ),
                    modifier = Modifier.scale(0.8f)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Status details row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = DarkSurfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ping indicator
                pingMs?.takeIf { it > 0L }?.let { livePing ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Ping",
                            tint = StatusOnline,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${livePing}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                
                // AI Status
                val isAIActive = aiStatus.lowercase() in listOf("active", "online", "enabled")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isAIActive) Icons.Default.Psychology else Icons.Default.PsychologyAlt,
                        contentDescription = "AI Status",
                        tint = if (isAIActive) NeutralBlue else TextDisabled,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = when {
                            aiStatus.lowercase() == "active" || aiStatus.lowercase() == "online" -> "AI Online"
                            aiStatus.lowercase() == "limited" -> "AI Limited"
                            else -> "AI Offline"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAIActive) NeutralBlue else TextDisabled,
                        fontSize = 10.sp
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Status badge
                Surface(
                    color = borderColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = status.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = borderColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            // Additional content (holdings, positions, etc.)
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
    label: String = "server",
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
            text = if (isConnected) "Connected to ${label.uppercase()}" else "Disconnected - Reconnecting...",
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}
