package com.kicryp.controlplane

import com.kicryp.shared.models.BotId
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
