package com.kibot.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.kibot.android.MainActivity
import java.text.NumberFormat
import java.util.Locale

// Widget Colors
private val WidgetBackground = Color(0xFF0A0A0A)
private val WidgetSurface = Color(0xFF141414)
private val WidgetProfitGreen = Color(0xFF00C853)
private val WidgetLossRed = Color(0xFFFF5252)
private val WidgetTextPrimary = Color(0xFFFFFFFF)
private val WidgetTextSecondary = Color(0xFFB3B3B3)
private val WidgetStatusOnline = Color(0xFF4CAF50)
private val WidgetStatusOffline = Color(0xFFF44336)

// Preference keys for widget data
object KiBotWidgetKeys {
    val BALANCE = doublePreferencesKey("widget_balance")
    val PNL_TODAY = doublePreferencesKey("widget_pnl_today")
    val TOTAL_RETURN = doublePreferencesKey("widget_total_return")
    val KIDAX_STATUS = stringPreferencesKey("widget_kidax_status")
    val KINANCE_STATUS = stringPreferencesKey("widget_kinance_status")
    val KIBOT_STATUS = stringPreferencesKey("widget_kibot_status")
    val KIDAX_PING = longPreferencesKey("widget_kidax_ping")
    val LAST_UPDATE = longPreferencesKey("widget_last_update")
}

class KiBotWidget : GlanceAppWidget() {
    
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(100.dp, 100.dp),
            DpSize(200.dp, 100.dp),
            DpSize(300.dp, 200.dp)
        )
    )
    
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            
            val balance = prefs[KiBotWidgetKeys.BALANCE] ?: 0.0
            val pnlToday = prefs[KiBotWidgetKeys.PNL_TODAY] ?: 0.0
            val totalReturn = prefs[KiBotWidgetKeys.TOTAL_RETURN] ?: 0.0
            val kidaxStatus = prefs[KiBotWidgetKeys.KIDAX_STATUS] ?: "offline"
            val kinanceStatus = prefs[KiBotWidgetKeys.KINANCE_STATUS] ?: "offline"
            val kibotStatus = prefs[KiBotWidgetKeys.KIBOT_STATUS] ?: "offline"
            val kidaxPing = prefs[KiBotWidgetKeys.KIDAX_PING] ?: 0L
            
            val size = LocalSize.current
            
            when {
                size.width < 150.dp -> SmallWidget(balance, pnlToday, kidaxStatus)
                size.width < 250.dp -> MediumWidget(balance, pnlToday, totalReturn, kidaxStatus)
                else -> LargeWidget(balance, pnlToday, totalReturn, kidaxStatus, kinanceStatus, kibotStatus, kidaxPing)
            }
        }
    }
}

@Composable
private fun StatusDot(isOnline: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(8.dp)
            .cornerRadius(4.dp)
            .background(if (isOnline) WidgetStatusOnline else WidgetStatusOffline)
    ) { }
}

