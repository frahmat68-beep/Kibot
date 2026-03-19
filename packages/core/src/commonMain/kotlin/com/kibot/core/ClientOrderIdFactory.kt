package com.kibot.core

import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.LeaseTerm
import kotlinx.datetime.Clock

class ClientOrderIdFactory {
    fun create(
        deviceId: DeviceId,
        term: LeaseTerm,
        pairSymbol: String,
        epochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): ClientOrderId {
        val compactDevice = deviceId.value
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .takeLast(6)
            .ifBlank { "device" }
        val compactPair = pairSymbol
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "pair" }
        val compactTerm = term.value.toString(36)
        val compactEpoch = epochMillis.toString(36)
        val candidate = "$compactDevice-$compactTerm-$compactPair-$compactEpoch"
        return ClientOrderId(candidate.take(36))
    }
}
