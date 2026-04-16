package com.kicryp.shared.models

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class UserId(val value: String)

@Serializable
@JvmInline
value class BotId(val value: String)

@Serializable
@JvmInline
value class DeviceId(val value: String)

@Serializable
@JvmInline
value class LeaseTerm(val value: Long)

@Serializable
@JvmInline
value class CommandId(val value: String)

@Serializable
@JvmInline
value class ExecutionActionId(val value: String)

@Serializable
@JvmInline
value class OrderId(val value: String)

@Serializable
@JvmInline
value class ClientOrderId(val value: String)

@Serializable
@JvmInline
value class FillId(val value: String)

@Serializable
@JvmInline
value class PositionId(val value: String)

@Serializable
@JvmInline
value class PairId(val value: String)

@Serializable
@JvmInline
value class DecimalValue(val value: String) {
    fun toDoubleOrZero(): Double = value.toDoubleOrNull() ?: 0.0

    companion object {
        val Zero = DecimalValue("0")
        fun fromDouble(value: Double): DecimalValue = DecimalValue(
            value.toString(),
        )
    }
}

