package com.kibot.commandcenter.data.remote

import com.kibot.shared.models.CommandCenterCommandRequest
import kotlinx.datetime.Clock
import java.nio.ByteBuffer
import java.nio.ByteOrder

object CommandTransportCodec {
    private const val MAGIC = 0x4B43 // KC
    private const val VERSION = 1

    fun encode(command: String, argument: String? = null, idempotencyKey: String? = null, sequenceId: Int): ByteArray {
        val commandBytes = command.encodeToByteArray().take(24).toByteArray()
        val argumentBytes = (argument ?: "").encodeToByteArray().take(32).toByteArray()
        val keyBytes = (idempotencyKey ?: "").encodeToByteArray().take(24).toByteArray()
        val buffer = ByteBuffer.allocate(2 + 1 + 4 + 1 + commandBytes.size + 1 + argumentBytes.size + 1 + keyBytes.size + 8)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(MAGIC.toShort())
        buffer.put(VERSION.toByte())
        buffer.putInt(sequenceId)
        buffer.put(commandBytes.size.toByte())
        buffer.put(commandBytes)
        buffer.put(argumentBytes.size.toByte())
        buffer.put(argumentBytes)
        buffer.put(keyBytes.size.toByte())
        buffer.put(keyBytes)
        buffer.putLong(Clock.System.now().toEpochMilliseconds())
        return buffer.array()
    }

    fun decode(bytes: ByteArray): CommandCenterCommandRequest? {
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.short.toInt() and 0xffff
        if (magic != MAGIC) return null
        buffer.get()
        buffer.int
        val commandLen = buffer.get().toInt() and 0xff
        val command = ByteArray(commandLen).also { buffer.get(it) }.decodeToString()
        val argumentLen = buffer.get().toInt() and 0xff
        val argument = ByteArray(argumentLen).also { buffer.get(it) }.decodeToString().ifBlank { null }
        val keyLen = buffer.get().toInt() and 0xff
        val key = ByteArray(keyLen).also { buffer.get(it) }.decodeToString().ifBlank { null }
        val issuedAt = buffer.long
        return CommandCenterCommandRequest(command = command, argument = argument, idempotencyKey = key, issuedAtEpochMs = issuedAt)
    }
}
