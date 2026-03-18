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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
            MaterialTheme.colorScheme.surface,
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
                RootTab.Dashboard -> DashboardScreen(state, onToggleBot)
                RootTab.Control -> EngineControlScreen(state, onCommand)
                RootTab.Logs -> LogsScreen(state)
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
        item {
            HeroCard(state = state, onToggleBot = onToggleBot)
        }
        item {
            MetricRow(
                leftTitle = "Modal Saat Ini",
                leftValue = state.modalSaatIniIdr,
                rightTitle = "PnL Hari Ini",
                rightValue = state.pnlTodayIdr,
            )
        }
        item {
            MetricRow(
                leftTitle = "Drawdown",
                leftValue = "${(state.drawdownPct * 100).toInt()}%",
                rightTitle = "Lease Term",
                rightValue = "#${state.leaseTerm}",
            )
        }
        item {
            MetricRow(
                leftTitle = "Sync Lag",
                leftValue = state.syncLagLabel,
                rightTitle = "Risk Gate",
                rightValue = if (state.riskBlocked) "BLOCKED" else "ALLOWED",
            )
        }
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ringkasan Engine", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Active: ${state.activeEngine}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Standby: ${state.standbyEngine}")
                    Text("Market regime: ${state.marketRegime}")
                    Text("Profit protection: ${state.profitProtectionStatus}")
                    Text(state.weeklyLearningSummary)
                    Text(state.weeklyAdaptationSummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            SectionTitle("Open Positions")
        }
        items(state.positions) { position ->
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(position.pair, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(position.quantity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(position.pnl, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            SectionTitle("Devices")
        }
        items(state.devices) { device ->
            SurfaceCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Heartbeat ${device.heartbeat}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (device.active) "ACTIVE" else "STANDBY", color = MaterialTheme.colorScheme.primary)
                        Text(if (device.online) device.health else "Offline", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("KiBot Control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(state.statusMessage, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (state.isBotRunning) "RUNNING" else "STOPPED", if (state.isBotRunning) Color(0xFF0E8A4C) else Color(0xFFB43F3F))
                StatusChip("ACTIVE ${state.activeEngine.uppercase()}", Color(0xFF0A6FD6))
                StatusChip("SYNC ${state.syncHealth}", Color(0xFF6B5B00))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("MODE ${state.operatingMode}", Color(0xFF1D4ED8))
                StatusChip("EDGE ${state.edgeConfidence}", Color(0xFF0F766E))
                StatusChip("RISK ${state.riskLadderLevel}", Color(0xFFB45309))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Pair Aktif", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.pairAktif, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(onClick = onToggleBot) {
                    Text(if (state.isBotRunning) "Turn OFF" else "Turn ON")
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
            SectionTitle("Engine Control")
        }
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Active engine: ${state.activeEngine}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Standby engine: ${state.standbyEngine}", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        item { SectionTitle("Logs") }
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

@Composable
private fun MetricRow(
    leftTitle: String,
    leftValue: String,
    rightTitle: String,
    rightValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SurfaceCard(modifier = Modifier.weight(1f)) {
            Text(leftTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(leftValue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        SurfaceCard(modifier = Modifier.weight(1f)) {
            Text(rightTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(rightValue, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChip(
    label: String,
    color: Color,
) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.14f),
            labelColor = color,
        ),
        modifier = Modifier.height(32.dp),
    )
}
