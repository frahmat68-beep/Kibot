package com.kibot.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kibot.android.data.*
import com.kibot.android.ui.components.*
import com.kibot.android.ui.theme.*

@Composable
fun PortfolioScreen(
    botState: BotState,
    modifier: Modifier = Modifier
) {
    var selectedTimeRange by remember { mutableStateOf(TimeRange.DAY_7) }
    
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Return Summary Card
        item {
            ReturnSummaryCard(
                returnSummary = botState.returnSummary
            )
        }
        
        // Net Worth Chart Card
        item {
            NetWorthChartCard(
                data = botState.netWorthHistory,
                selectedRange = selectedTimeRange,
                onRangeSelected = { selectedTimeRange = it }
            )
        }
        
        // Asset Allocation Card
        item {
            AssetAllocationCard(
                allocations = botState.assetAllocation
            )
        }
        
        // Holdings Card
        item {
            HoldingsCard(
                positions = botState.positions
            )
        }
        
        // Spacer for bottom nav
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun ReturnSummaryCard(
    returnSummary: ReturnSummary,
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
                .padding(20.dp)
        ) {
            Text(
                text = "Return Summary",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ReturnItem(
                    label = "Today",
                    valuePct = returnSummary.day1,
                    valueIdr = returnSummary.day1Idr,
                    modifier = Modifier.weight(1f)
                )
                
                VerticalDivider(
                    modifier = Modifier.height(50.dp),
                    color = DarkSurfaceVariant
                )
                
                ReturnItem(
                    label = "7D",
                    valuePct = returnSummary.day7,
                    valueIdr = returnSummary.day7Idr,
                    modifier = Modifier.weight(1f)
                )
                
                VerticalDivider(
                    modifier = Modifier.height(50.dp),
                    color = DarkSurfaceVariant
                )
                
                ReturnItem(
                    label = "30D",
                    valuePct = returnSummary.day30,
                    valueIdr = returnSummary.day30Idr,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ReturnItem(
    label: String,
    valuePct: Double,
    valueIdr: Double,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextTertiary
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = formatPercent(valuePct),
            style = MaterialTheme.typography.titleLarge,
            color = if (valuePct >= 0) ProfitGreen else LossRed,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = formatRupiah(valueIdr),
            style = MaterialTheme.typography.labelSmall,
            color = if (valueIdr >= 0) ProfitGreen else LossRed,
        )
    }
}

enum class TimeRange(val label: String, val days: Int) {
    DAY_1("1D", 1),
    DAY_7("7D", 7),
    DAY_30("30D", 30),
    ALL("All", -1)
}

@Composable
private fun NetWorthChartCard(
    data: List<NetWorthPoint>,
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredData = remember(data, selectedRange) {
        if (selectedRange == TimeRange.ALL || data.isEmpty()) {
            data
        } else {
            val cutoffTime = System.currentTimeMillis() - (selectedRange.days * 24 * 60 * 60 * 1000L)
            data.filter { it.timestamp >= cutoffTime }
        }
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Net Worth",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TimeRange.entries.forEach { range ->
                        FilterChip(
                            selected = selectedRange == range,
                            onClick = { onRangeSelected(range) },
                            label = {
                                Text(
                                    text = range.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = KiCrypBlue,
                                selectedLabelColor = TextPrimary,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextSecondary
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (filteredData.isEmpty()) {
                EmptyState(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = null,
                            tint = TextDisabled,
                            modifier = Modifier.size(48.dp)
                        )
                    },
                    title = "No Data Available",
                    subtitle = "Chart will appear when data is collected"
                )
            } else {
                // Current value
                val currentValue = filteredData.lastOrNull()?.value ?: 0.0
                val previousValue = filteredData.firstOrNull()?.value ?: currentValue
                val change = if (previousValue != 0.0) ((currentValue - previousValue) / previousValue) * 100 else 0.0
                
                Text(
                    text = formatRupiah(currentValue),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "${formatPercent(change)} in ${selectedRange.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (change >= 0) ProfitGreen else LossRed
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Line chart
                LineChart(
                    data = filteredData.map { it.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    lineColor = if (change >= 0) ProfitGreen else LossRed
                )
            }
        }
    }
}

@Composable
private fun LineChart(
    data: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = ChartLine
) {
    val animatedProgress = remember { Animatable(0f) }
    
    LaunchedEffect(data) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(1000, easing = EaseOutCubic)
        )
    }
    
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        
        val width = size.width
        val height = size.height
        val padding = 8.dp.toPx()
        
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        
        val max = data.maxOrNull() ?: 0.0
        val min = data.minOrNull() ?: 0.0
        val range = if (max != min) max - min else 1.0
        
        // Draw grid lines
        val gridColor = ChartGrid
        for (i in 0..4) {
            val y = padding + (chartHeight / 4) * i
            drawLine(
                color = gridColor,
                start = Offset(padding, y),
                end = Offset(width - padding, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        
        // Draw line
        val path = Path()
        val visibleDataCount = (data.size * animatedProgress.value).toInt().coerceAtLeast(2)
        val visibleData = data.take(visibleDataCount)
        
        visibleData.forEachIndexed { index, value ->
            val x = padding + (index.toFloat() / (data.size - 1).coerceAtLeast(1)) * chartWidth
            val y = padding + chartHeight - ((value - min) / range * chartHeight).toFloat()
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
        
        // Draw point at end
        if (visibleData.isNotEmpty()) {
            val lastIndex = visibleData.size - 1
            val lastX = padding + (lastIndex.toFloat() / (data.size - 1).coerceAtLeast(1)) * chartWidth
            val lastY = padding + chartHeight - ((visibleData.last() - min) / range * chartHeight).toFloat()
            
            drawCircle(
                color = lineColor,
                radius = 4.dp.toPx(),
                center = Offset(lastX, lastY)
            )
        }
    }
}

@Composable
private fun AssetAllocationCard(
    allocations: List<AssetAllocation>,
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
                .padding(20.dp)
        ) {
            Text(
                text = "Asset Allocation",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (allocations.isEmpty()) {
                EmptyState(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.PieChart,
                            contentDescription = null,
                            tint = TextDisabled,
                            modifier = Modifier.size(48.dp)
                        )
                    },
                    title = "No Assets",
                    subtitle = "Asset distribution will appear here"
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pie chart
                    PieChart(
                        allocations = allocations,
                        modifier = Modifier.size(140.dp)
                    )
                    
                    // Legend
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        allocations.take(5).forEachIndexed { index, allocation ->
                            AllocationLegendItem(
                                color = PieColors[index % PieColors.size],
                                coin = allocation.coin,
                                percentage = allocation.percentage
                            )
                        }
                        
                        if (allocations.size > 5) {
                            Text(
                                text = "+${allocations.size - 5} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PieChart(
    allocations: List<AssetAllocation>,
    modifier: Modifier = Modifier
) {
    val animatedProgress = remember { Animatable(0f) }
    
    LaunchedEffect(allocations) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(1000, easing = EaseOutCubic)
        )
    }
    
    Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val radius = canvasSize / 2
        val center = Offset(size.width / 2, size.height / 2)
        
        var startAngle = -90f
        
        allocations.forEachIndexed { index, allocation ->
            val sweepAngle = (allocation.percentage / 100f * 360f * animatedProgress.value).toFloat()
            val color = PieColors[index % PieColors.size]
            
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2)
            )
            
            startAngle += sweepAngle
        }
        
        // Center circle for donut effect
        drawCircle(
            color = DarkSurface,
            radius = radius * 0.6f,
            center = center
        )
    }
}

@Composable
private fun AllocationLegendItem(
    color: Color,
    coin: String,
    percentage: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = coin.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        
        Text(
            text = "${String.format("%.1f", percentage)}%",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun HoldingsCard(
    positions: List<Position>,
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
                .padding(20.dp)
        ) {
            Text(
                text = "Holdings",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (positions.isEmpty()) {
                EmptyState(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = TextDisabled,
                            modifier = Modifier.size(48.dp)
                        )
                    },
                    title = "No Holdings",
                    subtitle = "Your positions will appear here"
                )
            } else {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Asset",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.weight(2f)
                    )
                    Text(
                        text = "Buy Price",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "Current",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "PnL",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.End
                    )
                }
                
                HorizontalDivider(color = DarkSurfaceVariant)
                
                positions
                    .sortedByDescending { it.valueIdr }
                    .forEach { position ->
                    HoldingRow(position = position)
                }
            }
        }
    }
}

@Composable
private fun HoldingRow(
    position: Position,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Asset info
        Row(
            modifier = Modifier.weight(2f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val assetCode = position.pair.substringBefore("_").uppercase()
            val currentValue = position.currentPrice * position.amount
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(KiCrypBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = assetCode.take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = KiCrypBlue,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column {
                Text(
                    text = assetCode,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${decimalFormat.format(position.amount)} • ${formatRupiah(position.valueIdr.takeIf { it > 0.0 } ?: currentValue)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        }
        
        // Buy price
        Text(
            text = formatRupiah(position.buyPrice),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End
        )
        
        // Current price
        Text(
            text = formatRupiah(position.currentPrice),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            modifier = Modifier.weight(1.5f),
            textAlign = TextAlign.End
        )
        
        // PnL
        Text(
            text = "${formatRupiah(position.pnl)}\n${formatPercent(position.pnlPercent)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (position.pnlPercent >= 0) ProfitGreen else LossRed,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1.2f),
            textAlign = TextAlign.End
        )
    }
}
