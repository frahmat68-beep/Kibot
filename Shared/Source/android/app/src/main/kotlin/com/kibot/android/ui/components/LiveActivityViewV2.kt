package com.kibot.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kibot.android.data.BotState
import com.kibot.android.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Enhanced Live Activity Display
 * Shows real-time bot status with human-readable messages and timecode
 */
@Composable
fun LiveActivitySectionV2(
    botState: BotState,
    modifier: Modifier = Modifier
) {
    val currentTimeMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTimeMs.longValue = System.currentTimeMillis()
        }
    }
    
    val isActive = botState.effectiveState != "STOPPED"
    val timeSinceLastActivity = currentTimeMs.longValue - botState.lastActivityUpdate
    val isRecentActivity = timeSinceLastActivity < 5000  // Recent if < 5 seconds
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = if (isRecentActivity) androidx.compose.foundation.BorderStroke(1.dp, ProfitGreen.copy(alpha = 0.5f)) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // ===== HEADER WITH REAL-TIME INDICATOR =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Live pulse indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (isActive && isRecentActivity) ProfitGreen else if (isActive) ProfitGreen.copy(alpha = 0.6f) else LossRed,
                                shape = CircleShape
                            )
                    )
                    
                    Column {
                        Text(
                            "🤖 Bot Status",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (isActive) "ACTIVE • TRADING LIVE" else "STOPPED",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isActive) ProfitGreen else LossRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
                
                // Real-time indicator
                Text(
                    if (isRecentActivity) "🔴 LIVE" else "⏱️ ${formatTimeDifference(timeSinceLastActivity)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isRecentActivity) ProfitGreen else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Divider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
                color = DarkSurfaceVariant.copy(alpha = 0.5f)
            )
            
            // ===== MAIN STATUS MESSAGE =====
            val displayMessage = generateCleanStatusMessage(botState)
            if (displayMessage.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .background(
                            color = DarkSurfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    // Status action text (humanized)
                    Text(
                        displayMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        maxLines = 3
                    )
                    
                    // Timecode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTimecode(botState.lastActivityUpdate),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        
                        // Connection status
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .background(
                                        color = if (botState.isConnected) ProfitGreen else LossRed,
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                if (botState.isConnected) "Connected" else "Disconnected",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (botState.isConnected) ProfitGreen else LossRed,
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }
            
            // ===== BOT STATE DETAILS ROW =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Active trades count
                StateChip(
                    label = "${botState.positions.size} Holdings",
                    icon = "📊",
                    isActive = botState.positions.isNotEmpty()
                )
                
                // AI Status
                StateChip(
                    label = botState.heartbeat.kibot.aiStatus.uppercase(),
                    icon = "🧠",
                    isActive = botState.heartbeat.kibot.aiStatus.lowercase() in listOf("active", "online")
                )
                
                // Overall health
                StateChip(
                    label = botState.syncHealth,
                    icon = "💓",
                    isActive = botState.syncHealth == "HEALTHY"
                )
            }
        }
    }
}

/**
 * Status message is already human-readable from server
 * Just display as-is since it's already formatted
 */
private fun parseHumanReadableStatus(statusMessage: String): String {
    return statusMessage.trim()
}

/**
 * Generate clean, simple status message based on bot state
 * Priority: Recent action > Holdings > Waiting
 */
private fun generateCleanStatusMessage(botState: BotState): String {
    // If server message already has emoji + clean format, use it
    if (botState.statusMessage.contains("📥") || 
        botState.statusMessage.contains("📤") ||
        botState.statusMessage.contains("📈") ||
        botState.statusMessage.contains("📉") ||
        botState.statusMessage.contains("🔍") ||
        botState.statusMessage.contains("🛡️") ||
        botState.statusMessage.contains("⏱️")) {
        return botState.statusMessage.trim()
    }
    
    // Otherwise, generate from state
    return when {
        // Has active holdings
        botState.positions.isNotEmpty() -> {
            val top = botState.positions.first()
            val pnlSign = if (top.pnl >= 0) "+" else ""
            val pnlPct = String.format("%.2f", top.pnlPercent)
            "📊 Holding ${top.pair}: $pnlSign$pnlPct% (${botState.positions.size} coins)"
        }
        
        // No holdings - waiting for entry
        botState.heartbeat.KiBot.enabled && botState.effectiveState.contains("RUNNING", ignoreCase = true) ->
            "👀 Watching market for entry"
        
        // Idle
        else -> "⏳ Waiting..."
    }
}

/**
 * Format timestamp to HH:MM:SS format
 */
private fun formatTimecode(timestampMs: Long): String {
    val date = Date(timestampMs)
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
}

/**
 * Format time difference in human-readable format
 * e.g., "5s ago", "2m 30s ago"
 */
private fun formatTimeDifference(diffMs: Long): String {
    val seconds = diffMs / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    
    return when {
        seconds < 1 -> "now"
        seconds < 60 -> "${seconds}s"
        minutes < 60 -> {
            if (remainingSeconds > 0) "${minutes}m ${remainingSeconds}s" else "${minutes}m"
        }
        else -> {
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            "${hours}h ${remainingMinutes}m"
        }
    }
}

/**
 * Small status chip
 */
@Composable
private fun StateChip(
    label: String,
    icon: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(24.dp)
            .wrapContentWidth(),
        color = if (isActive) DarkSurfaceVariant else DarkSurfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                icon,
                fontSize = 10.sp
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) TextPrimary else TextTertiary,
                fontSize = 9.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
