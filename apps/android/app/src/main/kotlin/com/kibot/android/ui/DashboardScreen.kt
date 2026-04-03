package com.kibot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kibot.android.data.BotStatus
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val ProfitGreen = Color(0xFF00C851)
val LossRed = Color(0xFFff4444)
val PrimaryBlue = Color(0xFF0066CC)
val AccentOrange = Color(0xFFFF9900)
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkSurfaceVariant = Color(0xFF2C2C2C)
val LightText = Color(0xFFFFFFFF)
val SecondaryText = Color(0xFFB0B0B0)

val currencyFormat = NumberFormat.getInstance().apply {
    maximumFractionDigits = 0
    minimumFractionDigits = 0
}

val decimalFormat = NumberFormat.getInstance().apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}

@Composable
fun DashboardScreen(
    status: BotStatus?,
    isConnected: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onStartTrading: () -> Unit,
    onStopTrading: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        if (status != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                HeaderSection(isConnected, isLoading, status.status, onSettingsClick)
                BalanceSection(status.balance)
                PnLSection(status.pnl)
                CapitalAllocationSection(status.capitalSplit)
                ActiveTradesSection(status.activeTrades)
                ActionButtonsSection(
                    onStartTrading = onStartTrading,
                    onStopTrading = onStopTrading,
                    onRefresh = onRefresh
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        } else {
            LoadingScreen(isConnected)
        }
    }
}

@Composable
private fun HeaderSection(
    isConnected: Boolean,
    isLoading: Boolean,
    status: String,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "KiBot",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = LightText
            )
            Spacer(modifier = Modifier.width(12.dp))
            StatusIndicator(isConnected, isLoading, status)
        }
        IconButton(onClick = onSettingsClick) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = PrimaryBlue,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun StatusIndicator(
    isConnected: Boolean,
    isLoading: Boolean,
    status: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                if (isConnected) Color(0xFF2D5016) else Color(0xFF5D2C2C),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(8.dp, 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    if (isConnected) ProfitGreen else LossRed,
                    shape = RoundedCornerShape(50.dp)
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            if (isConnected) "Connected" else "Offline",
            fontSize = 12.sp,
            color = if (isConnected) ProfitGreen else LossRed
        )
        if (isLoading) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.dp,
                color = PrimaryBlue
            )
        }
    }
}

@Composable
private fun BalanceSection(balance: com.kibot.android.data.Balance) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Current Balance", fontSize = 14.sp, color = SecondaryText)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Rp ${currencyFormat.format(balance.idr)}",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = LightText,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "\$${decimalFormat.format(balance.usdt)}",
                fontSize = 16.sp,
                color = SecondaryText,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Total: Rp ${currencyFormat.format(balance.total)}",
                fontSize = 12.sp,
                color = SecondaryText
            )
        }
    }
}

@Composable
private fun PnLSection(pnl: com.kibot.android.data.PnL) {
    val isProfit = pnl.daily >= 0
    val profitColor = if (isProfit) ProfitGreen else LossRed
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Daily Profit / Loss", fontSize = 14.sp, color = SecondaryText)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Rp ${currencyFormat.format(pnl.daily)}",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = profitColor,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${if (isProfit) "+" else ""}${decimalFormat.format(pnl.percentage)}%",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = profitColor
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (pnl.trend.isNotEmpty()) {
                MiniSparkline(pnl.trend, profitColor)
            }
        }
    }
}

@Composable
private fun MiniSparkline(
    trend: List<Double>,
    color: Color
) {
    if (trend.isEmpty()) return
    
    val max = trend.maxOrNull() ?: 0.0
    val min = trend.minOrNull() ?: 0.0
    val range = if (max > min) max - min else 1.0
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        trend.forEach { value ->
            val normalized = if (range > 0) (value - min) / range else 0.5
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(normalized.toFloat().coerceIn(0.1f, 1f))
                    .background(color, RoundedCornerShape(4.dp))
                    .padding(horizontal = 2.dp)
            )
        }
    }
}

@Composable
private fun CapitalAllocationSection(split: com.kibot.android.data.CapitalSplit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text("Capital Allocation", fontSize = 14.sp, color = SecondaryText)
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(0.7f)
                        .height(20.dp)
                        .background(PrimaryBlue, RoundedCornerShape(10.dp))
                )
                Text(
                    "70%",
                    fontSize = 12.sp,
                    color = LightText,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                "High Conviction: Rp ${currencyFormat.format(split.highConviction)}",
                fontSize = 11.sp,
                color = SecondaryText,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(0.3f)
                        .height(20.dp)
                        .background(AccentOrange, RoundedCornerShape(10.dp))
                )
                Text(
                    "30%",
                    fontSize = 12.sp,
                    color = LightText,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                "Aggressive: Rp ${currencyFormat.format(split.aggressive)}",
                fontSize = 11.sp,
                color = SecondaryText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ActiveTradesSection(trades: List<com.kibot.android.data.Trade>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text("Active Trades (Top 5)", fontSize = 14.sp, color = SecondaryText)
            Spacer(modifier = Modifier.height(12.dp))
            
            if (trades.isEmpty()) {
                Text(
                    "No active trades",
                    fontSize = 14.sp,
                    color = SecondaryText,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                trades.take(5).forEach { trade ->
                    TradeCard(trade)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TradeCard(trade: com.kibot.android.data.Trade) {
    val isProfit = trade.profitPct >= 0
    val profitColor = if (isProfit) ProfitGreen else LossRed
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant, RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    trade.pair,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightText
                )
                Text(
                    "${if (isProfit) "+" else ""}${decimalFormat.format(trade.profitPct)}%",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = profitColor
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Entry", fontSize = 11.sp, color = SecondaryText)
                    Text(
                        decimalFormat.format(trade.entry),
                        fontSize = 13.sp,
                        color = LightText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column {
                    Text("Current", fontSize = 11.sp, color = SecondaryText)
                    Text(
                        decimalFormat.format(trade.current),
                        fontSize = 13.sp,
                        color = LightText,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column {
                    Text("Profit", fontSize = 11.sp, color = SecondaryText)
                    Text(
                        "Rp ${currencyFormat.format(trade.profit)}",
                        fontSize = 13.sp,
                        color = profitColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtonsSection(
    onStartTrading: () -> Unit,
    onStopTrading: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Button(
            onClick = onStartTrading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ProfitGreen),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = DarkBackground)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Trading", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DarkBackground)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = onStopTrading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LossRed),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("STOP Trading", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue).brush
            )
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = PrimaryBlue)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Manual Refresh", fontSize = 14.sp, color = PrimaryBlue)
        }
    }
}

@Composable
fun LoadingScreen(isConnected: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (isConnected) "Loading data..." else "Connecting to server...",
            fontSize = 16.sp,
            color = LightText
        )
    }
}
