package com.kibot.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kibot.android.data.TradeData
import com.kibot.android.ui.components.*
import com.kibot.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    trades: List<TradeData>,
    modifier: Modifier = Modifier
) {
    var selectedTrade by remember { mutableStateOf<TradeData?>(null) }
    var filterSide by remember { mutableStateOf<String?>(null) }
    
    val filteredTrades = remember(trades, filterSide) {
        if (filterSide == null) trades
        else trades.filter { it.side.lowercase() == filterSide?.lowercase() }
    }
    
    // Group trades by date
    val groupedTrades = remember(filteredTrades) {
        filteredTrades.groupBy { trade ->
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(trade.timestamp))
        }.toSortedMap(reverseOrder())
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterSide == null,
                onClick = { filterSide = null },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = KiBotBlue,
                    selectedLabelColor = TextPrimary,
                    containerColor = DarkSurface,
                    labelColor = TextSecondary
                )
            )
            
            FilterChip(
                selected = filterSide == "buy",
                onClick = { filterSide = if (filterSide == "buy") null else "buy" },
                label = { Text("Buy") },
                leadingIcon = if (filterSide == "buy") {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ProfitGreen.copy(alpha = 0.3f),
                    selectedLabelColor = ProfitGreen,
                    containerColor = DarkSurface,
                    labelColor = TextSecondary
                )
            )
            
            FilterChip(
                selected = filterSide == "sell",
                onClick = { filterSide = if (filterSide == "sell") null else "sell" },
                label = { Text("Sell") },
                leadingIcon = if (filterSide == "sell") {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = LossRed.copy(alpha = 0.3f),
                    selectedLabelColor = LossRed,
                    containerColor = DarkSurface,
                    labelColor = TextSecondary
                )
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Trade count
            Text(
                text = "${filteredTrades.size} trades",
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary
            )
        }
        
        if (filteredTrades.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Receipt,
                            contentDescription = null,
                            tint = TextDisabled,
                            modifier = Modifier.size(64.dp)
                        )
                    },
                    title = "No Trades Yet",
                    subtitle = "Your trade history will appear here"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                groupedTrades.forEach { (date, tradesForDate) ->
                    // Date header
                    item(key = "header_$date") {
                        DateHeader(date = date)
                    }
                    
                    // Trades for this date
                    items(
                        items = tradesForDate,
                        key = { it.id }
                    ) { trade ->
                        TradeItem(
                            trade = trade,
                            onClick = { selectedTrade = trade }
                        )
                    }
                    
                    item(key = "spacer_$date") {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                // Bottom padding for nav bar
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
    
    // Trade detail bottom sheet
    if (selectedTrade != null) {
        TradeDetailSheet(
            trade = selectedTrade!!,
            onDismiss = { selectedTrade = null }
        )
    }
}

@Composable
private fun DateHeader(
    date: String,
    modifier: Modifier = Modifier
) {
    val formattedDate = remember(date) {
        try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outputFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
            val parsedDate = inputFormat.parse(date)
            
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val tradeDate = Calendar.getInstance().apply { time = parsedDate!! }
            
            when {
                isSameDay(tradeDate, today) -> "Today"
                isSameDay(tradeDate, yesterday) -> "Yesterday"
                else -> outputFormat.format(parsedDate!!)
            }
        } catch (e: Exception) {
            date
        }
    }
    
    Text(
        text = formattedDate,
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 12.dp)
    )
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun TradeItem(
    trade: TradeData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBuy = trade.side.lowercase() == "buy"
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Buy/Sell icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isBuy) ProfitGreen.copy(alpha = 0.15f)
                            else LossRed.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isBuy) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = trade.side,
                        tint = if (isBuy) ProfitGreen else LossRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = trade.side.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBuy) ProfitGreen else LossRed,
                            fontWeight = FontWeight.Bold
                        )
                        if (trade.orderType.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = trade.orderType.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = trade.pair,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    
                    Text(
                        text = when {
                            !isBuy && trade.entryPrice != null && trade.exitPrice != null ->
                                "${decimalFormat.format(trade.amount)} • ${formatRupiah(trade.entryPrice)} -> ${formatRupiah(trade.exitPrice)}${trade.orderType.takeIf { it.isNotBlank() }?.let { " • ${it.uppercase()}" }.orEmpty()}"
                            else -> "${decimalFormat.format(trade.amount)} @ ${formatTradePrice(trade.price)}${trade.orderType.takeIf { it.isNotBlank() }?.let { " • ${it.uppercase()}" }.orEmpty()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatTradeTotal(trade.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trade.profitLoss?.let { pnl ->
                        Text(
                            text = buildString {
                                append(if (pnl >= 0) "Untung ${formatRupiah(pnl)}" else "Rugi ${formatRupiah(pnl)}")
                                trade.profitLossPercent?.let { append(" • ${formatPercent(it)}") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pnl >= 0) ProfitGreen else LossRed
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    Text(
                        text = timeFormat.format(Date(trade.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeDetailSheet(
    trade: TradeData,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val isBuy = trade.side.lowercase() == "buy"
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()) }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (isBuy) ProfitGreen.copy(alpha = 0.15f)
                                else LossRed.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isBuy) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = trade.side,
                            tint = if (isBuy) ProfitGreen else LossRed,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column {
                        Text(
                            text = "${trade.side.uppercase()} ${trade.pair}",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = dateFormat.format(Date(trade.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Trade details
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    DetailRow(label = "Amount", value = decimalFormat.format(trade.amount))
                    HorizontalDivider(color = DarkSurface, modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow(label = "Price", value = formatTradePrice(trade.price))
                    HorizontalDivider(color = DarkSurface, modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow(label = "Total", value = formatTradeTotal(trade.total))
                    if (trade.orderType.isNotBlank()) {
                        HorizontalDivider(color = DarkSurface, modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(label = "Order Type", value = trade.orderType.uppercase())
                    }
                    
                    trade.entryPrice?.let { entry ->
                        HorizontalDivider(color = DarkSurface, modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(label = "Harga Beli", value = formatRupiah(entry))
                    }
                    
                    trade.exitPrice?.let { exit ->
                        HorizontalDivider(color = DarkSurface, modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(label = "Harga Jual", value = formatRupiah(exit))
                    }
                    
                    trade.profitLoss?.let { pnl ->
                        HorizontalDivider(color = DarkSurface, modifier = Modifier.padding(vertical = 8.dp))
                        DetailRow(
                            label = if (pnl >= 0) "Hasil" else "Hasil",
                            value = buildString {
                                append(if (pnl >= 0) "Untung ${formatRupiah(pnl)}" else "Rugi ${formatRupiah(pnl)}")
                                trade.profitLossPercent?.let { append(" • ${formatPercent(it)}") }
                            },
                            valueColor = if (pnl >= 0) ProfitGreen else LossRed
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Trade ID
            Text(
                text = "Trade ID: ${trade.id}",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = TextPrimary,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatTradePrice(price: Double): String {
    if (price <= 0.0) return "~"
    return formatRupiah(price)
}

private fun formatTradeTotal(total: Double): String {
    if (total <= 0.0) return "~"
    return formatRupiah(total)
}
