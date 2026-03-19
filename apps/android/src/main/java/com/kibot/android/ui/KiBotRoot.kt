package com.kibot.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue

private enum class RootTab(val title: String) {
    Dashboard("Dashboard"),
    Portfolio("Portfolio"),
    Control("Control"),
    Logs("Logs"),
}

@Composable
fun KiBotRoot(
    state: KiBotUiState,
    onToggleBot: () -> Unit,
    onCommand: (EngineAction) -> Unit,
) {
    var currentTab by rememberSaveable { mutableStateOf(RootTab.Dashboard) }
    val background = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceContainerLowest,
            MaterialTheme.colorScheme.surfaceContainerLowest,
            MaterialTheme.colorScheme.surface,
        ),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                RootTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    RootTab.Dashboard -> Icons.Outlined.Dashboard
                                    RootTab.Portfolio -> Icons.Outlined.AccountBalanceWallet
                                    RootTab.Control -> Icons.Outlined.Sync
                                    RootTab.Logs -> Icons.AutoMirrored.Outlined.Notes
                                },
                                contentDescription = tab.title,
                            )
                        },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(padding),
        ) {
            when (currentTab) {
                RootTab.Dashboard -> DashboardScreen(state = state, onToggleBot = onToggleBot)
                RootTab.Portfolio -> PortfolioScreen(state = state)
                RootTab.Control -> EngineControlScreen(state = state, onCommand = onCommand)
                RootTab.Logs -> LogsScreen(state = state)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    state: KiBotUiState,
    onToggleBot: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { HeroCard(state = state, onToggleBot = onToggleBot) }
        item { PairRadarCard(modifier = Modifier.fillMaxWidth(), state = state) }
        item {
            LiveActivityCard(
                modifier = Modifier.fillMaxWidth(),
                state = state,
            )
        }
    }
}

@Composable
private fun PortfolioScreen(state: KiBotUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PortfolioSectionCard(modifier = Modifier.fillMaxWidth(), state = state) }
        item {
            HoldingsPreviewCard(
                modifier = Modifier.fillMaxWidth(),
                state = state,
            )
        }
    }
}

