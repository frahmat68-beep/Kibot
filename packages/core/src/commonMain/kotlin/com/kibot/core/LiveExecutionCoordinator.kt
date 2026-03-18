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
                message = "Masih ada order aktif tersimpan untuk ${executionPlan.signal.pairId.value}.",
            )
        }

        val orderIntentId = buildOrderIntentId(term, executionPlan)
        val action = controlPlane.reserveExecutionAction(
            botId = botId,
            deviceId = deviceId,
            term = term.value,
            orderIntentId = orderIntentId,
            actionType = "submit_order",
        )
        val clientOrderId = clientOrderIdFactory.create(
            deviceId = deviceId,
            term = term,
            pairSymbol = executionPlan.signal.pairId.value,
        )
        val draftOrder = draftSnapshot(clientOrderId, executionPlan)

        controlPlane.upsertOrderSnapshot(
            botId = botId,
            term = term.value,
            deviceId = deviceId,
            order = draftOrder,
        )

        return try {
            val order = exchange.placeOrder(executionPlan, clientOrderId)
            controlPlane.upsertOrderSnapshot(
                botId = botId,
                term = term.value,
                deviceId = deviceId,
                order = order,
            )
            controlPlane.completeExecutionAction(action.actionId, deviceId, "SUBMITTED")
            LiveExecutionResult(
                submitted = true,
                clientOrderId = clientOrderId,
                order = order,
                message = "Order ${clientOrderId.value} berhasil dikirim.",
            )
        } catch (error: Throwable) {
            val reconciledOrder = runCatching {
                exchange.fetchOpenOrders().firstOrNull { it.clientOrderId == clientOrderId }
            }.getOrNull()

            if (reconciledOrder != null) {
                controlPlane.upsertOrderSnapshot(
                    botId = botId,
                    term = term.value,
                    deviceId = deviceId,
                    order = reconciledOrder,
                )
                controlPlane.completeExecutionAction(action.actionId, deviceId, "SUBMITTED")
                LiveExecutionResult(
                    submitted = true,
                    clientOrderId = clientOrderId,
                    order = reconciledOrder,
                    message = "Order ${clientOrderId.value} sempat error, tapi berhasil direkonsiliasi dari exchange.",
                )
            } else {
                controlPlane.upsertOrderSnapshot(
                    botId = botId,
                    term = term.value,
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
                    message = "Submit order gagal dan statusnya ambigu, bot masuk SAFE_MODE.",
                )
            }
        }
    }

    private fun buildOrderIntentId(term: LeaseTerm, executionPlan: ExecutionPlan): String {
        return listOf(
            "submit",
            term.value.toString(),
            executionPlan.signal.pairId.value,
            executionPlan.signal.setupType.name.lowercase(),
            executionPlan.signal.horizon.name.lowercase(),
            executionPlan.side.name.lowercase(),
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
