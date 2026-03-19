package com.kibot.aisupport

data class GeminiSupportConfig(
    val enabled: Boolean,
    val apiKey: String,
    val model: String = "gemini-2.0-flash-lite",
    val maxCandidates: Int = 6,
    val minIntervalMinutes: Int = 240,
    val timeoutMillis: Long = 15_000L,
) {
    val isUsable: Boolean = enabled && apiKey.isNotBlank()
}