@Composable
private fun PortfolioSectionCard(
    modifier: Modifier = Modifier,
    state: KiBotUiState,
) {
    val portfolio = state.portfolio
    SurfaceCard(modifier = modifier) {
        Text("Portfolio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PortfolioMetricTile(
                modifier = Modifier.weight(1f),
                label = "Return 1D",
                value = portfolio.oneDayReturnLabel,
                caption = portfolio.oneDayReturnPctLabel,
                tint = pnlColor(portfolio.oneDayReturnLabel),
            )
            PortfolioMetricTile(
                modifier = Modifier.weight(1f),
                label = "Return 7D",
                value = portfolio.sevenDayReturnLabel,
                caption = portfolio.sevenDayReturnPctLabel,
                tint = pnlColor(portfolio.sevenDayReturnLabel),
            )
            PortfolioMetricTile(
                modifier = Modifier.weight(1f),
                label = "Cash Ready",
                value = portfolio.cashReadyLabel,
                caption = portfolio.cashReadyPctLabel,
                tint = Color(0xFF60A5FA),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Unrealized ${portfolio.totalUnrealizedLabel}", pnlColor(portfolio.totalUnrealizedLabel))
            StatusChip(portfolio.concentrationLabel, Color(0xFF38BDF8))
        }
        Surface(
            color = Color.White.copy(alpha = 0.04f),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Asset Net Worth",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        portfolio.lastUpdatedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PortfolioSparkline(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    points = portfolio.chartPoints,
                    tint = pnlColor(portfolio.sevenDayReturnLabel),
                )
            }
        }
        if (portfolio.allocations.isNotEmpty()) {
            Text("Asset Allocation", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            AllocationStrip(items = portfolio.allocations)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                portfolio.allocations.forEach { allocation ->
                    Surface(
                        color = Color.White.copy(alpha = 0.04f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(assetAccent(allocation.label), CircleShape),
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(allocation.label, fontWeight = FontWeight.Bold)
                                    Text(
                                        allocation.valueLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                allocation.pctLabel,
                                color = assetAccent(allocation.label),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortfolioMetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    caption: String,
    tint: Color,
) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                color = tint,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = tint.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PortfolioSparkline(
    modifier: Modifier = Modifier,
    points: List<PortfolioTrendPointUi>,
    tint: Color,
) {
    val safePoints = points.ifEmpty { listOf(PortfolioTrendPointUi("Hari ini", 0.0)) }
    val values = safePoints.map { it.valueIdr }
    val min = values.minOrNull() ?: 0.0
    val max = values.maxOrNull() ?: 0.0
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = modifier) {
            val path = Path()
            val stepX = if (safePoints.size > 1) size.width / safePoints.lastIndex else 0f
            safePoints.forEachIndexed { index, point ->
                val x = if (safePoints.size > 1) stepX * index else size.width / 2f
                val normalized = ((point.valueIdr - min) / range).toFloat()
                val y = size.height - (normalized * (size.height * 0.78f)) - (size.height * 0.10f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(
                    color = tint,
                    radius = if (index == safePoints.lastIndex) 8f else 5f,
                    center = Offset(x, y),
                )
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = 5f, cap = StrokeCap.Round),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf(
                safePoints.firstOrNull()?.label.orEmpty(),
                safePoints.getOrNull(safePoints.lastIndex / 2)?.label.orEmpty(),
                safePoints.lastOrNull()?.label.orEmpty(),
            ).forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AllocationStrip(items: List<PortfolioAllocationUi>) {
    val visibleItems = items.filter { it.pct > 0.0 }
    if (visibleItems.isEmpty()) return
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
        ) {
            visibleItems.forEach { item ->
                Box(
                    modifier = Modifier
                        .weight(item.pct.toFloat().coerceAtLeast(0.05f))
                        .fillMaxHeight()
                        .background(assetAccent(item.label)),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: KiBotUiState,
    onToggleBot: () -> Unit,
) {
    val pnlColor = pnlColor(state.pnlTodayIdr)
    val pingState = pingVisual(state.isBotRunning, state.internetPingLabel)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1A2850),
                            Color(0xFF131E3C),
                            Color(0xFF0F172A),
                        ),
                    ),
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("KiBot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Surface(
                    color = pingState.tint.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (pingState.online) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                            contentDescription = "Ping internet",
                            tint = pingState.tint,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            pingState.label,
                            color = pingState.tint,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Text(
                state.modalSaatIniIdr,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.pnlTodayIdr,
                    style = MaterialTheme.typography.headlineSmall,
                    color = pnlColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    color = pnlColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        state.pnlTodayPctLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = pnlColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            FilledTonalButton(
                onClick = onToggleBot,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (state.isBotRunning) Color(0xFF2B3557) else Color(0xFF1E40AF),
                    contentColor = Color(0xFFF8FAFC),
                ),
            ) {
                Text(
                    if (state.isBotRunning) "Matikan Bot" else "Nyalakan Bot",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AssetBadge(
    symbol: String,
) {
    val normalized = symbol.ifBlank { "?" }
    val accent = assetAccent(normalized)
    Surface(
        color = accent.copy(alpha = 0.18f),
        shape = CircleShape,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                normalized.take(2),
                color = accent,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PairRadarCard(
    modifier: Modifier = Modifier,
    state: KiBotUiState,
) {
    SurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssetBadge(symbol = visiblePairLabel(state).substringBefore('_').uppercase())
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        visiblePairLabel(state),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.isBotRunning) "Pair aktif bot" else "Radar pair teratas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusChip(
                label = if (state.isBotRunning) "LIVE" else "OFF",
                tint = if (state.isBotRunning) Color(0xFF2DD881) else Color(0xFF94A3B8),
            )
        }
        Surface(
            color = Color.White.copy(alpha = 0.04f),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadarChip(label = scanCountLabel(state))
                    RadarChip(label = "${radarPairs(state).size} kandidat")
                }
                if (radarPairs(state).isNotEmpty()) {
                    Text(
                        radarPairs(state).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    state.syncPathLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HoldingsPreviewCard(
    modifier: Modifier = Modifier,
    state: KiBotUiState,
) {
    SurfaceCard(modifier = modifier) {
        Text("Holdings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.positions.isEmpty()) {
            Surface(
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    "Belum ada aset aktif di akun bot.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 212.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.positions.forEach { position ->
                    val perCoinPnl = position.pnl.takeIf { it.isNotBlank() }
                    Surface(
                        color = Color.White.copy(alpha = 0.04f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AssetBadge(symbol = position.pair.uppercase().take(6))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(position.pair.uppercase(), fontWeight = FontWeight.Bold)
                                    Text(
                                        assetDisplayName(position.pair),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (perCoinPnl != null) {
                                        Text(
                                            "Unrealized P&L",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    position.quantity,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(position.value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                if (perCoinPnl != null) {
                                    Text(
                                        perCoinPnl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = pnlColor(perCoinPnl),
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.positions.size > 2) {
                    Text(
                        "Scroll untuk lihat aset lain",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveActivityCard(
    modifier: Modifier = Modifier,
    state: KiBotUiState,
) {
    SurfaceCard(modifier = modifier) {
        Text("Timeline Hari Ini", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val entries = dashboardTimelineEntries(state)
        if (entries.isEmpty()) {
            Surface(
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        state.statusMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.lastUpdatedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEach { entry ->
                    val displayCategory = displayLiveLogCategory(entry.category, entry.message)
                    Surface(
                        color = Color.White.copy(alpha = 0.04f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = liveLogTint(displayCategory).copy(alpha = 0.14f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    displayCategory,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = liveLogTint(displayCategory),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    entry.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatLogTime(entry.timestampEpochMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (entries.size > 3) {
                    Text(
                        "Scroll untuk lihat timeline lain",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineControlScreen(
    state: KiBotUiState,
    onCommand: (EngineAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Engine Control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("Active ${state.activeEngine}", Color(0xFF1D4ED8))
                    StatusChip("Standby ${state.standbyEngine}", Color(0xFF64748B))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(visiblePairLabel(state), Color(0xFF8B5CF6))
                    StatusChip(state.syncPathLabel, Color(0xFF0EA5E9))
                }
                Text(
                    engineSummaryLine(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    engineSummarySubline(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Aksi Aman",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { onCommand(EngineAction.RequestTakeover) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Takeover")
                    }
                    FilledTonalButton(
                        onClick = { onCommand(EngineAction.SyncNow) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Sync Now")
                    }
                }
                Text(
                    "Aksi Berisiko",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { onCommand(EngineAction.ForceSafeTakeover) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFFFF7ED),
                            contentColor = Color(0xFFEA580C),
                        ),
                    ) {
                        Text("Force Safe")
                    }
                    FilledTonalButton(
                        onClick = { onCommand(EngineAction.ReleaseControl) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFFBEAEC),
                            contentColor = Color(0xFFBE123C),
                        ),
                    ) {
                        Text("Release")
                    }
                }
                Text(
                    "Takeover tetap aman. Kalau state ambigu, bot tetap blok entry baru.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LogsScreen(state: KiBotUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Logs & Errors", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        SurfaceCard {
            Text("Trade History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.trades.isEmpty()) {
                Text("Belum ada trade history live.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.trades.take(16).forEach { trade ->
                        Surface(
                            color = Color.White.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = tradeSideTint(trade.side).copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(
                                        trade.side.substringBefore(" • "),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = tradeSideTint(trade.side),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(trade.pair, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(
                                        trade.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        trade.timeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                StatusChip(trade.status, tradeStatusTint(trade.status))
                            }
                        }
                    }
                }
            }
        }
        SurfaceCard {
            Text("Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.logs.isEmpty()) {
                Text("Belum ada log terbaru.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.logs.take(20).forEach { log ->
                        val displayCategory = displayLiveLogCategory(log.category, log.message)
                        Surface(
                            color = Color.White.copy(alpha = 0.04f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = liveLogTint(displayCategory).copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(
                                        displayCategory,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = liveLogTint(displayCategory),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        log.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${log.level} • ${log.timeLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusChip(
    label: String,
    tint: Color,
) {
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = tint,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RadarChip(label: String) {
    Surface(
        color = Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun pnlColor(label: String): Color {
    return when {
        label.trim().startsWith("-") -> Color(0xFFB43F3F)
        label.trim() == "+Rp0" || label.trim() == "Rp0" -> Color(0xFFE5E7EB)
        else -> Color(0xFF2DD881)
    }
}

private fun liveLogTint(category: String): Color = when (category.uppercase()) {
    "BUY" -> Color(0xFF2DD881)
    "SELL" -> Color(0xFF60A5FA)
    "RISK" -> Color(0xFFF97316)
    "SCAN" -> Color(0xFF9EC5FF)
    "TARGET" -> Color(0xFFA78BFA)
    "SETUP" -> Color(0xFF22C55E)
    "ROTASI" -> Color(0xFFFACC15)
    "PROFIT" -> Color(0xFF34D399)
    "LOSS" -> Color(0xFFEF4444)
    else -> Color(0xFF9EC5FF)
}

private fun displayLiveLogCategory(category: String, message: String): String {
    val normalizedCategory = category.uppercase()
    val normalizedMessage = message.lowercase()
    return when {
        normalizedCategory == "PROFIT" && (
            normalizedMessage.contains("loss") ||
                normalizedMessage.contains("-rp") ||
                normalizedMessage.contains("minus")
            ) -> "LOSS"
        else -> normalizedCategory
    }
}

private fun tradeSideTint(label: String): Color = when {
    label.startsWith("SELL") -> Color(0xFF60A5FA)
    label.startsWith("BUY") -> Color(0xFF22C55E)
    else -> Color(0xFF9EC5FF)
}

private fun tradeStatusTint(label: String): Color = when (label.uppercase()) {
    "FILLED" -> Color(0xFF34D399)
    "PARTIALLY_FILLED" -> Color(0xFFF59E0B)
    "CANCELED" -> Color(0xFF94A3B8)
    else -> Color(0xFF60A5FA)
}

private fun formatLogTime(epochMs: Long): String {
    if (epochMs <= 0L) return "--:--"
    val local = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(kotlinx.datetime.TimeZone.of("Asia/Jakarta"))
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

private fun scanCountLabel(state: KiBotUiState): String {
    return state.scanUniverseCount.takeIf { it > 0 }?.let { "Scan $it pair" } ?: "Scan berjalan"
}

private fun dashboardTimelineEntries(state: KiBotUiState): List<com.kibot.android.runtime.LiveLogEntry> {
    val priorityCategories = setOf("BUY", "SELL", "LOSS", "PROFIT", "RISK", "ROTASI")
    val displayEntries = state.liveLogEntries.map { entry ->
        displayLiveLogCategory(entry.category, entry.message) to entry
    }
    val priority = displayEntries
        .filter { (category, _) -> category in priorityCategories }
        .map { it.second }
    val chatter = displayEntries
        .filterNot { (category, _) -> category in priorityCategories }
        .map { it.second }
    return (priority + chatter).take(8)
}

private fun radarPairs(state: KiBotUiState): List<String> {
    val active = state.pairAktif.takeUnless { it.isBlank() || it == "-" }?.lowercase()
    return state.radarPairs
        .filter { it.isNotBlank() && it != "-" }
        .map { it.lowercase() }
        .filterNot { it == active }
        .take(2)
}

private fun visiblePairLabel(state: KiBotUiState): String {
    val active = state.pairAktif.takeUnless { it.isBlank() || it == "-" }?.lowercase()
    return active ?: state.radarPairs.firstOrNull()?.lowercase() ?: "scan"
}

private fun assetDisplayName(symbol: String): String {
    val normalized = symbol.substringBefore('_').uppercase()
    return when (normalized) {
        "TRX" -> "Tron"
        "XRP" -> "XRP"
        "BTC" -> "Bitcoin"
        "ETH" -> "Ethereum"
        "USDT" -> "Tether"
        "PEPE" -> "Pepe"
        else -> normalized.lowercase().replaceFirstChar { it.titlecase() }
    }
}

private fun engineSummaryLine(state: KiBotUiState): String {
    return listOf(
        "Regime ${state.marketRegime.removePrefix("HEALTHY_").replace('_', ' ')}",
        "Mode ${state.operatingMode}",
        "Edge ${state.edgeConfidence}",
    ).joinToString(" · ")
}

private fun engineSummarySubline(state: KiBotUiState): String {
    val guard = if (state.profitProtectionStatus.equals("INACTIVE", ignoreCase = true)) "Siaga" else state.profitProtectionStatus
    return listOf(
        "Risk ${state.riskLadderLevel}",
        "Guard $guard",
        compactStatusMessage(state.statusMessage),
    ).joinToString(" · ")
}

private fun compactStatusMessage(message: String): String {
    val sentences = message
        .split(".")
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (sentences.isEmpty()) return "Monitor live aktif."
    return sentences.last()
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class PingVisualState(
    val label: String,
    val tint: Color,
    val online: Boolean,
)

private fun pingVisual(
    isBotRunning: Boolean,
    label: String,
): PingVisualState {
    if (!isBotRunning) {
        return PingVisualState("OFF", Color(0xFF94A3B8), online = false)
    }
    val ms = label.substringBefore(" ").toLongOrNull()
    if (ms == null) {
        return PingVisualState("--", Color(0xFFF59E0B), online = false)
    }
    val tint = when {
        ms <= 220L -> Color(0xFF2DD881)
        ms <= 520L -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    return PingVisualState("${ms} ms", tint, online = true)
}

private fun assetAccent(symbol: String): Color {
    return when (symbol.lowercase()) {
        "btc", "bitcoin" -> Color(0xFFF7931A)
        "eth", "ethereum" -> Color(0xFF8B93A7)
        "sol", "solana" -> Color(0xFF8B5CF6)
        "xrp" -> Color(0xFF60A5FA)
        "usdt" -> Color(0xFF22C55E)
        else -> Color(0xFFA78BFA)
    }
}
