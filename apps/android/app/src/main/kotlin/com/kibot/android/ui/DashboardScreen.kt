package com.kibot.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kibot.android.data.*
import com.kibot.android.ui.components.*
import com.kibot.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    botState: BotState,
    isConnected: Boolean,
    onToggleBot: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pullRefreshState = remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Connection status banner
        AnimatedVisibility(
            visible = !isConnected,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            ConnectionBanner(
                isConnected = isConnected,
                label = botState.connectedBotId.ifBlank { "server" }
            )
        }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Balance Overview Card
            item {
                BalanceCard(
                    totalBalance = botState.balance,
                    totalReturn = botState.totalReturn,
                    pnlToday = botState.pnlToday
                )
            }
            
            // KiBot Manager Status Card
            item {
                BotStatusCard(
                    name = "KiBot Manager",
                    subtitle = "The Brain & Veto Manager",
                    status = botState.heartbeat.kibot.status,
                    pingMs = null,
                    aiStatus = botState.heartbeat.kibot.aiStatus,
                    isEnabled = botState.heartbeat.kibot.enabled,
                    onToggle = { enabled -> onToggleBot("kibot", enabled) }
                )
            }
            
            // Kinance Status Card
            item {
                BotStatusCard(
                    name = "Kinance",
                    subtitle = "Binance Predictive Radar",
                    status = botState.heartbeat.kinance.status,
                    pingMs = null,
                    aiStatus = botState.heartbeat.kinance.aiStatus,
                    isEnabled = botState.heartbeat.kinance.enabled,
                    onToggle = { enabled -> onToggleBot("kinance", enabled) }
                ) {
                    // Holdings section for Kinance
                    if (botState.heartbeat.kinance.holdings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = DarkSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Current Holdings",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        
                        botState.heartbeat.kinance.holdings.take(5).forEach { holding ->
                            HoldingItem(
                                coin = holding.coin,
                                amount = holding.amount,
                                price = holding.price,
                                pnl = holding.pnl
                            )
                        }
                    }
                }
            }
            
            // KiDax Status Card
            item {
                BotStatusCard(
                    name = "KiDax",
                    subtitle = "Indodax Executioner",
                    status = botState.heartbeat.kidax.status,
                    pingMs = botState.heartbeat.kidax.ping,
                    aiStatus = botState.heartbeat.kidax.aiStatus,
                    isEnabled = botState.heartbeat.kidax.enabled,
                    onToggle = { enabled -> onToggleBot("kidax", enabled) }
                ) {
                    // Active positions for KiDax
                    if (botState.positions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = DarkSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Active Positions",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        
                        botState.positions.take(5).forEach { position ->
                            HoldingItem(
                                coin = position.pair.replace("/IDR", ""),
                                amount = position.amount,
                                price = position.currentPrice,
                                pnl = position.pnlPercent
                            )
                        }
                    }
                }
            }
            
            // Recent Activity Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent Trades",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            TextButton(onClick = { /* Navigate to ledger */ }) {
                                Text(
                                    text = "See All",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = KiBotBlue
                                )
                            }
                        }
                        
                        if (botState.trades.isEmpty()) {
                            EmptyState(
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.ShowChart,
                                        contentDescription = null,
                                        tint = TextDisabled,
                                        modifier = Modifier.size(48.dp)
                                    )
                                },
                                title = "No Recent Trades",
                                subtitle = "Trades will appear here when executed"
                            )
                        } else {
                            botState.trades.take(3).forEach { trade ->
                                TradeListItem(trade = trade)
                            }
                        }
                    }
                }
            }
            
            // Spacer for bottom nav
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun TradeListItem(
    trade: TradeData,
    modifier: Modifier = Modifier
) {
    val isBuy = trade.side.lowercase() == "buy"
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isBuy) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = trade.side,
                tint = if (isBuy) ProfitGreen else LossRed,
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = if (isBuy) ProfitGreen.copy(alpha = 0.15f) else LossRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(6.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column {
                Text(
                    text = "${trade.side.uppercase()} ${trade.pair}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatTimestamp(trade.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }
        }
        
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatRupiah(trade.total),
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            trade.profitLoss?.let { pnl ->
                Text(
                    text = formatRupiah(pnl),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pnl >= 0) ProfitGreen else LossRed
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}
