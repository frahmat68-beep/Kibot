package com.kibot.controlplane

import com.kibot.shared.models.BotId
import kotlinx.serialization.Serializable

@Serializable
data class ControlPlaneConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val userEmail: String,
    val userPassword: String,
    val botId: BotId = BotId("main"),
) {
    val normalizedUrl: String = supabaseUrl.removeSuffix("/")
}
