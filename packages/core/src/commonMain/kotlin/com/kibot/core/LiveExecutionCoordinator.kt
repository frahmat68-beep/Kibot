package com.kibot.core

import com.kibot.shared.models.BotId
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.LeaseTerm
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
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
    ): LiveExecutionResult {
        if (existingPersistedOrders.any { it.pairId == executionPlan.signal.pairId }) {
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
        )
    }

    private suspend fun submitManagedOrder(
        botId: BotId,
        deviceId: DeviceId,
        term: LeaseTerm,
        executionPlan: ExecutionPlan,
        exchange: ExchangeGateway,
        controlPlane: ControlPlaneGateway,
    ): LiveExecutionResult {
        val reservation = reserveExecutionActionWithLeaseRecovery(
            botId = botId,
            deviceId = deviceId,
            initialTerm = term,
            executionPlan = executionPlan,
            controlPlane = controlPlane,
        ) ?: return LiveExecutionResult(
            submitted = false,
            message = "Lease tidak valid untuk submit ${executionPlan.signal.pairId.value}; menunggu holder aktif sinkron.",
        )
        val action = reservation.action
        val effectiveTerm = reservation.term
        val clientOrderId = clientOrderIdFactory.create(
            deviceId = deviceId,
            term = effectiveTerm,
            pairSymbol = executionPlan.signal.pairId.value,
        )
        val draftOrder = draftSnapshot(clientOrderId, executionPlan)

        controlPlane.upsertOrderSnapshot(
            botId = botId,
            term = effectiveTerm.value,
            deviceId = deviceId,
            order = draftOrder,
        )

        return try {
            val order = exchange.placeOrder(executionPlan, clientOrderId)
            controlPlane.upsertOrderSnapshot(
                botId = botId,
                term = effectiveTerm.value,
                deviceId = deviceId,
                order = order,
            )
            controlPlane.completeExecutionAction(action.actionId, deviceId, "SUBMITTED")
            LiveExecutionResult(
                submitted = true,
                clientOrderId = clientOrderId,
                order = order,
                message = "${executionPlan.side.name} ${executionPlan.orderType.name} ${executionPlan.signal.pairId.value} berhasil dikirim (${clientOrderId.value}).",
            )
        } catch (error: ExchangeRejectedException) {
            controlPlane.upsertOrderSnapshot(
                botId = botId,
                term = effectiveTerm.value,
                deviceId = deviceId,
                order = draftOrder.copy(
                    status = OrderStatus.REJECTED,
                    updatedAt = Clock.System.now(),
                ),
            )
            controlPlane.completeExecutionAction(action.actionId, deviceId, "FAILED")
            LiveExecutionResult(
                submitted = false,
                clientOrderId = clientOrderId,
                message = "Submit ${executionPlan.side.name} ${executionPlan.signal.pairId.value} ditolak exchange: ${error.message ?: "unknown error"}.",
            )
        } catch (error: Throwable) {
            val reconciledOrder = runCatching {
                exchange.fetchOpenOrders().firstOrNull { it.clientOrderId == clientOrderId }
            }.getOrNull()

            if (reconciledOrder != null) {
                controlPlane.upsertOrderSnapshot(
                    botId = botId,
                    term = effectiveTerm.value,
                    deviceId = deviceId,
                    order = reconciledOrder,
                )
                controlPlane.completeExecutionAction(action.actionId, deviceId, "SUBMITTED")
                LiveExecutionResult(
                    submitted = true,
                    clientOrderId = clientOrderId,
                    order = reconciledOrder,
                    message = "${executionPlan.side.name} ${executionPlan.orderType.name} ${executionPlan.signal.pairId.value} sempat error, tapi berhasil direkonsiliasi dari exchange (${clientOrderId.value}).",
                )
            } else {
                controlPlane.upsertOrderSnapshot(
                    botId = botId,
                    term = effectiveTerm.value,
                    deviceId = deviceId,
                    order = draftOrder.copy(
                        status = OrderStatus.UNKNOWN,
                        updatedAt = Clock.System.now(),
                    ),
                )
                controlPlane.completeExecutionAction(action.actionId, deviceId, "FAILED")
                controlPlane.markConflictSafeMode(
                    botId = botId,
                    reason = "Status order ${clientOrderId.value} ambigu setelah submit gagal: ${error.message ?: "unknown error"}",
                )
                LiveExecutionResult(
                    submitted = false,
                    clientOrderId = clientOrderId,
                    failSafeTriggered = true,
                    message = "Submit ${executionPlan.side.name} ${executionPlan.signal.pairId.value} gagal dan statusnya ambigu, bot masuk SAFE_MODE.",
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
