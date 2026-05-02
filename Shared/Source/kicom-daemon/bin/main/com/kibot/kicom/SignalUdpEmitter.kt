package com.kibot.kicom

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class SignalUdpEmitter(
    private val targetHost: String = "127.0.0.1",
    private val targetPort: Int = 9998
) {
    private val logger = LoggerFactory.getLogger(SignalUdpEmitter::class.java)
    private val socket = DatagramSocket()
    private val address = InetAddress.getByName(targetHost)
    private val json = Json { encodeDefaults = true }

    fun emit(momentum: MomentumAnalyzer.PairMomentum, indodaxPair: String) {
        val now = System.currentTimeMillis()
        val traceId = "LL-${UUID.randomUUID().toString().take(8).uppercase()}"

        val payload = buildJsonObject {
            put("kind", "lead_lag_breakout")
            put("msgType", "DETECTOR_HIT")
            put("traceId", traceId)
            put("senderBotId", "kicom-daemon-v7")
            put("pairId", indodaxPair)
            put("trend", if (momentum.priceChangePct > 0) "UP" else "DOWN")
            put("detectedAtEpochMs", now)
            put("sentAtEpochMs", now)
            put("expiresAtEpochMs", now + 60000) // 1 minute expiry
            put("confidence", 0.95) // High confidence for lead-lag
            put("conviction", momentum.convictionScore)
            put("expectedNetPct", momentum.priceChangePct * 1.2) // Projection
            put("shortTermReturnPct", momentum.priceChangePct)
            put("forceRotation", true)
        }

        val jsonStr = json.encodeToString(payload)
        val bytes = jsonStr.toByteArray()
        val packet = DatagramPacket(bytes, bytes.size, address, targetPort)

        try {
            socket.soTimeout = 50 // 50ms timeout for ACK
            socket.send(packet)
            
            // Wait for ACK
            val ackBuffer = ByteArray(256)
            val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)
            try {
                socket.receive(ackPacket)
                val ackResponse = String(ackPacket.data, 0, ackPacket.length, Charsets.UTF_8)
                if (ackResponse.contains(traceId)) {
                    logger.info("EMITTED [ACK_CONFIRMED] lead-lag signal for $indodaxPair trace=$traceId")
                } else {
                    logger.warn("EMITTED [ACK_MISMATCH] expected $traceId but got $ackResponse")
                }
            } catch (te: java.net.SocketTimeoutException) {
                logger.warn("EMITTED [ACK_TIMEOUT] signal for $indodaxPair trace=$traceId - engine might be busy")
            }

            logger.info("EMITTED lead-lag signal for $indodaxPair (CDC: ${momentum.symbol}) change=${String.format("%.2f", momentum.priceChangePct)}% conviction=${String.format("%.2f", momentum.convictionScore)} trace=$traceId")
        } catch (e: Exception) {
            logger.error("Failed to send UDP signal: ${e.message}")
        }
    }

    fun close() {
        socket.close()
    }
}
