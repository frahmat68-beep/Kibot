package com.kibot.commandcenter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PieChartOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kibot.commandcenter.data.model.CommandCenterUiState
import com.kibot.commandcenter.data.model.ConnectionState
import com.kibot.commandcenter.data.model.DashboardTab
import com.kibot.commandcenter.data.model.ServerPaneState
import com.kibot.commandcenter.data.repository.CommandCenterRepository
import com.kibot.shared.models.CommandCenterHolding
import com.kibot.shared.models.CommandCenterOrder
import kotlinx.datetime.Instant
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ScreenBg = Color(0xFF07111F)
private val SurfaceA = Color(0xFF101B33)
private val SurfaceB = Color(0xFF0E162B)
private val SurfaceC = Color(0xFF111D38)
private val TextMuted = Color(0xFF8EA0C5)
private val TextSoft = Color(0xFFB7C3DD)
private val Green = Color(0xFF5CFFBA)
private val Red = Color(0xFFFF6B6B)
private val Yellow = Color(0xFFFFD166)
private val Blue = Color(0xFF85D6FF)
private val Cyan = Color(0xFF55D5FF)
private val Purple = Color(0xFFB48DFF)

@Composable
fun CommandCenterRoot(repository: CommandCenterRepository) {
    val state by repository.uiState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = ScreenBg,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0C1528)) {
                navItems().forEach { item ->
                    NavigationBarItem(
                        selected = state.selectedTab == item.tab,
                        onClick = { repository.selectTab(item.tab) },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF091222), ScreenBg)))
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeroCard(state)
            when (state.selectedTab) {
                DashboardTab.ALL -> DashboardTabContent(state)
                DashboardTab.KIDAX -> PortfolioTabContent(state)
                DashboardTab.KINANCE, DashboardTab.LEDGER -> LedgerTabContent(state)
            }
        }
    }
}

@Composable
private fun HeroCard(state: CommandCenterUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceA), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("KiBot", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Command Center", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                StatusPill(state.systemHealthLabel, healthColor(state.systemHealthLabel))
            }
            Text(state.totalEquityLabel, color = Color.White, style = MaterialTheme.typography.displaySmall)
            Text(
                "${state.pnlTodayLabel} • ${state.pnlTodayPctLabel}",
                color = pnlColor(state.pnlTodayLabel),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            StatusPill("AI LIMITED", Yellow)
        }
    }
}

@Composable
private fun DashboardTabContent(state: CommandCenterUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExchangeCard(state.kidax)
    }
}

@Composable
private fun PortfolioTabContent(state: CommandCenterUiState) {
    val summary = liveSummary(state)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReturnsStrip(summary)
        AssetNetWorthCard(state.equityHistory)
        AssetAllocationsCard(state)
        HoldingsSection(state)
    }
}

@Composable
private fun LedgerTabContent(state: CommandCenterUiState) {
    val orders = combinedOrders(state)
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceB), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val summary = liveSummary(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ledger", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Riwayat trade bersih setelah fee", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(summary.pnlTodayLabel, color = pnlColor(summary.pnlTodayLabel), style = MaterialTheme.typography.titleMedium)
                    Text(summary.pnlTodayPctLabel, color = pnlColor(summary.pnlTodayPctLabel), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (orders.isEmpty()) {
                Text("Belum ada trade.", color = TextMuted)
            } else {
                orders.forEach { order -> LedgerItem(order) }
            }
        }
    }
}

@Composable
private fun ReturnsStrip(summary: LiveSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatChip("1D", summary.pnlTodayLabel, summary.pnlTodayPctLabel, Modifier.weight(1f))
        StatChip("7D", summary.return7dLabel, summary.return7dPctLabel, Modifier.weight(1f))
        StatChip("30D", summary.return30dLabel, summary.return30dPctLabel, Modifier.weight(1f))
    }
}

