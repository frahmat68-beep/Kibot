package com.kibot.aisupport

data class GeminiSupportConfig(
    val enabled: Boolean,
    val apiKey: String,
    val model: String = "gemini-2.0-flash-lite",
    val maxCandidates: Int = 6,
    val minIntervalMinutes: Int = 240,
    val timeoutMillis: Long = 15_000L,
    val maxOutputTokens: Int = 384,
    val hourlyRequestBudget: Int = 2,
    val dailyRequestBudget: Int = 12,
    val failureCooldownMinutes: Int = 120,
) {
    val isUsable: Boolean = enabled && apiKey.isNotBlank()
}