@Composable
private fun SmallWidget(balance: Double, pnlToday: Double, status: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusDot(status == "online")
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = "KiBot",
                style = TextStyle(
                    color = ColorProvider(WidgetTextPrimary),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        Spacer(modifier = GlanceModifier.height(8.dp))
        
        Text(
            text = formatBalanceShort(balance),
            style = TextStyle(
                color = ColorProvider(WidgetTextPrimary),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        )
        
        Text(
            text = formatPnLShort(pnlToday),
            style = TextStyle(
                color = ColorProvider(if (pnlToday >= 0) WidgetProfitGreen else WidgetLossRed),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable
private fun MediumWidget(balance: Double, pnlToday: Double, totalReturn: Double, status: String) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status == "online")
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = "KiBot",
                style = TextStyle(
                    color = ColorProvider(WidgetTextPrimary),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        Spacer(modifier = GlanceModifier.width(16.dp))
        
        Column(
            modifier = GlanceModifier.defaultWeight(),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = formatRupiahWidget(balance),
                style = TextStyle(
                    color = ColorProvider(WidgetTextPrimary),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            
            Spacer(modifier = GlanceModifier.height(4.dp))
            
            Row {
                Text(
                    text = "PnL: ",
                    style = TextStyle(
                        color = ColorProvider(WidgetTextSecondary),
                        fontSize = 11.sp
                    )
                )
                Text(
                    text = formatPnLShort(pnlToday),
                    style = TextStyle(
                        color = ColorProvider(if (pnlToday >= 0) WidgetProfitGreen else WidgetLossRed),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = formatPercentWidget(totalReturn),
                    style = TextStyle(
                        color = ColorProvider(if (totalReturn >= 0) WidgetProfitGreen else WidgetLossRed),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
private fun LargeWidget(
    balance: Double,
    pnlToday: Double,
    totalReturn: Double,
    kidaxStatus: String,
    kinanceStatus: String,
    kibotStatus: String,
    kidaxPing: Long
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(16.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "KiBot Live",
                style = TextStyle(
                    color = ColorProvider(WidgetTextPrimary),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(kidaxStatus == "online")
                Spacer(modifier = GlanceModifier.width(4.dp))
                Text(
                    text = "${kidaxPing}ms",
                    style = TextStyle(
                        color = ColorProvider(WidgetTextSecondary),
                        fontSize = 10.sp
                    )
                )
            }
        }
        
        Spacer(modifier = GlanceModifier.height(12.dp))
        
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(WidgetSurface)
                .cornerRadius(12.dp)
                .padding(12.dp)
        ) {
            Text(
                text = "Total Balance",
                style = TextStyle(
                    color = ColorProvider(WidgetTextSecondary),
                    fontSize = 11.sp
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = formatRupiahWidget(balance),
                style = TextStyle(
                    color = ColorProvider(WidgetTextPrimary),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            
            Spacer(modifier = GlanceModifier.height(8.dp))
            
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "PnL Today",
                        style = TextStyle(
                            color = ColorProvider(WidgetTextSecondary),
                            fontSize = 10.sp
                        )
                    )
                    Text(
                        text = formatPnLShort(pnlToday),
                        style = TextStyle(
                            color = ColorProvider(if (pnlToday >= 0) WidgetProfitGreen else WidgetLossRed),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                
                Spacer(modifier = GlanceModifier.width(24.dp))
                
                Column {
                    Text(
                        text = "Total Return",
                        style = TextStyle(
                            color = ColorProvider(WidgetTextSecondary),
                            fontSize = 10.sp
                        )
                    )
                    Text(
                        text = formatPercentWidget(totalReturn),
                        style = TextStyle(
                            color = ColorProvider(if (totalReturn >= 0) WidgetProfitGreen else WidgetLossRed),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
        
        Spacer(modifier = GlanceModifier.height(12.dp))
        
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BotStatusItem(name = "KiBot", isOnline = kibotStatus == "online")
            Spacer(modifier = GlanceModifier.width(8.dp))
            BotStatusItem(name = "Kinance", isOnline = kinanceStatus == "online")
            Spacer(modifier = GlanceModifier.width(8.dp))
            BotStatusItem(name = "KiDax", isOnline = kidaxStatus == "online")
        }
    }
}

@Composable
private fun BotStatusItem(name: String, isOnline: Boolean) {
    Row(
        modifier = GlanceModifier
            .background(WidgetSurface)
            .cornerRadius(8.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier
                .size(6.dp)
                .cornerRadius(3.dp)
                .background(if (isOnline) WidgetStatusOnline else WidgetStatusOffline)
        ) { }
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = name,
            style = TextStyle(
                color = ColorProvider(WidgetTextPrimary),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

private fun formatBalanceShort(value: Double): String {
    return when {
        value >= 1_000_000_000 -> String.format("%.1fB", value / 1_000_000_000)
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000)
        value >= 1_000 -> String.format("%.0fK", value / 1_000)
        else -> String.format("%.0f", value)
    }
}

private fun formatRupiahWidget(value: Double): String {
    val formatter = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${formatter.format(value.toLong())}"
}

private fun formatPnLShort(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return when {
        kotlin.math.abs(value) >= 1_000_000 -> "${sign}${String.format("%.1f", value / 1_000_000)}M"
        kotlin.math.abs(value) >= 1_000 -> "${sign}${String.format("%.0f", value / 1_000)}K"
        else -> "${sign}${String.format("%.0f", value)}"
    }
}

private fun formatPercentWidget(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return "${sign}${String.format("%.2f", value)}%"
}

class KiBotWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KiBotWidget()
}
