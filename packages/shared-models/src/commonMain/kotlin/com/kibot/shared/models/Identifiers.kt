package com.kibot.shared.models

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
    fun toDoubleOrZero(): Double = value.trim().toDoubleOrNull()
        ?: parseFlexibleDecimal(value, singleSeparatorLikelyGrouping = false)
    fun toDouble(): Double = toDoubleOrZero()
    fun toInt(): Int = toDoubleOrZero().toInt()
    fun toLong(): Long = toDoubleOrZero().toLong()

    private fun toScaledLong(): Long = (toDoubleOrZero() * SCALE).toLong()

    operator fun plus(other: DecimalValue): DecimalValue =
        fromScaledLong(this.toScaledLong() + other.toScaledLong())

    operator fun plus(other: Double): DecimalValue = this + fromDouble(other)

    operator fun minus(other: DecimalValue): DecimalValue =
        fromScaledLong(this.toScaledLong() - other.toScaledLong())

    operator fun minus(other: Double): DecimalValue = this - fromDouble(other)

    operator fun times(multiplier: Double): DecimalValue =
        fromScaledLong((this.toScaledLong() * multiplier).toLong())

    operator fun times(other: DecimalValue): DecimalValue {
        val a = this.toScaledLong().toDouble()
        val b = other.toScaledLong().toDouble()
        return fromScaledLong((a * b / SCALE).toLong())
    }

    operator fun div(divider: Double): DecimalValue =
        if (divider == 0.0) Zero else fromScaledLong((this.toScaledLong() / divider).toLong())

    operator fun div(other: DecimalValue): Double {
        val otherScaled = other.toScaledLong()
        if (otherScaled == 0L) return 0.0
        return this.toScaledLong().toDouble() / otherScaled.toDouble()
    }

    operator fun compareTo(other: DecimalValue): Int = this.toScaledLong().compareTo(other.toScaledLong())
    operator fun compareTo(other: Double): Int = this.toDoubleOrZero().compareTo(other)
    operator fun compareTo(other: Int): Int = this.toDoubleOrZero().compareTo(other.toDouble())
    operator fun compareTo(other: Long): Int = this.toDoubleOrZero().compareTo(other.toDouble())

    fun absoluteValue(): DecimalValue {
        val scaled = toScaledLong()
        return if (scaled < 0) fromScaledLong(-scaled) else this
    }

    fun toFormattedString(decimals: Int): String {
        val text = toDoubleOrZero().toString()
        if (text.contains("E", ignoreCase = true)) {
            return text
        }
        if (!text.contains(".")) return if (decimals > 0) text + "." + "0".repeat(decimals) else text
        val parts = text.split(".")
        val integral = parts[0]
        val fractional = parts[1].padEnd(decimals, '0').take(decimals)
        return if (decimals > 0) "$integral.$fractional" else integral
    }

    companion object {
        val Zero = DecimalValue("0")
        val Infinity = DecimalValue(Double.POSITIVE_INFINITY.toString())
        private const val SCALE = 100_000_000.0
        fun fromDouble(value: Double): DecimalValue = DecimalValue(
            value.toString(),
        )
        fun fromLong(value: Long): DecimalValue = DecimalValue(value.toString())
        fun fromInt(value: Int): DecimalValue = DecimalValue(value.toString())
        fun fromScaledLong(scaled: Long): DecimalValue = DecimalValue((scaled.toDouble() / SCALE).toString())
        fun parse(value: String?): DecimalValue = DecimalValue(value ?: "0")
    }
}

private fun parseFlexibleDecimal(
    rawValue: String,
    singleSeparatorLikelyGrouping: Boolean,
): Double {
    val filtered = rawValue
        .trim()
        .filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' }
    if (filtered.isBlank()) return 0.0

    val sign = if (filtered.startsWith("-")) "-" else ""
    val unsigned = filtered.removePrefix("-").removePrefix("+")
    if (unsigned.isBlank()) return 0.0

    val normalized = when {
        unsigned.contains('.') && unsigned.contains(',') -> {
            val decimalSeparator = if (unsigned.lastIndexOf('.') > unsigned.lastIndexOf(',')) '.' else ','
            normalizeWithExplicitDecimal(unsigned, decimalSeparator)
        }
        unsigned.count { it == '.' } > 1 -> normalizeRepeatedSeparator(unsigned, '.')
        unsigned.count { it == ',' } > 1 -> normalizeRepeatedSeparator(unsigned, ',')
        unsigned.contains('.') -> normalizeSingleSeparator(unsigned, '.', singleSeparatorLikelyGrouping)
        unsigned.contains(',') -> normalizeSingleSeparator(unsigned, ',', singleSeparatorLikelyGrouping)
        else -> unsigned
    }

    return (sign + normalized).toDoubleOrNull() ?: 0.0
}

private fun normalizeWithExplicitDecimal(unsigned: String, decimalSeparator: Char): String {
    val groupingSeparator = if (decimalSeparator == '.') ',' else '.'
    return buildString(unsigned.length) {
        unsigned.forEachIndexed { index, char ->
            when {
                char == groupingSeparator -> Unit
                char == decimalSeparator && index == unsigned.lastIndexOf(decimalSeparator) -> append('.')
                char.isDigit() -> append(char)
            }
        }
    }
}

private fun normalizeRepeatedSeparator(unsigned: String, separator: Char): String {
    val parts = unsigned.split(separator)
    if (parts.isEmpty()) return unsigned
    val tailGroups = parts.drop(1)
    return if (tailGroups.isNotEmpty() && tailGroups.all { it.length == 3 }) {
        parts.joinToString(separator = "")
    } else {
        val decimalPart = parts.last()
        val integerPart = parts.dropLast(1).joinToString(separator = "")
        if (decimalPart.isEmpty()) integerPart else "$integerPart.$decimalPart"
    }
}

private fun normalizeSingleSeparator(
    unsigned: String,
    separator: Char,
    singleSeparatorLikelyGrouping: Boolean,
): String {
    val parts = unsigned.split(separator)
    if (parts.size != 2) return unsigned
    val integerPart = parts[0]
    val suffix = parts[1]
    if (suffix.isEmpty()) return integerPart
    return if (singleSeparatorLikelyGrouping && suffix.length == 3) {
        integerPart + suffix
    } else {
        "$integerPart.$suffix"
    }
}

operator fun Double.plus(other: DecimalValue): DecimalValue = DecimalValue.fromDouble(this) + other
operator fun Double.minus(other: DecimalValue): DecimalValue = DecimalValue.fromDouble(this) - other
operator fun Double.times(other: DecimalValue): DecimalValue = other * this
operator fun Double.compareTo(other: DecimalValue): Int = this.compareTo(other.toDoubleOrZero())

fun Double.toFormattedString(decimals: Int): String {
    val text = this.toString()
    if (!text.contains(".")) return if (decimals > 0) text + "." + "0".repeat(decimals) else text
    val parts = text.split(".")
    val integral = parts[0]
    val fractional = parts[1].padEnd(decimals, '0').take(decimals)
    return if (decimals > 0) "$integral.$fractional" else integral
}
