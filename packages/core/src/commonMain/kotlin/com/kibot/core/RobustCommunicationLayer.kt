package com.kibot.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * RobustCommunicationLayer - UDP with fallback, retry, queue
 * 
 * Problem: UDP packet loss, network timeout
 * Solution:
 * 1. Send message via UDP (primary)
 * 2. If fails → queue locally
 * 3. Retry every 10 seconds
 * 4. Bots query message queue if UDP unavailable
 * 5. Fallback to local rules (bot trades independently)
 */
class RobustCommunicationLayer {
    private val messageQueue = ConcurrentLinkedQueue<QueuedMessage>()
    // FIX: Changed from mutableMapOf to ConcurrentHashMap for thread safety
    // These maps are accessed from multiple coroutines/threads (sendMessage, retryFailedMessages, getStatus)
    // Non-thread-safe maps can cause ConcurrentModificationException, lost updates, or corrupted state
    private val deliveredMessages = ConcurrentHashMap<String, Instant>()
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val udpMutex = Mutex()
    
    /**
     * Send message: Try UDP, if fails → queue
     */
    suspend fun sendMessage(
        message: BotMessage,
        targetBots: List<String>,  // "kinance", "kidax", "kibot"
        udpSender: suspend (BotMessage) -> Boolean,
    ): SendResult {
        val messageId = "${Clock.System.now().toEpochMilliseconds()}-${message.sender}"
        
        // Try UDP first
        var udpSuccess = false
        var udpError: String? = null
        
        try {
            udpMutex.lock()
            try {
                udpSuccess = udpSender(message)
            } finally {
                udpMutex.unlock()
            }
        } catch (e: Exception) {
            udpError = e.message
            udpSuccess = false
        }
        
        if (udpSuccess) {
            deliveredMessages[messageId] = Clock.System.now()
            failedAttempts.remove(messageId)
            
            return SendResult(
                messageId = messageId,
                sent = true,
                method = "UDP",
                timestamp = Clock.System.now(),
            )
        } else {
            // UDP failed → queue for retry
            targetBots.forEach { bot ->
                messageQueue.add(QueuedMessage(
                    id = "${messageId}-$bot",
                    message = message,
                    targetBot = bot,
                    queuedAt = Clock.System.now(),
                    attempts = 1,
                ))
            }
            
            failedAttempts[messageId] = (failedAttempts[messageId] ?: 0) + 1
            
            return SendResult(
                messageId = messageId,
                sent = false,
                method = "QUEUED",
                error = udpError,
                timestamp = Clock.System.now(),
            )
        }
    }
    
    /**
     * Get queued messages for a bot
     */
    fun getQueuedMessages(botName: String): List<BotMessage> {
        return messageQueue
            .filter { it.targetBot == botName }
            .map { it.message }
    }
    
    /**
     * Mark message as delivered
     */
    fun markDelivered(messageId: String) {
        messageQueue.removeIf { it.id == messageId }
        deliveredMessages[messageId] = Clock.System.now()
    }
    
    /**
     * Retry failed messages (call every 10 seconds)
     */
    fun retryFailedMessages(
        udpSender: (BotMessage) -> Boolean,
    ): RetryResult {
        var retried = 0
        var succeeded = 0
        
        val toRemove = mutableListOf<QueuedMessage>()
        
        messageQueue.forEach { queued ->
            if (queued.attempts < 5) {  // Max 5 attempts
                queued.attempts++
                
                try {
                    if (udpSender(queued.message)) {
                        succeeded++
                        toRemove.add(queued)
                    } else {
                        retried++
                    }
                } catch (e: Exception) {
                    retried++
                }
            } else {
                // Give up after 5 attempts
                toRemove.add(queued)
            }
        }
        
        toRemove.forEach { messageQueue.remove(it) }
        
        return RetryResult(
            queueSize = messageQueue.size,
            retried = retried,
            succeeded = succeeded,
        )
    }
    
    /**
     * Bot can query communication status
     */
    fun getStatus(): CommunicationStatus {
        return CommunicationStatus(
            queuedMessages = messageQueue.size,
            deliveredMessages = deliveredMessages.size,
            failedAttempts = failedAttempts.size,
            largestFailureCount = failedAttempts.values.maxOrNull() ?: 0,
            queueOldestMessage = messageQueue.minByOrNull { it.queuedAt }?.queuedAt,
        )
    }
}

/**
 * Message format for inter-bot communication
 */
data class BotMessage(
    val sender: String,  // "kinance", "kidax", or "kibot"
    val type: MessageType,
    val payload: String,  // JSON
    val timestamp: Instant = Clock.System.now(),
    val priority: Int = 0,  // 0=low, 1=normal, 2=high
    val ttlMs: Long = 500_000,  // 500 seconds default
)

enum class MessageType {
    PUMP_SIGNAL,           // Kinance → KiBot: "Pump detected"
    POSITION_UPDATE,       // KiDax → KiBot: "Bought BTC"
    BUY_REQUEST,           // KiDax → KiBot: "Want to buy ABC"
    BUY_APPROVAL,          // KiBot → KiDax: "Approved!"
    BUY_REJECTION,         // KiBot → KiDax: "VETO!"
    SELL_REQUEST,          // KiDax → KiBot: "Want to sell ABC"
    SELL_APPROVAL,         // KiBot → KiDax: "Approved!"
    SELL_REJECTION,        // KiBot → KiDax: "HOLD!"
    HEARTBEAT,             // Any bot: "I'm alive"
    ALERT,                 // Any bot: "Emergency!"
    PRICE_UPDATE,          // Kinance/KiDax: Price info
}

data class QueuedMessage(
    val id: String,
    val message: BotMessage,
    val targetBot: String,
    val queuedAt: Instant,
    var attempts: Int,
)

data class SendResult(
    val messageId: String,
    val sent: Boolean,
    val method: String,  // "UDP" or "QUEUED"
    val error: String? = null,
    val timestamp: Instant,
)

data class RetryResult(
    val queueSize: Int,
    val retried: Int,
    val succeeded: Int,
)

data class CommunicationStatus(
    val queuedMessages: Int,
    val deliveredMessages: Int,
    val failedAttempts: Int,
    val largestFailureCount: Int,
    val queueOldestMessage: Instant?,
)
