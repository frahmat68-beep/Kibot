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
        val compactPair = pairSymbol.lowercase().replace("/", "_")
        return ClientOrderId("${deviceId.value}-${term.value}-$compactPair-$epochMillis")
    }
}

