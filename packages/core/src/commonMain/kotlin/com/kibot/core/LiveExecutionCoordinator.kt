package com.kibot.core

import com.kibot.shared.models.BotId
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.LeaseTerm
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

data class LiveExecutionResult(
    val submitted: Boolean,
    val clientOrderId: ClientOrderId? = null,
    val order: OrderSnapshot? = null,
    val failSafeTriggered: Boolean = false,
    val message: String,
)

class LiveExecutionCoordinator(
    private val clientOrderIdFactory: ClientOrderIdFactory = ClientOrderIdFactory(),
) {
    suspend fun submitEntry(
        botId: BotId,
        deviceId: DeviceId,
        term: LeaseTerm,
        executionPlan: ExecutionPlan,
        existingPersistedOrders: List<OrderSnapshot>,
        exchange: ExchangeGateway,
        controlPlane: ControlPlaneGateway,
        bypassLeaseValidation: Boolean = false,
    ): LiveExecutionResult {
        if (existingPersistedOrders.any { it.pairId == executionPlan.signal.pairId }) {
            return LiveExecutionResult(
                submitted = false,
                message = "${executionPlan.side.name} ${executionPlan.signal.pairId.value} ditunda karena masih ada order aktif tersimpan.",
            )
        }
        return submitManagedOrder(
            botId = botId,
            deviceId = deviceId,
            term = term,
            executionPlan = executionPlan,
            exchange = exchange,
            controlPlane = controlPlane,
            bypassLeaseValidation = bypassLeaseValidation,
        )
    }

    suspend fun submitExit(
        botId: BotId,
        deviceId: DeviceId,
        term: LeaseTerm,
        executionPlan: ExecutionPlan,
        existingPersistedOrders: List<OrderSnapshot>,
        exchange: ExchangeGateway,
        controlPlane: ControlPlaneGateway,
        bypassLeaseValidation: Boolean = false,
        bypassSamePairOrderCheck: Boolean = false,
    ): LiveExecutionResult {
        if (!bypassSamePairOrderCheck && existingPersistedOrders.any { it.pairId == executionPlan.signal.pairId }) {
            return LiveExecutionResult(
                submitted = false,
                message = "Exit ${executionPlan.signal.pairId.value} ditunda karena masih ada order aktif untuk pair yang sama.",
            )
        }
        return submitManagedOrder(
            botId = botId,
            deviceId = deviceId,
            term = term,
            executionPlan = executionPlan,
            exchange = exchange,
            controlPlane = controlPlane,
            bypassLeaseValidation = bypassLeaseValidation,
        )
    }

    private suspend fun submitManagedOrder(
        botId: BotId,
        deviceId: DeviceId,
        term: LeaseTerm,
        executionPlan: ExecutionPlan,
        exchange: ExchangeGateway,
        controlPlane: ControlPlaneGateway,
        bypassLeaseValidation: Boolean = false,
    ): LiveExecutionResult {
        println(
            "[LIVE_EXECUTION] submitManagedOrder begin pair=${executionPlan.signal.pairId.value} " +
                "side=${executionPlan.side.name} orderType=${executionPlan.orderType.name} bypassLeaseValidation=$bypassLeaseValidation",
        )
        val reservation = if (bypassLeaseValidation) {
            // EMERGENCY: Skip lease validation - control plane may be unavailable
            null
        } else {
            reserveExecutionActionWithLeaseRecovery(
                botId = botId,
                deviceId = deviceId,
                initialTerm = term,
                executionPlan = executionPlan,
                controlPlane = controlPlane,
            )
        }
        
        if (!bypassLeaseValidation && reservation == null) {
            return LiveExecutionResult(
                submitted = false,
                message = "Lease tidak valid untuk submit ${executionPlan.signal.pairId.value}; menunggu holder aktif sinkron.",
            )
        }
        
        // Use reservation if available, otherwise use initial term (bypass mode)
        val action = reservation?.action
        val effectiveTerm = reservation?.term ?: term
        val clientOrderId = clientOrderIdFactory.create(
            deviceId = deviceId,
            term = effectiveTerm,
            pairSymbol = executionPlan.signal.pairId.value,
        )
        val draftOrder = draftSnapshot(clientOrderId, executionPlan)

        // Only update control plane if not bypassing (control plane may be down)
        if (!bypassLeaseValidation) {
            controlPlane.upsertOrderSnapshot(
                botId = botId,
                term = effectiveTerm.value,
                deviceId = deviceId,
                order = draftOrder,
            )
        }

        return try {
            var placedOrder: OrderSnapshot? = null
            var lastPlaceError: Throwable? = null
            var permanentRejection = false
            for (attempt in 0 until 3) {
                println(
                    "[LIVE_EXECUTION] placeOrder attempt=${attempt + 1} pair=${executionPlan.signal.pairId.value} " +
                        "clientOrderId=${clientOrderId.value}",
                )
                val attemptOrder = runCatching {
                    withTimeoutOrNull(15_000L) {
                        exchange.placeOrder(executionPlan, clientOrderId)
                    }
                }.getOrElse { error ->
                    lastPlaceError = error
                    val message = error.message?.lowercase().orEmpty()
                    if (message.contains("insufficient balance")) {
                        permanentRejection = true
                    }
                    println(
                        "[LIVE_EXECUTION] placeOrder exception attempt=${attempt + 1} pair=${executionPlan.signal.pairId.value} " +
                            "error=${error.message ?: error::class.simpleName ?: "unknown"}",
                    )
                    null
                }
                if (attemptOrder != null) {
                    placedOrder = attemptOrder
                    println(
                        "[LIVE_EXECUTION] placeOrder success attempt=${attempt + 1} pair=${executionPlan.signal.pairId.value} " +
                            "clientOrderId=${clientOrderId.value}",
                    )
                    break
                }
                println(
                    "[LIVE_EXECUTION] placeOrder returned null attempt=${attempt + 1} pair=${executionPlan.signal.pairId.value}",
                )
                if (permanentRejection) {
                    break
                }
                if (attempt < 2) {
                    delay(500L * (attempt + 1))
                }
            }
            val order = placedOrder ?: throw (lastPlaceError ?: error("exchange.placeOrder returned null"))
            if (!bypassLeaseValidation) {
                controlPlane.upsertOrderSnapshot(
                    botId = botId,
                    term = effectiveTerm.value,
                    deviceId = deviceId,
                    order = order,
                )
                action?.let {
                    controlPlane.completeExecutionAction(it.actionId, deviceId, "SUBMITTED")
                }
            }
            LiveExecutionResult(
                submitted = true,
                clientOrderId = clientOrderId,
                order = order,
                message = "${executionPlan.side.name} ${executionPlan.orderType.name} ${executionPlan.signal.pairId.value} berhasil dikirim (${clientOrderId.value}).",
            )
        } catch (error: ExchangeRejectedException) {
            println(
                "[LIVE_EXECUTION] ExchangeRejectedException pair=${executionPlan.signal.pairId.value} " +
                    "message=${error.message ?: "unknown"}",
            )
            if (!bypassLeaseValidation) {
                controlPlane.upsertOrderSnapshot(
                    botId = botId,
                    term = effectiveTerm.value,
                    deviceId = deviceId,
                    order = draftOrder.copy(
                        status = OrderStatus.REJECTED,
                        updatedAt = Clock.System.now(),
                    ),
                )
                action?.let {
                    controlPlane.completeExecutionAction(it.actionId, deviceId, "FAILED")
                }
            }
            LiveExecutionResult(
                submitted = false,
                clientOrderId = clientOrderId,
                message = "Submit ${executionPlan.side.name} ${executionPlan.signal.pairId.value} ditolak exchange: ${error.message ?: "unknown error"}.",
            )
        } catch (error: Throwable) {
            println(
                "[LIVE_EXECUTION] placeOrder throwable pair=${executionPlan.signal.pairId.value} " +
                    "message=${error.message ?: error::class.simpleName ?: "unknown"}",
            )
            var reconciledOrder = runCatching {
                withTimeoutOrNull(5_000L) {
                    exchange.fetchOpenOrders().firstOrNull { it.clientOrderId == clientOrderId }
                }
            }.getOrNull()
            if (reconciledOrder == null) {
                repeat(4) { attempt ->
                    delay(500L)
                    reconciledOrder = runCatching {
                        withTimeoutOrNull(5_000L) {
                            exchange.fetchOpenOrders().firstOrNull { it.clientOrderId == clientOrderId }
                        }
                    }.getOrNull()
                    if (reconciledOrder != null) {
                        return@repeat
                    }
                }
            }

            if (reconciledOrder != null) {
                if (!bypassLeaseValidation) {
                    controlPlane.upsertOrderSnapshot(
                        botId = botId,
                        term = effectiveTerm.value,
                        deviceId = deviceId,
                        order = reconciledOrder,
                    )
                    action?.let {
                        controlPlane.completeExecutionAction(it.actionId, deviceId, "SUBMITTED")
                    }
                }
                LiveExecutionResult(
                    submitted = true,
                    clientOrderId = clientOrderId,
                    order = reconciledOrder,
                    message = "${executionPlan.side.name} ${executionPlan.orderType.name} ${executionPlan.signal.pairId.value} sempat error, tapi berhasil direkonsiliasi dari exchange (${clientOrderId.value}).",
                )
            } else {
                if (!bypassLeaseValidation) {
                    controlPlane.upsertOrderSnapshot(
                        botId = botId,
                        term = effectiveTerm.value,
                        deviceId = deviceId,
                        order = draftOrder.copy(
                            status = OrderStatus.UNKNOWN,
                            updatedAt = Clock.System.now(),
                        ),
                    )
                    action?.let {
                        controlPlane.completeExecutionAction(it.actionId, deviceId, "FAILED")
                    }
                    controlPlane.markConflictSafeMode(
                        botId = botId,
                        reason = "Status order ${clientOrderId.value} ambigu setelah submit gagal: ${error.message ?: "unknown error"}",
                    )
                }
                LiveExecutionResult(
                    submitted = false,
                    clientOrderId = clientOrderId,
                    failSafeTriggered = !bypassLeaseValidation,
                    message = "Submit ${executionPlan.side.name} ${executionPlan.signal.pairId.value} gagal dan statusnya ambigu${if (bypassLeaseValidation) "" else ", bot masuk SAFE_MODE"}.",
                )
            }
        }
    }

    private data class ReservedAction(
        val action: com.kibot.shared.models.ExecutionActionTicket,
        val term: LeaseTerm,
    )

    private suspend fun reserveExecutionActionWithLeaseRecovery(
        botId: BotId,
        deviceId: DeviceId,
        initialTerm: LeaseTerm,
        executionPlan: ExecutionPlan,
        controlPlane: ControlPlaneGateway,
    ): ReservedAction? {
        val firstIntentId = buildOrderIntentId(initialTerm, executionPlan)
        val first = runCatching {
            controlPlane.reserveExecutionAction(
                botId = botId,
                deviceId = deviceId,
                term = initialTerm.value,
                orderIntentId = firstIntentId,
                actionType = "submit_order",
            )
        }
        if (first.isSuccess) {
            return ReservedAction(first.getOrThrow(), initialTerm)
        }
        val error = first.exceptionOrNull()
        if (!isLeaseReservationConflict(error)) return null

        val latestLease = runCatching { controlPlane.fetchLease(botId) }.getOrNull() ?: return null
        val latestTerm = if (latestLease.currentHolder == deviceId) {
            latestLease.term
        } else {
            val forced = runCatching {
                controlPlane.acquireLease(
                    botId = botId,
                    deviceId = deviceId,
                    ttlSeconds = 90,
                )
            }.getOrNull() ?: return null
            forced.term
        }
        if (latestTerm.value == initialTerm.value && latestLease.currentHolder == deviceId) return null

        val retryIntentId = buildOrderIntentId(latestTerm, executionPlan)
        val retry = runCatching {
            controlPlane.reserveExecutionAction(
                botId = botId,
                deviceId = deviceId,
                term = latestTerm.value,
                orderIntentId = retryIntentId,
                actionType = "submit_order",
            )
        }.getOrNull() ?: return null
        return ReservedAction(retry, latestTerm)
    }

    private fun isLeaseReservationConflict(error: Throwable?): Boolean {
        val message = error?.message?.lowercase().orEmpty()
        return message.contains("only the active lease holder") ||
            message.contains("lease is conflicted or expired") ||
            message.contains("stale lease")
    }

    private fun buildOrderIntentId(term: LeaseTerm, executionPlan: ExecutionPlan): String {
        val timeWindow = (Clock.System.now().toEpochMilliseconds() / 5_000L).toString()
        return listOf(
            "submit",
            term.value.toString(),
            executionPlan.signal.pairId.value,
            executionPlan.signal.setupType.name.lowercase(),
            executionPlan.signal.horizon.name.lowercase(),
            executionPlan.side.name.lowercase(),
            timeWindow,
        ).joinToString(":")
    }

    private fun draftSnapshot(
        clientOrderId: ClientOrderId,
        executionPlan: ExecutionPlan,
    ): OrderSnapshot {
        val now = Clock.System.now()
        return OrderSnapshot(
            orderId = OrderId(clientOrderId.value),
            clientOrderId = clientOrderId,
            pairId = executionPlan.signal.pairId,
            side = executionPlan.side,
            orderType = executionPlan.orderType,
            status = OrderStatus.SUBMITTING,
            price = executionPlan.limitPrice ?: executionPlan.signal.entryPrice ?: error("Order draft membutuhkan harga."),
            originalQuantity = executionPlan.quantity,
            executedQuantity = com.kibot.shared.models.DecimalValue.Zero,
            remainingQuantity = executionPlan.quantity,
            createdAt = now,
            updatedAt = now,
        )
    }
}
