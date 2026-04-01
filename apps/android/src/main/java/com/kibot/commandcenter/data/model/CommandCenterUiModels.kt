package com.kibot.commandcenter.data.model

import com.kibot.shared.models.CommandCenterLiveSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class ServerPaneState(
    val serverKey: String,
    val label: String,
    val snapshot: CommandCenterLiveSnapshot? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val lastError: String? = null,
    val lastUpdatedEpochMs: Long = 0L,
)

enum class ConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
}

data class CommandCenterUiState(
    val totalEquityLabel: String = "Rp0",
    val pnlTodayLabel: String = "+Rp0",
    val pnlTodayPctLabel: String = "+0.0%",
    val return7dLabel: String = "+Rp0",
    val return7dPctLabel: String = "+0.0%",
    val return30dLabel: String = "+Rp0",
    val return30dPctLabel: String = "+0.0%",
    val equityHistory: List<Double> = emptyList(),
    val systemHealthLabel: String = "BOOT",
    val latencyLabel: String = "-- ms",
    val kidax: ServerPaneState = ServerPaneState("kidax", "KiDax"),
    val kinance: ServerPaneState = ServerPaneState("kinance", "Kinance"),
    val consoleLines: List<ConsoleLine> = emptyList(),
    val selectedTab: DashboardTab = DashboardTab.ALL,
)

data class ConsoleLine(
    val role: ConsoleRole,
    val text: String,
    val timestampEpochMs: Long,
)

enum class ConsoleRole {
    USER,
    SYSTEM,
    ERROR,
}

enum class DashboardTab {
    ALL,
    KIDAX,
    KINANCE,
    LEDGER,
}
