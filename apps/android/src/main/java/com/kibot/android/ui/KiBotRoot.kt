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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
                                    RootTab.Dashboard -> Icons.Outlined.PowerSettingsNew
                                    RootTab.Control -> Icons.Outlined.Sync
                                    RootTab.Logs -> Icons.Outlined.Sync
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HeroCard(state = state, onToggleBot = onToggleBot) }
        item {
            SurfaceCard {
                StatusLine("Mode", state.operatingMode)
                StatusLine("Edge", state.edgeConfidence)
                StatusLine("Sync", "${state.syncPathLabel} • ${state.syncHealth}")
                StatusLine("Lag", state.syncLagLabel)
            }
        }
        item {
            SurfaceCard {
                StatusLine("Risk", state.riskLadderLevel)
                StatusLine("Profit guard", state.profitProtectionStatus)
                StatusLine("Update", state.lastUpdatedLabel)
                StatusLine("Backup", state.standbyEngine)
            }
        }
        item { SectionTitle("Holdings") }
        if (state.positions.isEmpty()) {
            item {
                SurfaceCard {
                    Text("Belum ada aset aktif di akun Indodax.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.positions.take(4)) { position ->
                SurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(position.pair.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(position.quantity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(position.value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MetricCard(
                        label = "Drawdown",
                        value = "${(state.drawdownPct * 100).toInt()}%",
                        supporting = "Limit ${(state.dailyLossLimitPct * 100).toInt()}%",
                    )
                    MetricCard(
                        label = "Lease",
                        value = "#${state.leaseTerm}",
                        supporting = state.profitProtectionStatus,
                    )
                    StatusLine("Engine aktif", state.activeEngine)
                    StatusLine("Backup", state.standbyEngine)
                    StatusLine("Pair aktif", state.pairAktif.lowercase())
                    StatusLine("Regime", state.marketRegime)
                    StatusLine("Sync", state.syncPathLabel)
                    StatusLine("Status", state.statusMessage)
                }
            }
        }
        if (state.devices.isNotEmpty()) {
            item { SectionTitle("Devices") }
            items(state.devices) { device ->
                SurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(device.heartbeat, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusChip(
                            if (device.active) "ACTIVE" else device.health.uppercase(),
                            if (device.active) Color(0xFF147D57) else Color(0xFF4B6385),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: KiBotUiState,
    onToggleBot: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("KiBot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.isBotRunning) "Balance live akun bot" else "Siap dipakai lagi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    if (state.pairAktif == "-") "scan" else state.pairAktif.lowercase(),
                    Color(0xFF1D4ED8),
                )
            }
            Text(state.modalSaatIniIdr, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                state.pnlTodayIdr,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                heroSubtitle(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (state.isBotRunning) "RUNNING" else "STOPPED", if (state.isBotRunning) Color(0xFF0E8A4C) else Color(0xFFB43F3F))
                StatusChip(state.operatingMode, Color(0xFF6959CD))
                StatusChip(state.syncPathLabel, Color(0xFF8A6A12))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onToggleBot) {
                    Text(if (state.isBotRunning) "Turn OFF" else "Turn ON")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        state.activeEngine,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Update ${state.lastUpdatedLabel}",
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
private fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    supporting: String,
) {
    SurfaceCard(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun heroSubtitle(state: KiBotUiState): String {
    return when {
        !state.isBotRunning -> "Bot sedang berhenti."
        state.syncHealth == "BROKEN" -> "Sync bermasalah. Cek tab Logs."
        else -> "${state.activeEngine} aktif • ${state.marketRegime} • lag ${state.syncLagLabel}."
    }
}