@Composable
private fun AssetNetWorthCard(history: List<Double>) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceB), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Asset Net Worth", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Laporan harian", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(todayDateLabel(), color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
            if (history.size >= 2) {
                Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    val min = history.minOrNull() ?: 0.0
                    val max = history.maxOrNull() ?: 0.0
                    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
                    val path = androidx.compose.ui.graphics.Path()
                    history.forEachIndexed { index, value ->
                        val x = if (history.size == 1) 0f else (index.toFloat() / (history.size - 1).toFloat()) * size.width
                        val y = size.height - (((value - min) / range).toFloat() * size.height)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = Green, style = Stroke(width = 6f, cap = StrokeCap.Round))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("30D", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    Text("7D", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    Text("Today", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text("Menunggu riwayat equity tersinkron.", color = TextMuted)
            }
        }
    }
}

@Composable
private fun AssetAllocationsCard(state: CommandCenterUiState) {
    val allocations = buildAllocations(state)
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceB), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Asset Allocations", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("KiDax live allocation", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(allocationTotalLabel(state), color = TextSoft, style = MaterialTheme.typography.labelMedium)
            }
            if (allocations.isEmpty()) {
                Text("Belum ada komposisi aset yang bisa ditampilkan.", color = TextMuted)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.size(170.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = size.minDimension * 0.24f
                        val topLeft = Offset(stroke / 2f, stroke / 2f)
                        val arcSize = Size(size.width - stroke, size.height - stroke)
                        var startAngle = -90f
                        val total = allocations.sumOf { it.valueIdr }.takeIf { it > 0.0 } ?: 1.0
                        allocations.forEach { slice ->
                            val sweep = ((slice.valueIdr / total) * 360f).toFloat()
                            drawArc(
                                color = slice.color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = stroke, cap = StrokeCap.Round),
                            )
                            startAngle += sweep
                        }
                        drawCircle(color = SurfaceB, radius = size.minDimension * 0.29f)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Total", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                        Text(allocationTotalLabel(state), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    allocations.take(5).forEachIndexed { index, slice ->
                        AllocationLegendRow(slice = slice, rank = index + 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldingsSection(state: CommandCenterUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceB), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Holdings", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("Harga IDR dan PnL per koin", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "${state.kidax.snapshot?.holdingsDetailed.orEmpty().size} coins",
                    color = TextSoft,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            ExchangeHoldingList("KiDax", state.kidax.snapshot, Green)
        }
    }
}

