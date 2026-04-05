package com.kibot.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kibot.android.data.BotState
import com.kibot.android.ui.theme.*

@Composable
fun LiveActivitySection(
    botState: BotState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header with pulse indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsing indicator
                    PulseIndicator(
                        isActive = botState.effectiveState.name != "STOPPED"
                    )
                    
                    Column {
                        Text(
                            "Live Activity",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                botState.effectiveState.name.replace("_", " "),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            // AI status indicator in Live Activity
                            val aiActive = botState.heartbeat.kibot.aiStatus.lowercase() in listOf("active", "online")
                            Surface(
                                modifier = Modifier
                                    .size(4.dp),
                                shape = CircleShape,
                                color = if (aiActive) ProfitGreen else LossRed
                            ) {}
                        }
                    }
                    }
                }
                
                Icon(
                    Icons.Filled.TrendingUp,
                    contentDescription = null,
                    tint = ProfitGreen,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Timeline (last 3 activities)
            if (botState.statusMessage.isNotBlank()) {
                TimelineItem(
                    activity = botState.statusMessage,
                    isLatest = true,
                    timestamp = "Now"
                )
            }
        }
    }
}

@Composable
private fun PulseIndicator(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing circle
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = StatusOnline.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
            )
        }
        
        // Inner solid circle
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(
                    color = if (isActive) StatusOnline else StatusOffline,
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun TimelineItem(
    activity: String,
    isLatest: Boolean,
    timestamp: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Timeline dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = if (isLatest) ProfitGreen else TextTertiary,
                    shape = CircleShape
                )
                .align(Alignment.Top)
                .offset(y = 6.dp)
        )
        
        // Activity text
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                activity
                    .take(60)
                    .replace("Rp", "")
                    .replace("IDR", ""),
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontSize = 11.sp,
                maxLines = 2
            )
            
            Text(
                timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
fun BotActivityIndicator(
    botState: BotState,
    modifier: Modifier = Modifier
) {
    val isActive = botState.effectiveState.name != "STOPPED"
    val indicatorColor by animateColorAsState(
        targetValue = if (isActive) ProfitGreen else LossRed,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000)
        )
    )
    
    Surface(
        modifier = modifier
            .size(12.dp),
        shape = CircleShape,
        color = indicatorColor.copy(alpha = if (isActive) 1f else 0.5f)
    ) {}
}
