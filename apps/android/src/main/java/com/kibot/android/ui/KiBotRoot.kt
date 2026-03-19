package com.kibot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

private enum class RootTab(val title: String) {
    Dashboard("Dashboard"),
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HeroCard(state = state, onToggleBot = onToggleBot)
        HoldingsPreviewCard(
            modifier = Modifier.fillMaxWidth(),
            state = state,
        )
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(22.dp),
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
                        AssetBadge(symbol = state.pairAktif.substringBefore('_').uppercase())
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                state.pairAktif.lowercase(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                regimeLabel(state.marketRegime),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    FilledTonalButton(onClick = onToggleBot) {
                        Text(if (state.isBotRunning) "Turn OFF" else "Turn ON")
                    }
                }
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
            state.positions.take(3).forEach { position ->
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
                            AssetBadge(symbol = position.pair.uppercase())
                            Column {
                                Text(position.pair.uppercase(), fontWeight = FontWeight.Bold)
                                Text(
                                    position.quantity,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(position.value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (state.positions.size > 3) {
                Text(
                    "+${state.positions.size - 3} aset lainnya",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EngineControlScreen(
    state: KiBotUiState,
    onCommand: (EngineAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Engine Control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(state.statusMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(state.activeEngine, Color(0xFF1D4ED8))
                        StatusChip("Backup ${state.standbyEngine}", Color(0xFF4B6385))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    FilledTonalButton(onClick = { onCommand(EngineAction.RequestTakeover) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Request Takeover")
                    }
                    FilledTonalButton(onClick = { onCommand(EngineAction.ForceSafeTakeover) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Force Safe Takeover")
                    }
                    FilledTonalButton(onClick = { onCommand(EngineAction.ReleaseControl) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Release Control")
                    }
                    FilledTonalButton(onClick = { onCommand(EngineAction.SyncNow) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sync Status Now")
                    }
                    Text(
                        "Takeover tetap lewat control-plane. Jika state ambigu, bot akan tetap blok entry baru.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsScreen(state: KiBotUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("Logs & Errors") }
        item { SectionTitle("Trade History") }
        if (state.trades.isEmpty()) {
            item {
                SurfaceCard {
                    Text("Belum ada trade history live.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.trades) { trade ->
                SurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(trade.pair, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(trade.side, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(trade.pnl, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { SectionTitle("Logs") }
        if (state.logs.isEmpty()) {
            item {
                SurfaceCard {
                    Text("Belum ada log terbaru.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.logs) { log ->
                SurfaceCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${log.level} • ${log.category}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(log.message)
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun pnlColor(label: String): Color {
    return when {
        label.trim().startsWith("-") -> Color(0xFFB43F3F)
        label.trim() == "+Rp0" || label.trim() == "Rp0" -> Color(0xFFE5E7EB)
        else -> Color(0xFF2DD881)
    }
}

private fun regimeLabel(value: String): String = when (value.uppercase()) {
    "HEALTHY_UPTREND" -> "Uptrend sehat"
    "HEALTHY_SIDEWAYS" -> "Sideways sehat"
    "HIGH_VOLATILITY_UNCLEAR" -> "Volatil tinggi"
    "BREAKDOWN_PANIC" -> "Panic"
    else -> value.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
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