@Composable
private fun ExchangeHoldingList(
    label: String,
    snapshot: com.kibot.shared.models.CommandCenterLiveSnapshot?,
    accent: Color,
) {
    val holdings = snapshot?.holdingsDetailed.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = accent, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text("Hold ${holdings.size}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        if (holdings.isEmpty()) {
            Text(
                if (snapshot == null) "Belum terkoneksi." else "Tidak ada holding.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        holdings.forEach { holding ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, accent.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .background(accent.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(holding.assetCode, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        "${holding.currentPriceLabel.ifBlank { holding.valueIdrLabel }} • ${holding.quantityLabel}",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(holding.pnlIdrLabel.ifBlank { "-" }, color = pnlColor(holding.pnlIdrLabel), style = MaterialTheme.typography.bodyMedium)
                    Text(holding.pnlPctLabel.ifBlank { "-" }, color = pnlColor(holding.pnlIdrLabel.ifBlank { holding.pnlPctLabel }), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AllocationLegendRow(slice: AllocationSlice, rank: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(slice.color),
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(slice.label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Text(slice.amountLabel, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(slice.percentageLabel, color = TextSoft, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun ExchangeCard(pane: ServerPaneState) {
    val syncLabel = pane.snapshot?.syncHealth?.name ?: pane.connectionState.name
    val accent = serverAccentColor(pane)
    val snapshot = pane.snapshot
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceB),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(26.dp)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(pane.label, color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                StatusPill(syncLabel, accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatusPill("Ping ${snapshot?.exchangePingMs ?: "--"}", stateLatencyColor(snapshot?.exchangePingMs))
                StatusPill(snapshot?.pnlTodayIdr ?: "+Rp0", pnlColor(snapshot?.pnlTodayIdr))
                StatusPill("Hold ${snapshot?.holdingsDetailed?.size ?: 0}", TextSoft)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(snapshotTotalIdrLabel(snapshot), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Net worth", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(snapshot?.pnlTodayIdr ?: "+Rp0", color = pnlColor(snapshot?.pnlTodayIdr), style = MaterialTheme.typography.titleMedium)
                    Text(snapshot?.pnlTodayPctLabel ?: "+0.0%", color = pnlColor(snapshot?.pnlTodayPctLabel), style = MaterialTheme.typography.labelSmall)
                }
            }
            val holdings = snapshot?.holdingsDetailed.orEmpty()
            if (holdings.isEmpty()) {
                Text(
                    "No holdings visible.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                holdings.take(2).forEach { holding ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(holding.assetCode, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${holding.currentPriceLabel.ifBlank { holding.valueIdrLabel }} • ${holding.pnlPctLabel.ifBlank { "-" }}",
                                color = TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(holding.pnlIdrLabel.ifBlank { "-" }, color = pnlColor(holding.pnlIdrLabel), style = MaterialTheme.typography.bodyMedium)
                            Text(holding.pnlPctLabel.ifBlank { "-" }, color = pnlColor(holding.pnlIdrLabel.ifBlank { holding.pnlPctLabel }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerItem(order: CommandCenterOrder) {
    val sideColor = orderSideColor(order.side, order.detail, order.pnlIdrLabel)
    val resultColor = orderResultColor(order.pnlIdrLabel, order.detail)
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceC), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(order.pair, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(formatLedgerTime(order.timestampEpochMs), color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
                StatusPill(order.side.uppercase(), sideColor)
            }
            if (order.status.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(order.status.uppercase(), resultColor)
                }
            }
            if (order.detail.isNotBlank()) {
                Text(
                    order.detail,
                    color = TextSoft,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (order.pnlIdrLabel.isNotBlank() || order.pnlPctLabel.isNotBlank()) {
                Text(
                    "PnL ${order.pnlIdrLabel.ifBlank { "-" }} • ${order.pnlPctLabel.ifBlank { "-" }}",
                    color = resultColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (order.side.equals("SELL", ignoreCase = true)) {
                Text("PnL pending", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatChip(title: String, amount: String, pct: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceB), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            Text(amount, color = pnlColor(amount), style = MaterialTheme.typography.titleMedium)
            Text(pct, color = pnlColor(pct), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color = TextSoft) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f)), shape = RoundedCornerShape(999.dp)) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun navItems() = listOf(
    NavItem(DashboardTab.ALL, "Dashboard", Icons.Outlined.Dashboard),
    NavItem(DashboardTab.KIDAX, "Portfolio", Icons.Outlined.PieChartOutline),
    NavItem(DashboardTab.KINANCE, "Logs", Icons.AutoMirrored.Outlined.ListAlt),
)

private data class NavItem(val tab: DashboardTab, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private data class LiveSummary(
    val totalEquityLabel: String,
    val pnlTodayLabel: String,
    val pnlTodayPctLabel: String,
    val return7dLabel: String,
    val return7dPctLabel: String,
    val return30dLabel: String,
    val return30dPctLabel: String,
)

private data class AllocationSlice(
    val label: String,
    val valueIdr: Double,
    val color: Color,
    val amountLabel: String,
    val percentageLabel: String,
)

private fun buildAllocations(state: CommandCenterUiState): List<AllocationSlice> {
    val snapshots = listOfNotNull(state.kidax.snapshot)
    if (snapshots.isEmpty()) return emptyList()
    val totals = linkedMapOf<String, Double>()
    snapshots.forEach { snapshot ->
        snapshot.holdingsDetailed.forEach { holding ->
            val value = holdingValueIdr(snapshot, holding)
            if (value > 0.0) {
                totals[holding.assetCode] = (totals[holding.assetCode] ?: 0.0) + value
            }
        }
    }
    val holdingsTotal = totals.values.sum()
    val totalValue = snapshots.sumOf { snapshotTotalIdr(it) }
    val cashValue = (totalValue - holdingsTotal).coerceAtLeast(0.0)
    val percentFormatter = DecimalFormat("#,##0.0", DecimalFormatSymbols(Locale("id", "ID")))
    val slices = totals.entries
        .sortedByDescending { it.value }
        .mapIndexed { index, entry ->
            AllocationSlice(
                label = entry.key,
                valueIdr = entry.value,
                color = allocationColor(index),
                amountLabel = formatIdr(entry.value),
                percentageLabel = "${percentFormatter.format((entry.value / totalValue.coerceAtLeast(1.0)) * 100.0)}%",
            )
        }
        .toMutableList()
    if (cashValue > 0.0) {
        slices.add(
            AllocationSlice(
                label = "Cash",
                valueIdr = cashValue,
                color = Blue,
                amountLabel = formatIdr(cashValue),
                percentageLabel = "${percentFormatter.format((cashValue / totalValue.coerceAtLeast(1.0)) * 100.0)}%",
            ),
        )
    }
    return slices.sortedByDescending { it.valueIdr }
}

private fun allocationTotalLabel(state: CommandCenterUiState): String {
    val total = listOfNotNull(state.kidax.snapshot).sumOf { snapshotTotalIdr(it) }
    return formatIdr(total)
}

private fun snapshotTotalIdrLabel(snapshot: com.kibot.shared.models.CommandCenterLiveSnapshot?): String {
    return formatIdr(snapshotTotalIdr(snapshot))
}

private fun snapshotTotalIdr(snapshot: com.kibot.shared.models.CommandCenterLiveSnapshot?): Double {
    if (snapshot == null) return 0.0
    val raw = snapshot.totalValueIdr.ifBlank { snapshot.portfolioValueIdr }
    return labelToIdr(raw, snapshot.referenceQuoteAssetPriceIdr)
}

private fun holdingValueIdr(snapshot: com.kibot.shared.models.CommandCenterLiveSnapshot, holding: CommandCenterHolding): Double {
    return labelToIdr(holding.valueIdrLabel, snapshot.referenceQuoteAssetPriceIdr)
}

private fun labelToIdr(label: String, referenceQuoteAssetPriceIdr: Double?): Double {
    val parsed = parseIdr(label)
    return if (label.contains("USDT", ignoreCase = true) || label.contains("USDC", ignoreCase = true)) {
        parsed * (referenceQuoteAssetPriceIdr ?: 16_000.0)
    } else parsed
}

private fun combinedOrders(state: CommandCenterUiState): List<CommandCenterOrder> {
    return state.kidax.snapshot?.recentOrders.orEmpty()
        .filterNot {
            it.status.equals("CANCELED", ignoreCase = true) ||
                it.status.equals("REJECTED", ignoreCase = true) ||
                it.detail.contains("cancel", ignoreCase = true) ||
                it.detail.contains("reject", ignoreCase = true)
        }
        .sortedByDescending { it.timestampEpochMs }
        .take(16)
}

private fun allocationColor(index: Int): Color = when (index % 5) {
    0 -> Cyan
    1 -> Green
    2 -> Yellow
    3 -> Purple
    else -> Blue
}

private fun stateLatencyColor(value: String?): Color = when {
    value.isNullOrBlank() -> TextMuted
    value.contains("--") -> TextMuted
    else -> value.filter { it.isDigit() }.toIntOrNull()?.let { ping ->
        when {
            ping >= 500 -> Red
            ping >= 200 -> Yellow
            else -> Green
        }
    } ?: TextMuted
}

private fun serverAccentColor(pane: ServerPaneState): Color {
    val effective = pane.snapshot?.effectiveState?.name.orEmpty()
    val health = pane.snapshot?.syncHealth?.name.orEmpty()
    val connection = pane.connectionState
    return when {
        connection == ConnectionState.DISCONNECTED -> Red
        effective.contains("SAFE_MODE", ignoreCase = true) || health.contains("BROKEN", ignoreCase = true) -> Red
        effective.contains("STARTING", ignoreCase = true) || health.contains("DEGRADED", ignoreCase = true) || connection == ConnectionState.RECONNECTING -> Yellow
        effective.contains("RUNNING", ignoreCase = true) && health.contains("HEALTHY", ignoreCase = true) -> Green
        else -> TextSoft
    }
}

private fun healthColor(label: String): Color = when {
    label.contains("HEALTHY", ignoreCase = true) -> Green
    label.contains("DEGRADED", ignoreCase = true) -> Yellow
    label.contains("WARM", ignoreCase = true) -> Yellow
    label.contains("BOOT", ignoreCase = true) -> Yellow
    else -> Red
}

private fun pnlColor(value: String?): Color = when {
    value.isNullOrBlank() -> TextSoft
    value.startsWith("-") -> Red
    value == "0" || value == "+Rp0" || value == "Rp0" -> TextSoft
    else -> Green
}

private fun orderSideColor(side: String, detail: String, pnlIdrLabel: String = ""): Color = when {
    side.equals("BUY", ignoreCase = true) -> TextSoft
    side.equals("SELL", ignoreCase = true) && pnlIdrLabel.startsWith("-") -> Red
    side.equals("SELL", ignoreCase = true) && pnlIdrLabel.isNotBlank() && !pnlIdrLabel.startsWith("-") -> Green
    side.equals("SELL", ignoreCase = true) && detail.contains("Untung", ignoreCase = true) -> Green
    side.equals("SELL", ignoreCase = true) && detail.contains("Rugi", ignoreCase = true) -> Red
    side.equals("SELL", ignoreCase = true) && (detail.contains("loss", ignoreCase = true) || detail.contains("rugi", ignoreCase = true)) -> Red
    side.equals("SELL", ignoreCase = true) && (detail.contains("profit", ignoreCase = true) || detail.contains("untung", ignoreCase = true)) -> Green
    side.equals("SELL", ignoreCase = true) -> TextMuted
    else -> TextSoft
}

private fun orderResultColor(pnlIdrLabel: String, detail: String): Color = when {
    pnlIdrLabel.startsWith("-") -> Red
    pnlIdrLabel.isNotBlank() && pnlIdrLabel != "-Rp0" && !pnlIdrLabel.startsWith("-") -> Green
    detail.contains("rugi", ignoreCase = true) || detail.contains("loss", ignoreCase = true) -> Red
    detail.contains("untung", ignoreCase = true) || detail.contains("profit", ignoreCase = true) -> Green
    else -> TextMuted
}

private fun liveSummary(state: CommandCenterUiState): LiveSummary = LiveSummary(
    totalEquityLabel = state.totalEquityLabel,
    pnlTodayLabel = state.pnlTodayLabel,
    pnlTodayPctLabel = state.pnlTodayPctLabel,
    return7dLabel = state.return7dLabel,
    return7dPctLabel = state.return7dPctLabel,
    return30dLabel = state.return30dLabel,
    return30dPctLabel = state.return30dPctLabel,
)

private fun parseIdr(value: String?): Double {
    val raw = value.orEmpty().trim()
    val numeric = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
    val normalized = when {
        raw.contains("USDT", ignoreCase = true) || raw.contains("USDC", ignoreCase = true) ->
            numeric.replace(",", "")
        else ->
            numeric.replace(".", "").replace(",", ".")
    }
    return normalized.toDoubleOrNull() ?: 0.0
}

private fun formatIdr(value: Double): String {
    val formatter = DecimalFormat("#,##0", DecimalFormatSymbols(Locale("id", "ID")))
    return "Rp${formatter.format(value.coerceAtLeast(0.0).toLong())}"
}

private fun formatLedgerTime(epochMs: Long): String {
    return runCatching {
        val instant = Instant.fromEpochMilliseconds(epochMs)
        instant.toString().substring(0, 16).replace('T', ' ')
    }.getOrDefault("time unavailable")
}

private fun todayDateLabel(): String {
    val formatter = DateTimeFormatter.ofPattern("dd/MM", Locale("id", "ID"))
    return "Updated ${java.time.LocalDate.now(ZoneId.systemDefault()).format(formatter)}"
}
