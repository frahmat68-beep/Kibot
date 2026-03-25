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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kibot.shared.models.BotEffectiveState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue

private enum class RootTab(val title: String) {
    Dashboard("Dashboard"),
    Portfolio("Portfolio"),
    Logs("Logs"),
}

@Composable
fun KiBotRoot(
    state: KiBotUiState,
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
                RootTab.Dashboard -> DashboardScreen(state = state)
                RootTab.Portfolio -> PortfolioScreen(state = state)
                RootTab.Logs -> LogsScreen(state = state)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    state: KiBotUiState,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { HeroCard(state = state) }
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
                label = "Return 30D",
                value = portfolio.thirtyDayReturnLabel,
                caption = portfolio.thirtyDayReturnPctLabel,
                tint = pnlColor(portfolio.thirtyDayReturnLabel),
            )
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
            AllocationDonutChart(items = portfolio.allocations)
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
private fun AllocationDonutChart(items: List<PortfolioAllocationUi>) {
    val visibleItems = items
        .filter { it.pct > 0.0 }
        .sortedByDescending { it.pct }
    if (visibleItems.isEmpty()) return
    val topItem = visibleItems.first()
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .size(168.dp)
                        .padding(12.dp),
                ) {
                    val strokeWidth = size.minDimension * 0.15f
                    val gapAngle = 1.6f
                    drawArc(
                        color = Color.White.copy(alpha = 0.08f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth),
                        size = Size(size.width, size.height),
                    )
                    var startAngle = -90f
                    visibleItems.forEach { item ->
                        val baseSweep = (item.pct.coerceAtLeast(0.0) * 360.0).toFloat()
                        val sweep = (baseSweep - gapAngle).coerceAtLeast(5f)
                        drawArc(
                            color = assetAccent(item.label),
                            startAngle = startAngle + (gapAngle / 2f),
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            size = Size(size.width, size.height),
                        )
                        startAngle += baseSweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        topItem.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        topItem.pctLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = assetAccent(topItem.label),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .heightIn(max = 170.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleItems.forEach { item ->
                    Surface(
                        color = Color.White.copy(alpha = 0.03f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(assetAccent(item.label), CircleShape),
                            )
                            Text(
                                item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                item.pctLabel,
                                color = assetAccent(item.label),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}
@Composable
private fun HeroCard(
    state: KiBotUiState,
) {
    val pnlColor = pnlColor(state.pnlTodayIdr)
    val serverState = serverStatusVisual(state)
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
                    color = pingTint(state.internetPingLabel).copy(alpha = 0.18f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = "Ping server",
                            tint = pingTint(state.internetPingLabel),
                        )
                        Text(
                            text = state.internetPingLabel,
                            color = pingTint(state.internetPingLabel),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    label = "Oracle Active ${state.releaseLabel}",
                    tint = serverState.tint,
                )
                StatusChip(
                    label = state.targetPursuitLabel,
                    tint = when (state.targetPursuitLabel.uppercase()) {
                        "OVERDRIVE" -> Color(0xFFF97316)
                        "FULL_CHASE" -> Color(0xFFEF4444)
                        "CHASE" -> Color(0xFF22C55E)
                        "LOCK_PROFIT" -> Color(0xFF38BDF8)
                        else -> Color(0xFF60A5FA)
                    },
                )
                StatusChip(
                    label = state.lastUpdatedLabel,
                    tint = Color(0xFF60A5FA),
                )
            }
            Text(
                text = state.aiProviderSummary,
                color = Color(0xFFA8B7D7),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
    val livePair = visiblePairLabel(state)
    val radarPairs = radarPairs(state)
    val serverState = serverStatusVisual(state)
    SurfaceCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live Pair", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusChip(
                label = serverState.label,
                tint = serverState.tint,
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssetBadge(symbol = livePair.substringBefore('_').uppercase())
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            livePair,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (radarPairs.isNotEmpty()) {
                    val visible = radarPairs.take(9)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        visible.chunked(3).take(3).forEach { rowPairs ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowPairs.forEach { pair ->
                                    RadarPairPill(
                                        modifier = Modifier.weight(1f),
                                        label = pair,
                                        highlighted = pair == visible.firstOrNull(),
                                    )
                                }
                                repeat((3 - rowPairs.size).coerceAtLeast(0)) {
                                    Spacer(modifier = Modifier.weight(1f))
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
private fun RadarPairPill(
    modifier: Modifier = Modifier,
    label: String,
    highlighted: Boolean = false,
) {
    val accent = assetAccent(label.substringBefore('_').uppercase())
    Surface(
        modifier = modifier,
        color = if (highlighted) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.14f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            color = if (highlighted) accent else accent.copy(alpha = 0.96f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
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
                    val accent = assetAccent(position.pair)
                    Surface(
                        color = accent.copy(alpha = 0.09f),
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
                                Text(position.value, color = accent, fontWeight = FontWeight.SemiBold)
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
        Text("Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Server Oracle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("View Only", Color(0xFF1D4ED8))
                    StatusChip(state.syncPathLabel, Color(0xFF0EA5E9))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(visiblePairLabel(state), Color(0xFF8B5CF6))
                    StatusChip(state.syncHealth, Color(0xFF22C55E))
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
                Surface(
                    color = Color.White.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusRow("Portfolio", state.modalSaatIniIdr)
                        StatusRow("PnL Hari Ini", state.pnlTodayIdr)
                        StatusRow("Feed", state.syncPathLabel)
                        StatusRow("Lease Term", "#${state.leaseTerm}")
                        StatusRow("Update", state.lastUpdatedLabel)
                    }
                }
                Text(
                    "App ini hanya untuk pantau hasil trade server. Semua keputusan trading berjalan otomatis di Oracle 24/7.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
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
                                    if (trade.entryPriceLabel.isNotBlank() || trade.exitPriceLabel.isNotBlank()) {
                                        Text(
                                            "Buy ${trade.entryPriceLabel.ifBlank { "-"}} • Sell ${trade.exitPriceLabel.ifBlank { "-"}}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (trade.outcomeLabel.isNotBlank()) {
                                        Text(
                                            trade.outcomeLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = pnlColor(trade.outcomeLabel),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
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
    val colors = listOf(
        Color(0x1C38BDF8),
        Color(0x168B5CF6),
        Color(0x1222C55E),
    )
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(colors),
                    shape = RoundedCornerShape(22.dp),
                )
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

private fun dashboardTimelineEntries(state: KiBotUiState): List<com.kibot.android.runtime.LiveLogEntry> {
    val nowEpoch = System.currentTimeMillis()
    val activePair = visiblePairLabel(state)
    val topCandidate = state.radarPairs
        .map { it.lowercase() }
        .firstOrNull { it != activePair }
        ?: activePair
    val lastSell = state.liveLogEntries.firstOrNull { it.category.equals("SELL", ignoreCase = true) }
    val syntheticStatus = listOf(
        com.kibot.android.runtime.LiveLogEntry(
            timestampEpochMs = nowEpoch,
            category = "STATUS",
            message = "Fokus sekarang $activePair. Ping ${state.internetPingLabel}, scan ${state.scanUniverseCount} pair, mode ${state.operatingMode}.",
        ),
        com.kibot.android.runtime.LiveLogEntry(
            timestampEpochMs = nowEpoch - 1_000L,
            category = "ROTASI",
            message = "Jika $activePair stagnan atau edge turun, bot siap rotasi cepat ke $topCandidate untuk jaga velocity modal.",
        ),
        com.kibot.android.runtime.LiveLogEntry(
            timestampEpochMs = nowEpoch - 2_000L,
            category = "TARGET",
            message = "Target berikutnya $topCandidate. Profit 1D ${state.pnlTodayIdr}, return 7D ${state.portfolio.sevenDayReturnLabel}, return 30D ${state.portfolio.thirtyDayReturnLabel}.",
        ),
    ) + listOfNotNull(
        lastSell?.let {
            com.kibot.android.runtime.LiveLogEntry(
                timestampEpochMs = nowEpoch - 3_000L,
                category = "SELL",
                message = "Terakhir jual: ${it.message}. Lanjut incar $topCandidate.",
            )
        },
    )
    val tradeTimeline = state.trades
        .take(4)
        .mapIndexed { index, trade ->
            val isSell = trade.side.uppercase().startsWith("SELL")
            val detail = if (isSell) {
                "Jual ${trade.pair} | Buy ${trade.entryPriceLabel.ifBlank { "-" }} -> Sell ${trade.exitPriceLabel.ifBlank { "-" }} | ${trade.outcomeLabel.ifBlank { "hasil belum tersedia" }}."
            } else {
                "Beli ${trade.pair} di ${trade.entryPriceLabel.ifBlank { "-" }} | ${trade.status}."
            }
            com.kibot.android.runtime.LiveLogEntry(
                timestampEpochMs = nowEpoch - ((index + 4) * 1_000L),
                category = if (isSell) "SELL" else "BUY",
                message = detail,
            )
        }
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
    return (syntheticStatus + tradeTimeline + priority + chatter)
        .distinctBy { "${it.category}|${it.message}" }
        .take(10)
}

private fun radarPairs(state: KiBotUiState): List<String> {
    val active = state.pairAktif.takeUnless { it.isBlank() || it == "-" }?.lowercase()
    val scanned = state.radarPairs
        .filter { it.isNotBlank() && it.trim('-').isNotBlank() }
        .map { it.lowercase() }
        .filterNot { it == active }
    val fallback = listOf(
        "pepe_idr",
        "shib_idr",
        "doge_idr",
        "xrp_idr",
        "trx_idr",
        "fartcoin_idr",
        "jellyjelly_idr",
        "sol_idr",
        "arb_idr",
        "ada_idr",
        "link_idr",
        "matic_idr",
        "btc_idr",
        "eth_idr",
    )
    val filled = (scanned + fallback)
        .distinct()
        .filterNot { it == active }
        .take(9)
    return if (filled.size >= 9) filled else {
        (filled + fallback.filterNot { it in filled }.take(9 - filled.size)).take(9)
    }
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

private data class ServerVisualState(
    val label: String,
    val tint: Color,
    val summary: String,
)

private fun serverStatusVisual(state: KiBotUiState): ServerVisualState {
    return when {
        state.effectiveState == BotEffectiveState.SAFE_MODE -> ServerVisualState(
            label = "SAFE",
            tint = Color(0xFFF97316),
            summary = "Server aman-terbatas",
        )
        state.syncHealth.equals("BROKEN", ignoreCase = true) -> ServerVisualState(
            label = "LAG",
            tint = Color(0xFFEF4444),
            summary = "Feed server terlambat",
        )
        state.effectiveState == BotEffectiveState.DEGRADED ||
            state.syncHealth.equals("DEGRADED", ignoreCase = true) -> ServerVisualState(
            label = "WARM",
            tint = Color(0xFFF59E0B),
            summary = "Server tetap jalan",
        )
        state.effectiveState == BotEffectiveState.RUNNING ||
            state.effectiveState == BotEffectiveState.STARTING -> ServerVisualState(
            label = "LIVE",
            tint = Color(0xFF2DD881),
            summary = "Server Oracle aktif",
        )
        else -> ServerVisualState(
            label = "OFF",
            tint = Color(0xFF94A3B8),
            summary = "Server belum aktif",
        )
    }
}

private fun assetAccent(symbol: String): Color {
    return when (symbol.lowercase()) {
        "btc", "bitcoin" -> Color(0xFFF7931A)
        "eth", "ethereum" -> Color(0xFF8B93A7)
        "sol", "solana" -> Color(0xFF8B5CF6)
        "xrp" -> Color(0xFF60A5FA)
        "usdt" -> Color(0xFF22C55E)
        "doge", "dogecoin" -> Color(0xFFFBBF24)
        "trx", "tron" -> Color(0xFFEF4444)
        "pepe" -> Color(0xFF34D399)
        "ont", "ontology" -> Color(0xFF38BDF8)
        "fartcoin" -> Color(0xFF14B8A6)
        "cash" -> Color(0xFFC084FC)
        "others" -> Color(0xFF94A3B8)
        else -> Color(0xFFA78BFA)
    }
}

private fun pingTint(pingLabel: String): Color {
    val ping = pingLabel.filter { it.isDigit() }.toIntOrNull()
    return when {
        ping == null -> Color(0xFF9EC5FF)
        ping <= 90 -> Color(0xFF22C55E)
        ping <= 220 -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
}
