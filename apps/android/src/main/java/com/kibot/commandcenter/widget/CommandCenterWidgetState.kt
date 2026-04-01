package com.kibot.commandcenter.widget

data class CommandCenterWidgetState(
    val title: String = "KiBot",
    val totalLabel: String = "Rp0",
    val pnlLabel: String = "+Rp0",
    val pnlPctLabel: String = "+0.0%",
    val pingLabel: String = "KiDax -- ms",
    val accentRole: AccentRole = AccentRole.NEUTRAL,
    val updatedAtEpochMs: Long = 0L,
) {
    enum class AccentRole { POSITIVE, NEGATIVE, NEUTRAL, WARN }
}
