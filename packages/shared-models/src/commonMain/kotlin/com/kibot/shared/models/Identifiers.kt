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
data class DecimalValue(val value: String) : Comparable<DecimalValue> {
    fun toDoubleOrZero(): Double = value.toDoubleOrNull() ?: 0.0
    fun toDouble(): Double = toDoubleOrZero()
    fun toInt(): Int = toDoubleOrZero().toInt()
    fun toLong(): Long = toDoubleOrZero().toLong()

    // High-precision math using Long Fixed-Point (Base 10^8)
    fun toScaledLong(): Long {
        val d = toDoubleOrZero()
        return (d * 100_000_000).toLong()
    }

    operator fun plus(other: DecimalValue): DecimalValue = fromScaledLong(this.toScaledLong() + other.toScaledLong())
    operator fun plus(other: Double): DecimalValue = this + fromDouble(other)
    operator fun minus(other: DecimalValue): DecimalValue = fromScaledLong(this.toScaledLong() - other.toScaledLong())
    operator fun minus(other: Double): DecimalValue = this - fromDouble(other)
    
    operator fun times(multiplier: Double): DecimalValue = fromScaledLong((this.toScaledLong() * multiplier).toLong())
    operator fun times(other: DecimalValue): DecimalValue {
        // Use Double for intermediate to avoid Long overflow on product, then back to scaled long
        val a = this.toScaledLong().toDouble()
        val b = other.toScaledLong().toDouble()
        return fromScaledLong((a * b / 100_000_000.0).toLong())
    }

    operator fun div(divider: Double): DecimalValue = if (divider == 0.0) Zero else fromScaledLong((this.toScaledLong() / divider).toLong())
    operator fun div(other: DecimalValue): Double {
        if (other.toScaledLong() == 0L) return 0.0
        return this.toScaledLong().toDouble() / other.toScaledLong().toDouble()
    }

    fun divide(other: DecimalValue): DecimalValue {
        if (other.toScaledLong() == 0L) return Zero
        val a = this.toScaledLong().toDouble()
        val b = other.toScaledLong().toDouble()
        return fromDouble(a / b)
    }

    override operator fun compareTo(other: DecimalValue): Int = this.toScaledLong().compareTo(other.toScaledLong())
    operator fun compareTo(other: Double): Int = this.toDoubleOrZero().compareTo(other)
    operator fun compareTo(other: Int): Int = this.toDoubleOrZero().compareTo(other.toDouble())
    operator fun compareTo(other: Long): Int = this.toDoubleOrZero().compareTo(other.toDouble())

    fun absoluteValue(): DecimalValue {
        val scaled = toScaledLong()
        return if (scaled < 0) fromScaledLong(-scaled) else this
    }

    fun toFormattedString(decimals: Int): String {
        val d = toDoubleOrZero()
        // Multiplatform-safe decimal formatting
        val s = d.toString()
        if (s.contains("E", ignoreCase = true)) {
             // Handle scientific notation for small/large numbers
             return d.toDouble().toString() // Fallback
        }
        if (!s.contains(".")) return s + "." + "0".repeat(decimals)
        val parts = s.split(".")
        val integral = parts[0]
        val fractional = parts[1].padEnd(decimals, '0').take(decimals)
        return if (decimals > 0) "$integral.$fractional" else integral
    }

    companion object {
        val Zero = DecimalValue("0")
        val ZERO = Zero
        val Infinity = DecimalValue(Double.POSITIVE_INFINITY.toString())
        private const val SCALE = 100_000_000L

        fun fromDouble(value: Double): DecimalValue = DecimalValue(value.toString())
        fun fromLong(value: Long): DecimalValue = DecimalValue(value.toString())
        fun fromInt(value: Int): DecimalValue = DecimalValue(value.toString())
        
        fun from(value: Double): DecimalValue = fromDouble(value)
        fun from(value: Int): DecimalValue = fromInt(value)
        fun from(value: Long): DecimalValue = fromLong(value)
        fun from(value: String?): DecimalValue = parse(value)
        
        fun fromScaledLong(scaled: Long): DecimalValue = DecimalValue((scaled.toDouble() / SCALE).toString())
        
        fun parse(value: String?): DecimalValue = DecimalValue(value ?: "0")

        fun minOf(a: DecimalValue, b: DecimalValue): DecimalValue = if (a.toScaledLong() <= b.toScaledLong()) a else b
        fun minOf(a: DecimalValue, b: DecimalValue, c: DecimalValue): DecimalValue = minOf(a, minOf(b, c))
        fun minOf(a: DecimalValue, b: DecimalValue, c: DecimalValue, d: DecimalValue): DecimalValue = minOf(a, minOf(b, c, d))
        fun maxOf(a: DecimalValue, b: DecimalValue): DecimalValue = if (a.toScaledLong() >= b.toScaledLong()) a else b
        fun maxOf(a: DecimalValue, b: DecimalValue, c: DecimalValue): DecimalValue = maxOf(a, maxOf(b, c))
    }
}

operator fun Double.plus(other: DecimalValue): DecimalValue = DecimalValue.fromDouble(this) + other
operator fun Double.minus(other: DecimalValue): DecimalValue = DecimalValue.fromDouble(this) - other
operator fun Double.times(other: DecimalValue): DecimalValue = other * this
operator fun Double.compareTo(other: DecimalValue): Int = this.compareTo(other.toDoubleOrZero())

fun Double.toFormattedString(decimals: Int): String {
    val s = this.toString()
    if (!s.contains(".")) return s + "." + "0".repeat(decimals)
    val parts = s.split(".")
    val integral = parts[0]
    val fractional = parts[1].padEnd(decimals, '0').take(decimals)
    return if (decimals > 0) "$integral.$fractional" else integral
}

fun <T> Iterable<T>.sumOf(selector: (T) -> DecimalValue): DecimalValue {
    var sum = DecimalValue.ZERO
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

fun Iterable<DecimalValue>.average(): DecimalValue {
    val count = this.toList().size
    if (count == 0) return DecimalValue.ZERO
    return this.sumOf { it } / count.toDouble()
}

operator fun Double.div(other: DecimalValue): DecimalValue {
    if (other.toScaledLong() == 0L) return DecimalValue.Infinity
    return DecimalValue.fromDouble(this / other.toDouble())
}

