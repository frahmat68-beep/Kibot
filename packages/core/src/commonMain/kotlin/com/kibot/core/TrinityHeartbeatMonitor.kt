package com.kibot.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * TrinityHeartbeatMonitor - All 3 bots know who's alive
 * 
 * Simple explanation:
 * - Every 10 seconds, each bot says "I'm alive!"
 * - If a bot doesn't say anything for 30 seconds → DEAD
 * - Other bots try to restart the dead bot
 * 
 * Example:
 * Kinance: "I'm alive!" (every 10s)
 * KiDax:   "I'm alive!" (every 10s)
 * KiBot:   "I'm alive!" (every 10s)
 * 
 * If Kinance stops talking:
 * KiBot:   "Eh Kinance mati! Gw restart dia!"
 * KiDax:   "Gw juga coba restart Kinance!"
 */
class TrinityHeartbeatMonitor {
    private val heartbeats = mutableMapOf<String, BotHeartbeat>()
    
    /**
     * Record heartbeat from a bot
     */
    fun recordHeartbeat(
        botName: String,  // "kinance", "kidax", or "kibot"
        timestamp: Instant = Clock.System.now(),
        extraInfo: Map<String, String> = emptyMap(),
    ) {
        heartbeats[botName] = BotHeartbeat(
            botName = botName,
            lastSeen = timestamp,
            status = BotStatus.ALIVE,
            consecutiveMisses = 0,
            extraInfo = extraInfo,
        )
    }
    
    /**
     * Check which bots are dead
     */
    fun checkDeadBots(now: Instant = Clock.System.now()): List<DeadBotAlert> {
        val alerts = mutableListOf<DeadBotAlert>()
        
        val requiredBots = listOf("kinance", "kidax", "kibot")
        
        for (botName in requiredBots) {
            val heartbeat = heartbeats[botName]
            
            if (heartbeat == null) {
                // Bot never sent heartbeat
                alerts.add(DeadBotAlert(
                    botName = botName,
                    reason = "NEVER_SEEN",
                    lastSeenSecondsAgo = null,
                    action = RestartAction.IMMEDIATE_RESTART,
                ))
                continue
            }
            
            val secondsSinceLastSeen = (now - heartbeat.lastSeen).inWholeSeconds
            
            when {
                secondsSinceLastSeen > 60 -> {
                    // Dead for > 1 minute
                    heartbeat.status = BotStatus.DEAD
                    heartbeat.consecutiveMisses++
                    
                    alerts.add(DeadBotAlert(
                        botName = botName,
                        reason = "HEARTBEAT_TIMEOUT_CRITICAL",
                        lastSeenSecondsAgo = secondsSinceLastSeen,
                        action = RestartAction.IMMEDIATE_RESTART,
                        message = "$botName has been dead for ${secondsSinceLastSeen}s - RESTART NOW!",
                    ))
                }
                secondsSinceLastSeen > 30 -> {
                    // Degraded for 30-60 seconds
                    heartbeat.status = BotStatus.DEGRADED
                    heartbeat.consecutiveMisses++
                    
                    alerts.add(DeadBotAlert(
                        botName = botName,
                        reason = "HEARTBEAT_TIMEOUT_WARNING",
                        lastSeenSecondsAgo = secondsSinceLastSeen,
                        action = RestartAction.WARN_ONLY,
                        message = "$botName hasn't responded for ${secondsSinceLastSeen}s - might be dead",
                    ))
                }
                else -> {
                    // Alive
                    if (heartbeat.status != BotStatus.ALIVE) {
                        heartbeat.status = BotStatus.ALIVE
                        heartbeat.consecutiveMisses = 0
                    }
                }
            }
        }
        
        return alerts
    }
    
    /**
     * Get status of all bots
     */
    fun getAllBotStatus(now: Instant = Clock.System.now()): Map<String, BotStatusSummary> {
        val requiredBots = listOf("kinance", "kidax", "kibot")
        
        return requiredBots.associateWith { botName ->
            val heartbeat = heartbeats[botName]
            
            if (heartbeat == null) {
                BotStatusSummary(
                    botName = botName,
                    status = BotStatus.UNKNOWN,
                    lastSeenSecondsAgo = null,
                    message = "Never seen",
                )
            } else {
                val secondsSinceLastSeen = (now - heartbeat.lastSeen).inWholeSeconds
                
                BotStatusSummary(
                    botName = botName,
                    status = heartbeat.status,
                    lastSeenSecondsAgo = secondsSinceLastSeen,
                    message = when {
                        secondsSinceLastSeen <= 30 -> "Healthy"
                        secondsSinceLastSeen <= 60 -> "Degraded"
                        else -> "Dead"
                    },
                    extraInfo = heartbeat.extraInfo,
                )
            }
        }
    }
    
    /**
     * Generate restart command for dead bot
     */
    fun generateRestartCommand(botName: String, serverHost: String): String {
        return when (botName) {
            "kinance" -> """
                ssh -i "SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key" ubuntu@$serverHost \
                  'sudo systemctl restart kinance-engine'
            """.trimIndent()
            
            "kidax" -> """
                ssh -i "SSH_SINGAPORE/SSH_SG1/ssh-key-2026-03-22.key" ubuntu@$serverHost \
                  'sudo systemctl restart kidax-engine'
            """.trimIndent()
            
            "kibot" -> """
                ssh -i "SSH_SINGAPORE/SSH_SG2/ssh-key-2026-03-27.key" ubuntu@$serverHost \
                  'sudo systemctl restart kibot-engine'
            """.trimIndent()
            
            else -> "# Unknown bot: $botName"
        }
    }
}

enum class BotStatus {
    ALIVE,      // Heartbeat < 30s ago
    DEGRADED,   // Heartbeat 30-60s ago
    DEAD,       // Heartbeat > 60s ago
    UNKNOWN,    // Never seen
}

enum class RestartAction {
    IMMEDIATE_RESTART,  // Bot is dead, restart NOW
    WARN_ONLY,          // Bot degraded, just warn
    NO_ACTION,          // Bot healthy
}

data class BotHeartbeat(
    val botName: String,
    var lastSeen: Instant,
    var status: BotStatus,
    var consecutiveMisses: Int,
    val extraInfo: Map<String, String>,
)

data class DeadBotAlert(
    val botName: String,
    val reason: String,
    val lastSeenSecondsAgo: Long?,
    val action: RestartAction,
    val message: String = "",
)

data class BotStatusSummary(
    val botName: String,
    val status: BotStatus,
    val lastSeenSecondsAgo: Long?,
    val message: String,
    val extraInfo: Map<String, String> = emptyMap(),
)
