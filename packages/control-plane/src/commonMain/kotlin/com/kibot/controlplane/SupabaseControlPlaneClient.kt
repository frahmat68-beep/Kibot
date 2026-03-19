package com.kibot.controlplane

import com.kibot.core.ControlPlaneGateway
import com.kibot.core.DeviceRegistration
import com.kibot.shared.models.AdvisorySeverity
import com.kibot.shared.models.AuditLogRecord
import com.kibot.shared.models.BotDesiredState
import com.kibot.shared.models.BotEffectiveState
import com.kibot.shared.models.BotId
import com.kibot.shared.models.BotMode
import com.kibot.shared.models.BotStateSnapshot
import com.kibot.shared.models.BotUpdateRecommendation
import com.kibot.shared.models.CommandEnvelope
import com.kibot.shared.models.CommandId
import com.kibot.shared.models.CommandStatus
import com.kibot.shared.models.CommandType
import com.kibot.shared.models.DailyEquityHistoryPoint
import com.kibot.shared.models.DailyRiskSnapshot
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.DistrustLabel
import com.kibot.shared.models.DeviceDescriptor
import com.kibot.shared.models.DeviceId
import com.kibot.shared.models.DevicePlatform
import com.kibot.shared.models.DeviceRole
import com.kibot.shared.models.EdgeConfidence
import com.kibot.shared.models.EncryptedCredentialBundle
import com.kibot.shared.models.EngineHeartbeatSnapshot
import com.kibot.shared.models.EngineLeaseSnapshot
import com.kibot.shared.models.ExecutionActionId
import com.kibot.shared.models.ExecutionActionTicket
import com.kibot.shared.models.LeaseState
import com.kibot.shared.models.LeaseTerm
import com.kibot.shared.models.LogLevel
import com.kibot.shared.models.MarketRegime
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import com.kibot.shared.models.PairScore
import com.kibot.shared.models.ProfitProtectionStatus
import com.kibot.shared.models.RiskLadderLevel
import com.kibot.shared.models.RuntimeIntelligenceUpdate
import com.kibot.shared.models.StrategyMode
import com.kibot.shared.models.SyncHealth
import com.kibot.shared.models.UserId
import com.kibot.shared.models.WeeklyAdaptationPlan
import com.kibot.shared.models.WeeklyLearningSummary
import com.kibot.shared.models.SetupType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

class SupabaseControlPlaneClient internal constructor(
    private val config: ControlPlaneConfig,
    private val client: HttpClient,
    private val json: Json,
) : ControlPlaneGateway {
    private val sessionMutex = Mutex()
    private var session: Session? = null

    override suspend fun registerDevice(registration: DeviceRegistration): DeviceDescriptor {
        val row = rpc<RpcRegisterDeviceRequest, DeviceRow>(
            function = "rpc_register_device",
            body = RpcRegisterDeviceRequest(
                deviceId = registration.deviceId.value,
                displayName = registration.displayName,
                platform = registration.platform.name,
                role = registration.role.name,
            ),
        )
        return row.toDeviceDescriptor()
    }

    override suspend fun fetchBotState(botId: BotId): BotStateSnapshot? {
        return selectSingle<BotStateRow>(
            table = "bot_state",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
        )?.toBotStateSnapshot()
    }

    override suspend fun fetchLease(botId: BotId): EngineLeaseSnapshot? {
        return selectSingle<LeaseRow>(
            table = "engine_leases",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
        )?.toLeaseSnapshot()
    }

    override suspend fun fetchDevices(botId: BotId): List<DeviceDescriptor> {
        return selectList<DeviceRow>(
            table = "devices",
            orderBy = "updated_at.desc",
        ).map(DeviceRow::toDeviceDescriptor)
    }

    override suspend fun fetchDailyRisk(botId: BotId, date: LocalDate): DailyRiskSnapshot? {
        return selectSingle<DailyEquityRow>(
            table = "daily_equity",
            filters = mapOf(
                "bot_id" to "eq.${botId.value}",
                "equity_date" to "eq.$date",
            ),
        )?.toDailyRiskSnapshot()
    }

    override suspend fun fetchDailyRiskHistory(botId: BotId, days: Int): List<DailyEquityHistoryPoint> {
        return selectList<DailyEquityRow>(
            table = "daily_equity",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
            orderBy = "equity_date.desc",
            limit = days,
        ).mapNotNull { it.toDailyEquityHistoryPoint() }
            .sortedBy { it.date }
    }

    override suspend fun upsertDailyRisk(
        botId: BotId,
        date: LocalDate,
        snapshot: DailyRiskSnapshot,
    ) {
        upsertTable(
            table = "daily_equity",
            body = buildJsonObject {
                put("bot_id", botId.value)
                put("equity_date", date.toString())
                put("opening_equity_idr", decimalJson(snapshot.openingEquityIdr))
                put("current_equity_idr", decimalJson(snapshot.currentEquityIdr))
                put("realized_pnl_idr", decimalJson(snapshot.realizedPnlIdr))
                put("unrealized_pnl_idr", decimalJson(snapshot.unrealizedPnlIdr))
                put("drawdown_pct", snapshot.drawdownPct)
                put("hard_daily_loss_limit_pct", snapshot.hardDailyLossLimitPct)
                put("hard_stop_triggered", snapshot.hardStopTriggered)
                put("rebase_pending", snapshot.rebasePending)
                put("risk_ladder_level", snapshot.riskLadderLevel.name)
                put("weekly_drawdown_pct", snapshot.weeklyDrawdownPct)
                put("loss_streak_count", snapshot.lossStreakCount)
                put("performance_decay_detected", snapshot.performanceDecayDetected)
                put("high_watermark_equity_idr", decimalJson(snapshot.highWatermarkEquityIdr))
                put("giveback_pct", snapshot.givebackPct)
                put("profit_protection_status", snapshot.profitProtectionStatus.name)
            },
            onConflict = "bot_id,equity_date",
        )
    }

    override suspend fun fetchPendingCommands(botId: BotId, deviceId: DeviceId, limit: Int): List<CommandEnvelope> {
        return selectList<CommandRow>(
            table = "command_queue",
            filters = mapOf(
                "bot_id" to "eq.${botId.value}",
                "status" to "eq.${CommandStatus.QUEUED.name}",
            ),
            orderBy = "created_at.asc",
            limit = limit,
        ).filter { row ->
            row.targetDeviceId == null || row.targetDeviceId == deviceId.value
        }.map(CommandRow::toCommandEnvelope)
    }

    override suspend fun setDesiredState(botId: BotId, desiredState: BotDesiredState) {
        patchTable(
            table = "bot_state",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
            body = buildJsonObject {
                put("desired_state", desiredState.name)
            },
        )
    }

    override suspend fun acquireLease(botId: BotId, deviceId: DeviceId, ttlSeconds: Int): EngineLeaseSnapshot {
        val row = rpc<RpcAcquireLeaseRequest, LeaseRow>(
            function = "rpc_acquire_engine_lease",
            body = RpcAcquireLeaseRequest(
                botId = botId.value,
                requesterDeviceId = deviceId.value,
                ttlSeconds = ttlSeconds,
            ),
        )
        return row.toLeaseSnapshot()
    }

    override suspend fun releaseLease(
        botId: BotId,
        deviceId: DeviceId,
        term: Long,
        reason: String?,
    ): EngineLeaseSnapshot {
        val row = rpc<RpcReleaseLeaseRequest, LeaseRow>(
            function = "rpc_release_engine_lease",
            body = RpcReleaseLeaseRequest(
                botId = botId.value,
                requesterDeviceId = deviceId.value,
                term = term,
                reason = reason,
            ),
        )
        return row.toLeaseSnapshot()
    }

    override suspend fun appendHeartbeat(snapshot: EngineHeartbeatSnapshot) {
        rpcUnit(
            function = "rpc_append_heartbeat",
            body = RpcHeartbeatRequest(
                botId = snapshot.botId.value,
                deviceId = snapshot.deviceId.value,
                term = snapshot.term?.value,
                isMaster = snapshot.isMaster,
                desiredState = snapshot.desiredState.name,
                effectiveState = snapshot.effectiveState.name,
                syncHealth = snapshot.health.syncHealth.name,
                healthStatus = snapshot.health.status.name,
                websocketHealthy = snapshot.health.websocketHealthy,
                exchangeReachable = snapshot.health.exchangeReachable,
                supabaseReachable = snapshot.health.supabaseReachable,
                batteryPercent = snapshot.health.batteryPercent,
                charging = snapshot.health.charging,
                networkMetered = snapshot.health.networkMetered,
                heartbeatLagMs = snapshot.health.heartbeatLagMs,
                lastError = snapshot.health.lastError,
                warnings = buildJsonArray {
                    snapshot.health.warnings.forEach { add(JsonPrimitive(it)) }
                },
            ),
        )
    }

    override suspend fun publishRuntimeIntelligence(update: RuntimeIntelligenceUpdate) {
        patchTable(
            table = "bot_state",
            filters = mapOf("bot_id" to "eq.${update.botId.value}"),
            body = buildJsonObject {
                put("active_device_id", update.deviceId.value)
                put("current_term", update.term.value)
                put("current_pair", update.currentPair?.value?.let(::JsonPrimitive) ?: JsonNull)
                put("operating_mode", update.operatingMode.name)
                put("edge_confidence", update.edgeConfidence.name)
                put("aggression_score", update.aggressionScore)
                put("risk_ladder_level", update.riskLadderLevel.name)
                put("profit_protection_status", update.profitProtectionStatus.name)
                put("market_regime", update.marketRegime.name)
                put("distrust_labels", buildJsonArray {
                    update.distrustLabels.forEach { add(JsonPrimitive(it.name)) }
                })
                put("active_candidate_pairs", buildJsonArray {
                    update.activeCandidatePairs.forEach { add(JsonPrimitive(it.value)) }
                })
                put("safe_mode_reason", update.safeModeReason?.let(::JsonPrimitive) ?: JsonNull)
                put("last_heartbeat_at", Clock.System.now().toString())
            },
        )
        insertIntoTable(
            table = "mode_metrics",
            body = buildJsonObject {
                put("bot_id", update.botId.value)
                put("device_id", update.deviceId.value)
                put("operating_mode", update.operatingMode.name)
                put("market_regime", update.marketRegime.name)
                put("edge_confidence", update.edgeConfidence.name)
                put("risk_ladder_level", update.riskLadderLevel.name)
                put("profit_protection_status", update.profitProtectionStatus.name)
                put("market_opportunity_score", update.marketOpportunityScore)
                put("bot_health_score", update.botHealthScore)
                put("performance_momentum_score", update.performanceMomentumScore)
                put("aggression_score", update.aggressionScore)
            },
        )
    }

    override suspend fun appendStrategyMetrics(botId: BotId, metrics: List<PairScore>) {
        metrics.take(5).forEach { metric ->
            insertIntoTable(
                table = "strategy_metrics",
                body = buildJsonObject {
                    put("bot_id", botId.value)
                    put("pair_id", metric.pairId.value)
                    put("liquidity_score", metric.liquidityScore)
                    put("spread_score", metric.spreadScore)
                    put("slippage_score", metric.slippageScore)
                    put("stability_score", metric.stabilityScore)
                    put("fee_adjusted_edge_score", metric.feeAdjustedEdgeScore)
                    put("volume_consistency_score", metric.volumeConsistencyScore)
                    put("volatility_quality_score", metric.volatilityQualityScore)
                    put("trend_quality_score", metric.trendQualityScore)
                    put("historical_expectancy_score", metric.historicalExpectancyScore)
                    put("recent_health_score", metric.recentHealthScore)
                    put("fill_quality_score", metric.fillQualityScore)
                    put("holdability_score", metric.holdabilityScore)
                    put("market_opportunity_score", metric.marketOpportunityScore)
                    put("ranking_score", metric.rankingScore)
                    put("pair_tier", metric.pairTier.name)
                    put("preferred_horizon", metric.preferredHorizon.name)
                    put("rejection_reasons", buildJsonArray {
                        metric.rejectionReasons.forEach { add(JsonPrimitive(it)) }
                    })
                },
            )
        }
    }

    override suspend fun upsertWeeklyLearningSummary(summary: WeeklyLearningSummary) {
        upsertTable(
            table = "weekly_learning_reviews",
            body = buildJsonObject {
                put("bot_id", summary.botId.value)
                put("period_start", summary.periodStart.toString())
                put("period_end", summary.periodEnd.toString())
                put("trade_count", summary.tradeCount)
                put("best_pairs", buildJsonArray {
                    summary.bestPairs.forEach { add(JsonPrimitive(it.value)) }
                })
                put("worst_pairs", buildJsonArray {
                    summary.worstPairs.forEach { add(JsonPrimitive(it.value)) }
                })
                put("best_setups", buildJsonArray {
                    summary.bestSetups.forEach { add(JsonPrimitive(it.name)) }
                })
                put("worst_setups", buildJsonArray {
                    summary.worstSetups.forEach { add(JsonPrimitive(it.name)) }
                })
                put("best_hours", buildJsonArray {
                    summary.bestHours.forEach { add(JsonPrimitive(it)) }
                })
                put("worst_hours", buildJsonArray {
                    summary.worstHours.forEach { add(JsonPrimitive(it)) }
                })
                put("false_entry_rate", summary.falseEntryRate)
                put("no_trade_quality_score", summary.noTradeQualityScore)
                put("avoided_bad_trades_indicator", summary.avoidedBadTradesIndicator)
                put("capital_utilization_pct", summary.capitalUtilizationPct)
                put("productive_utilization_pct", summary.productiveUtilizationPct)
                put("missed_opportunity_rate", summary.missedOpportunityRate)
                put("tactical_expectancy", summary.tacticalExpectancy)
                put("swing_expectancy", summary.swingExpectancy)
                put(
                    "adaptation_plan",
                    json.encodeToJsonElement(WeeklyAdaptationPlan.serializer(), summary.adaptationPlan),
                )
                put("notes", buildJsonArray {
                    summary.notes.forEach { add(JsonPrimitive(it)) }
                })
            },
            onConflict = "bot_id,period_start,period_end",
        )
    }

    override suspend fun fetchLatestWeeklyLearningSummary(botId: BotId): WeeklyLearningSummary? {
        return selectList<WeeklyLearningReviewRow>(
            table = "weekly_learning_reviews",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
            orderBy = "period_end.desc",
            limit = 1,
        ).firstOrNull()?.toWeeklyLearningSummary()
    }

    override suspend fun upsertUpdateRecommendation(recommendation: BotUpdateRecommendation) {
        upsertTable(
            table = "parameter_versions",
            body = buildJsonObject {
                put("bot_id", recommendation.botId.value)
                put("scope", recommendation.scope)
                put("version_tag", recommendation.versionTag)
                put("is_active", false)
                put("created_by_device_id", recommendation.createdByDeviceId?.value?.let(::JsonPrimitive) ?: JsonNull)
                put("parameters", buildJsonObject {
                    recommendation.evidence.forEach { (key, value) -> put(key, value) }
                })
                put("change_summary", buildJsonObject {
                    put("reason_code", recommendation.reasonCode)
                    put("title", recommendation.title)
                    put("summary", recommendation.summary)
                    put("source", recommendation.source)
                    put("severity", recommendation.severity.name)
                    put("confidence_score", recommendation.confidenceScore)
                    put("recommended_actions", buildJsonArray {
                        recommendation.recommendedActions.forEach { add(JsonPrimitive(it)) }
                    })
                })
                put("created_at", recommendation.createdAt.toString())
            },
            onConflict = "bot_id,scope,version_tag",
        )
    }

    override suspend fun fetchLatestUpdateRecommendations(botId: BotId, limit: Int): List<BotUpdateRecommendation> {
        return selectList<ParameterVersionRow>(
            table = "parameter_versions",
            filters = mapOf(
                "bot_id" to "eq.${botId.value}",
                "scope" to "eq.update_recommendation",
            ),
            orderBy = "created_at.desc",
            limit = limit,
        ).map(ParameterVersionRow::toBotUpdateRecommendation)
    }

    override suspend fun enqueueCommand(
        botId: BotId,
        createdBy: DeviceId,
        commandType: CommandType,
        targetDeviceId: DeviceId?,
        payloadJson: String?,
    ): CommandEnvelope {
        val row = rpc<RpcEnqueueCommandRequest, CommandRow>(
            function = "rpc_enqueue_command",
            body = RpcEnqueueCommandRequest(
                botId = botId.value,
                createdByDeviceId = createdBy.value,
                commandType = commandType.name,
                targetDeviceId = targetDeviceId?.value,
                payload = payloadJson?.let(json::parseToJsonElement),
            ),
        )
        return row.toCommandEnvelope()
    }

    override suspend fun updateCommandStatus(commandId: CommandId, status: CommandStatus) {
        patchTable(
            table = "command_queue",
            filters = mapOf("command_id" to "eq.${commandId.value}"),
            body = buildJsonObject {
                put("status", status.name)
            },
        )
    }

    override suspend fun reserveExecutionAction(
        botId: BotId,
        deviceId: DeviceId,
        term: Long,
        orderIntentId: String,
        actionType: String,
    ): ExecutionActionTicket {
        val row = rpc<RpcReserveExecutionActionRequest, ExecutionActionRow>(
            function = "rpc_reserve_execution_action",
            body = RpcReserveExecutionActionRequest(
                botId = botId.value,
                deviceId = deviceId.value,
                term = term,
                orderIntentId = orderIntentId,
                actionType = actionType,
            ),
        )
        return row.toExecutionActionTicket()
    }

    override suspend fun completeExecutionAction(actionId: ExecutionActionId, deviceId: DeviceId, status: String) {
        rpcUnit(
            function = "rpc_complete_execution_action",
            body = RpcCompleteExecutionActionRequest(
                actionId = actionId.value,
                deviceId = deviceId.value,
                status = status,
            ),
        )
    }

    override suspend fun markConflictSafeMode(botId: BotId, reason: String) {
        rpcUnit(
            function = "rpc_mark_conflict_and_safe_mode",
            body = RpcSafeModeRequest(botId = botId.value, reason = reason),
        )
    }

    override suspend fun appendLog(botId: BotId, record: AuditLogRecord) {
        insertIntoTable(
            table = "logs",
            body = buildJsonObject {
                put("bot_id", botId.value)
                put("device_id", record.deviceId?.value)
                put("term", record.term?.value)
                put("level", record.level.name)
                put("category", record.category)
                put("message", record.message)
                put("metadata", parseOrNull(record.metadataJson) ?: JsonNull)
                put("created_at", record.recordedAt.toString())
            },
        )
    }

    override suspend fun fetchRecentLogs(botId: BotId, limit: Int): List<AuditLogRecord> {
        return selectList<LogRow>(
            table = "logs",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
            orderBy = "created_at.desc",
            limit = limit,
        ).map(LogRow::toAuditLogRecord)
    }

    override suspend fun fetchRecentOrders(botId: BotId, limit: Int): List<OrderSnapshot> {
        return selectList<OrderRow>(
            table = "orders",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
            orderBy = "updated_at.desc",
            limit = limit,
        ).map(OrderRow::toOrderSnapshot)
    }

    override suspend fun fetchOpenPersistedOrders(botId: BotId): List<OrderSnapshot> {
        return selectList<OrderRow>(
            table = "orders",
            filters = mapOf(
                "bot_id" to "eq.${botId.value}",
                "status" to "in.(CREATED,SUBMITTING,OPEN,PARTIALLY_FILLED,CANCEL_REQUESTED,UNKNOWN)",
            ),
            orderBy = "updated_at.desc",
            limit = 100,
        ).map(OrderRow::toOrderSnapshot)
    }

    override suspend fun upsertOrderSnapshot(
        botId: BotId,
        term: Long,
        deviceId: DeviceId,
        order: OrderSnapshot,
    ) {
        upsertTable(
            table = "orders",
            body = buildJsonObject {
                put("bot_id", botId.value)
                put("term", term)
                put("device_id", deviceId.value)
                put("pair_id", order.pairId.value)
                put("exchange_order_id", order.orderId.value)
                put("client_order_id", order.clientOrderId.value)
                put("side", order.side.name)
                put("order_type", order.orderType.name)
                put("status", order.status.name)
                put("price", order.price.value)
                put("quantity", order.originalQuantity.value)
                put("executed_quantity", order.executedQuantity.value)
                put("remaining_quantity", order.remainingQuantity.value)
                put("fee_paid", order.feePaid.value)
                put("opened_at", order.createdAt.toString())
                put("updated_at", order.updatedAt.toString())
            },
            onConflict = "bot_id,client_order_id",
        )
    }

    override suspend fun upsertEncryptedCredentialBundle(bundle: EncryptedCredentialBundle) {
        val activeSession = authedSession()
        upsertTable(
            table = "api_credentials_encrypted",
            body = buildJsonObject {
                put("bot_id", bundle.botId.value)
                put("user_id", activeSession.userId)
                put("cipher_version", bundle.cipherVersion)
                put("kdf_algorithm", bundle.kdfAlgorithm)
                put("kdf_params", parseOrNull(bundle.kdfParamsJson) ?: JsonObject(emptyMap()))
                put("secret_bundle_ciphertext", bundle.secretBundleCiphertext)
                put("secret_bundle_nonce", bundle.secretBundleNonce)
                put("secret_bundle_salt", bundle.secretBundleSalt)
            },
            onConflict = "bot_id,user_id",
        )
    }

    override suspend fun fetchEncryptedCredentialBundle(botId: BotId): EncryptedCredentialBundle? {
        return selectSingle<EncryptedCredentialRow>(
            table = "api_credentials_encrypted",
            filters = mapOf("bot_id" to "eq.${botId.value}"),
        )?.toEncryptedCredentialBundle()
    }

    suspend fun fetchSnapshot(botId: BotId, deviceId: DeviceId, date: LocalDate): ControlPlaneSnapshot = coroutineScope {
        val botState = async { fetchBotState(botId) }
        val lease = async { fetchLease(botId) }
        val devices = async { fetchDevices(botId) }
        val dailyRisk = async { fetchDailyRisk(botId, date) }
        val commands = async { fetchPendingCommands(botId, deviceId, limit = 25) }
        val logs = async { fetchRecentLogs(botId, limit = 25) }
        val orders = async { fetchRecentOrders(botId, limit = 25) }

        ControlPlaneSnapshot(
            botState = botState.await(),
            lease = lease.await(),
            devices = devices.await(),
            dailyRisk = dailyRisk.await(),
            pendingCommands = commands.await(),
            recentLogs = logs.await(),
            recentOrders = orders.await(),
        )
    }

    private suspend inline fun <reified T> selectSingle(
        table: String,
        filters: Map<String, String>,
    ): T? = selectList<T>(table = table, filters = filters, limit = 1).firstOrNull()

    private suspend inline fun <reified T> selectList(
        table: String,
        filters: Map<String, String> = emptyMap(),
        orderBy: String? = null,
        limit: Int? = null,
    ): List<T> = authedRequest {
        client.get("${config.normalizedUrl}/rest/v1/$table") {
            applyPostgrestHeaders(it)
            url {
                parameters.append("select", "*")
                filters.forEach { (key, value) -> parameters.append(key, value) }
                orderBy?.let { parameters.append("order", it) }
                limit?.let { parameters.append("limit", it.toString()) }
            }
        }.body()
    }

    private suspend fun patchTable(
        table: String,
        filters: Map<String, String>,
        body: JsonObject,
    ) {
        authedRequest {
            client.patch("${config.normalizedUrl}/rest/v1/$table") {
                applyPostgrestHeaders(it)
                header("Prefer", "return=minimal")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                url {
                    filters.forEach { (key, value) -> parameters.append(key, value) }
                }
                setBody(body)
            }
            Unit
        }
    }

    private suspend fun insertIntoTable(table: String, body: JsonObject) {
        authedRequest {
            client.post("${config.normalizedUrl}/rest/v1/$table") {
                applyPostgrestHeaders(it)
                header("Prefer", "return=minimal")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
            Unit
        }
    }

    private suspend fun upsertTable(table: String, body: JsonObject, onConflict: String? = null) {
        authedRequest {
            client.post("${config.normalizedUrl}/rest/v1/$table") {
                applyPostgrestHeaders(it)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                url {
                    onConflict?.let { parameters.append("on_conflict", it) }
                }
                setBody(body)
            }
            Unit
        }
    }

    private suspend inline fun <reified Req : Any, reified Res> rpc(
        function: String,
        body: Req,
    ): Res = authedRequest {
        client.post("${config.normalizedUrl}/rest/v1/rpc/$function") {
            applyPostgrestHeaders(it)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(body)
        }.body()
    }

    private suspend fun rpcUnit(function: String, body: Any) {
        authedRequest {
            client.post("${config.normalizedUrl}/rest/v1/rpc/$function") {
                applyPostgrestHeaders(it)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(body)
            }
            Unit
        }
    }

    private suspend fun <T> authedRequest(block: suspend (Session) -> T): T {
        val active = authedSession()
        return try {
            block(active)
        } catch (error: ClientRequestException) {
            if (error.response.status != HttpStatusCode.Unauthorized) throw error
            session = null
            block(refreshSession())
        }
    }

    private suspend fun authedSession(): Session {
        val existing = session
        if (existing != null) return existing
        return refreshSession()
    }

    private suspend fun refreshSession(): Session = sessionMutex.withLock {
        val response = client.post("${config.normalizedUrl}/auth/v1/token?grant_type=password") {
            header("apikey", config.supabaseAnonKey)
            accept(ContentType.Application.Json)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                PasswordGrantRequest(
                    email = config.userEmail,
                    password = config.userPassword,
                ),
            )
        }.body<PasswordGrantResponse>()

        return Session(
            accessToken = response.accessToken,
            userId = response.user.id,
        ).also { session = it }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyPostgrestHeaders(session: Session) {
        header("apikey", config.supabaseAnonKey)
        header(HttpHeaders.Authorization, "Bearer ${session.accessToken}")
        accept(ContentType.Application.Json)
        header("Accept-Profile", "public")
        header("Content-Profile", "public")
    }

    private fun parseOrNull(value: String?): JsonElement? {
        if (value.isNullOrBlank()) return null
        return json.parseToJsonElement(value)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decimalJson(value: DecimalValue): JsonElement {
        return JsonUnquotedLiteral(value.value)
    }

    data class ControlPlaneSnapshot(
        val botState: BotStateSnapshot?,
        val lease: EngineLeaseSnapshot?,
        val devices: List<DeviceDescriptor>,
        val dailyRisk: DailyRiskSnapshot?,
        val pendingCommands: List<CommandEnvelope>,
        val recentLogs: List<AuditLogRecord>,
        val recentOrders: List<OrderSnapshot>,
    )

    private data class Session(
        val accessToken: String,
        val userId: String,
    )

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    constructor(config: ControlPlaneConfig) : this(
        config = config,
        client = createPlatformHttpClient(defaultJson),
        json = defaultJson,
    )
}

@Serializable
private data class PasswordGrantRequest(
    val email: String,
    val password: String,
)

@Serializable
private data class PasswordGrantResponse(
    @SerialName("access_token") val accessToken: String,
    val user: PasswordGrantUser,
)

@Serializable
private data class PasswordGrantUser(
    val id: String,
)

@Serializable
private data class RpcRegisterDeviceRequest(
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_display_name") val displayName: String,
    @SerialName("p_platform") val platform: String,
    @SerialName("p_role") val role: String,
)

@Serializable
private data class RpcAcquireLeaseRequest(
    @SerialName("p_bot_id") val botId: String,
    @SerialName("p_requester_device_id") val requesterDeviceId: String,
    @SerialName("p_ttl_seconds") val ttlSeconds: Int,
)

@Serializable
private data class RpcReleaseLeaseRequest(
    @SerialName("p_bot_id") val botId: String,
    @SerialName("p_requester_device_id") val requesterDeviceId: String,
    @SerialName("p_term") val term: Long,
    @SerialName("p_reason") val reason: String? = null,
)

@Serializable
private data class RpcHeartbeatRequest(
    @SerialName("p_bot_id") val botId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_term") val term: Long? = null,
    @SerialName("p_is_master") val isMaster: Boolean,
    @SerialName("p_desired_state") val desiredState: String,
    @SerialName("p_effective_state") val effectiveState: String,
    @SerialName("p_sync_health") val syncHealth: String,
    @SerialName("p_health_status") val healthStatus: String,
    @SerialName("p_websocket_healthy") val websocketHealthy: Boolean,
    @SerialName("p_exchange_reachable") val exchangeReachable: Boolean,
    @SerialName("p_supabase_reachable") val supabaseReachable: Boolean,
    @SerialName("p_battery_percent") val batteryPercent: Int? = null,
    @SerialName("p_charging") val charging: Boolean? = null,
    @SerialName("p_network_metered") val networkMetered: Boolean? = null,
    @SerialName("p_heartbeat_lag_ms") val heartbeatLagMs: Long? = null,
    @SerialName("p_last_error") val lastError: String? = null,
    @SerialName("p_warnings") val warnings: JsonArray = JsonArray(emptyList()),
)

@Serializable
private data class RpcRuntimeIntelligenceRequest(
    @SerialName("p_bot_id") val botId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_term") val term: Long,
    @SerialName("p_current_pair") val currentPair: String? = null,
    @SerialName("p_operating_mode") val operatingMode: String,
    @SerialName("p_edge_confidence") val edgeConfidence: String,
    @SerialName("p_aggression_score") val aggressionScore: Double,
    @SerialName("p_risk_ladder_level") val riskLadderLevel: String,
    @SerialName("p_profit_protection_status") val profitProtectionStatus: String,
    @SerialName("p_market_regime") val marketRegime: String,
    @SerialName("p_distrust_labels") val distrustLabels: JsonArray = JsonArray(emptyList()),
    @SerialName("p_active_candidate_pairs") val activeCandidatePairs: JsonArray = JsonArray(emptyList()),
    @SerialName("p_market_opportunity_score") val marketOpportunityScore: Double,
    @SerialName("p_bot_health_score") val botHealthScore: Double,
    @SerialName("p_performance_momentum_score") val performanceMomentumScore: Double,
    @SerialName("p_safe_mode_reason") val safeModeReason: String? = null,
)

@Serializable
private data class RpcEnqueueCommandRequest(
    @SerialName("p_bot_id") val botId: String,
    @SerialName("p_created_by_device_id") val createdByDeviceId: String,
    @SerialName("p_command_type") val commandType: String,
    @SerialName("p_target_device_id") val targetDeviceId: String? = null,
    @SerialName("p_payload") val payload: JsonElement? = null,
)

@Serializable
private data class RpcReserveExecutionActionRequest(
    @SerialName("p_bot_id") val botId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_term") val term: Long,
    @SerialName("p_order_intent_id") val orderIntentId: String,
    @SerialName("p_action_type") val actionType: String,
)

@Serializable
private data class RpcCompleteExecutionActionRequest(
    @SerialName("p_action_id") val actionId: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_status") val status: String,
)

@Serializable
private data class RpcSafeModeRequest(
    @SerialName("p_bot_id") val botId: String,
    @SerialName("p_reason") val reason: String,
)

@Serializable
private data class DeviceRow(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val platform: String,
    val role: String,
    @SerialName("is_revoked") val isRevoked: Boolean = false,
)

private fun DeviceRow.toDeviceDescriptor(): DeviceDescriptor = DeviceDescriptor(
    userId = UserId(userId),
    deviceId = DeviceId(deviceId),
    displayName = displayName,
    platform = DevicePlatform.valueOf(platform),
    role = DeviceRole.valueOf(role),
    isRevoked = isRevoked,
)

@Serializable
private data class BotStateRow(
    @SerialName("bot_id") val botId: String,
    @SerialName("desired_state") val desiredState: String,
    @SerialName("effective_state") val effectiveState: String,
    @SerialName("active_device_id") val activeDeviceId: String? = null,
    @SerialName("standby_device_id") val standbyDeviceId: String? = null,
    @SerialName("current_term") val currentTerm: Long,
    @SerialName("sync_health") val syncHealth: String,
    @SerialName("strategy_mode") val strategyMode: String,
    @SerialName("safe_mode_reason") val safeModeReason: String? = null,
    @SerialName("current_pair") val currentPair: String? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: Instant? = null,
    @SerialName("operating_mode") val operatingMode: String? = null,
    @SerialName("edge_confidence") val edgeConfidence: String? = null,
    @SerialName("aggression_score") val aggressionScore: Double? = null,
    @SerialName("risk_ladder_level") val riskLadderLevel: String? = null,
    @SerialName("profit_protection_status") val profitProtectionStatus: String? = null,
    @SerialName("market_regime") val marketRegime: String? = null,
    @SerialName("distrust_labels") val distrustLabels: JsonElement? = null,
    @SerialName("active_candidate_pairs") val activeCandidatePairs: JsonElement? = null,
)

private fun BotStateRow.toBotStateSnapshot(): BotStateSnapshot = BotStateSnapshot(
    botId = BotId(botId),
    desiredState = BotDesiredState.valueOf(desiredState),
    effectiveState = BotEffectiveState.valueOf(effectiveState),
    activeDeviceId = activeDeviceId?.let(::DeviceId),
    standbyDeviceId = standbyDeviceId?.let(::DeviceId),
    currentTerm = LeaseTerm(currentTerm),
    syncHealth = SyncHealth.valueOf(syncHealth),
    strategyMode = StrategyMode.valueOf(strategyMode),
    safeModeReason = safeModeReason,
    currentPair = currentPair?.let(::PairId),
    lastHeartbeatAt = lastHeartbeatAt,
    operatingMode = operatingMode?.let(BotMode::valueOf) ?: BotMode.GROWTH,
    edgeConfidence = edgeConfidence?.let(EdgeConfidence::valueOf) ?: EdgeConfidence.MEDIUM,
    aggressionScore = aggressionScore ?: 0.5,
    riskLadderLevel = riskLadderLevel?.let(RiskLadderLevel::valueOf) ?: RiskLadderLevel.NORMAL,
    profitProtectionStatus = profitProtectionStatus?.let(ProfitProtectionStatus::valueOf) ?: ProfitProtectionStatus.INACTIVE,
    marketRegime = marketRegime?.let(MarketRegime::valueOf) ?: MarketRegime.HIGH_VOLATILITY_UNCLEAR,
    distrustLabels = distrustLabels.decodeEnumListOrEmpty(),
    activeCandidatePairs = activeCandidatePairs.decodePairIdListOrEmpty(),
)

@Serializable
private data class LeaseRow(
    @SerialName("bot_id") val botId: String,
    @SerialName("holder_device_id") val holderDeviceId: String? = null,
    @SerialName("current_term") val currentTerm: Long,
    val state: String,
    @SerialName("expires_at") val expiresAt: Instant? = null,
    @SerialName("last_heartbeat_at") val lastHeartbeatAt: Instant? = null,
    @SerialName("conflict_detected") val conflictDetected: Boolean = false,
)

private fun LeaseRow.toLeaseSnapshot(): EngineLeaseSnapshot = EngineLeaseSnapshot(
    botId = BotId(botId),
    currentHolder = holderDeviceId?.let(::DeviceId),
    term = LeaseTerm(currentTerm),
    state = LeaseState.valueOf(state),
    expiresAt = expiresAt ?: Instant.DISTANT_PAST,
    lastHeartbeatAt = lastHeartbeatAt,
    conflictDetected = conflictDetected,
)

@Serializable
private data class DailyEquityRow(
    @SerialName("equity_date") val equityDate: LocalDate? = null,
    @SerialName("opening_equity_idr") val openingEquityIdr: JsonElement,
    @SerialName("current_equity_idr") val currentEquityIdr: JsonElement,
    @SerialName("realized_pnl_idr") val realizedPnlIdr: JsonElement,
    @SerialName("unrealized_pnl_idr") val unrealizedPnlIdr: JsonElement,
    @SerialName("drawdown_pct") val drawdownPct: Double,
    @SerialName("hard_daily_loss_limit_pct") val hardDailyLossLimitPct: Double,
    @SerialName("hard_stop_triggered") val hardStopTriggered: Boolean,
    @SerialName("rebase_pending") val rebasePending: Boolean,
    @SerialName("risk_ladder_level") val riskLadderLevel: String? = null,
    @SerialName("weekly_drawdown_pct") val weeklyDrawdownPct: Double? = null,
    @SerialName("loss_streak_count") val lossStreakCount: Int? = null,
    @SerialName("performance_decay_detected") val performanceDecayDetected: Boolean? = null,
    @SerialName("high_watermark_equity_idr") val highWatermarkEquityIdr: JsonElement? = null,
    @SerialName("giveback_pct") val givebackPct: Double? = null,
    @SerialName("profit_protection_status") val profitProtectionStatus: String? = null,
)

private fun DailyEquityRow.toDailyRiskSnapshot(): DailyRiskSnapshot = DailyRiskSnapshot(
    openingEquityIdr = openingEquityIdr.toDecimalValue(),
    currentEquityIdr = currentEquityIdr.toDecimalValue(),
    realizedPnlIdr = realizedPnlIdr.toDecimalValue(),
    unrealizedPnlIdr = unrealizedPnlIdr.toDecimalValue(),
    drawdownPct = drawdownPct,
    hardDailyLossLimitPct = hardDailyLossLimitPct,
    hardStopTriggered = hardStopTriggered,
    rebasePending = rebasePending,
    riskLadderLevel = riskLadderLevel?.let(RiskLadderLevel::valueOf) ?: RiskLadderLevel.NORMAL,
    weeklyDrawdownPct = weeklyDrawdownPct ?: 0.0,
    lossStreakCount = lossStreakCount ?: 0,
    performanceDecayDetected = performanceDecayDetected ?: false,
    highWatermarkEquityIdr = (highWatermarkEquityIdr ?: currentEquityIdr).toDecimalValue(),
    givebackPct = givebackPct ?: 0.0,
    profitProtectionStatus = profitProtectionStatus?.let(ProfitProtectionStatus::valueOf) ?: ProfitProtectionStatus.INACTIVE,
)

private fun DailyEquityRow.toDailyEquityHistoryPoint(): DailyEquityHistoryPoint? {
    val date = equityDate ?: return null
    return DailyEquityHistoryPoint(
        date = date,
        openingEquityIdr = openingEquityIdr.toDecimalValue(),
        currentEquityIdr = currentEquityIdr.toDecimalValue(),
        realizedPnlIdr = realizedPnlIdr.toDecimalValue(),
        unrealizedPnlIdr = unrealizedPnlIdr.toDecimalValue(),
    )
}

private fun JsonElement.toDecimalValue(): DecimalValue = when (this) {
    is JsonPrimitive -> DecimalValue(contentOrNull ?: toString())
    else -> DecimalValue.Zero
}

@Serializable
private data class CommandRow(
    @SerialName("command_id") val commandId: String,
    @SerialName("bot_id") val botId: String,
    @SerialName("created_by_device_id") val createdByDeviceId: String,
    @SerialName("target_device_id") val targetDeviceId: String? = null,
    @SerialName("command_type") val commandType: String,
    val status: String,
    val payload: JsonElement? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("expires_at") val expiresAt: Instant? = null,
)

private fun CommandRow.toCommandEnvelope(): CommandEnvelope = CommandEnvelope(
    commandId = CommandId(commandId),
    botId = BotId(botId),
    createdBy = DeviceId(createdByDeviceId),
    targetDeviceId = targetDeviceId?.let(::DeviceId),
    commandType = CommandType.valueOf(commandType),
    status = CommandStatus.valueOf(status),
    createdAt = createdAt,
    expiresAt = expiresAt,
    payloadJson = payload?.toString(),
)

@Serializable
private data class ExecutionActionRow(
    @SerialName("action_id") val actionId: String,
    @SerialName("bot_id") val botId: String,
    val term: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("order_intent_id") val orderIntentId: String,
    @SerialName("expires_at") val expiresAt: Instant,
)

private fun ExecutionActionRow.toExecutionActionTicket(): ExecutionActionTicket = ExecutionActionTicket(
    actionId = ExecutionActionId(actionId),
    botId = BotId(botId),
    term = LeaseTerm(term),
    deviceId = DeviceId(deviceId),
    orderIntentId = orderIntentId,
    expiresAt = expiresAt,
)

@Serializable
private data class LogRow(
    @SerialName("created_at") val createdAt: Instant,
    val level: String,
    val category: String,
    @SerialName("device_id") val deviceId: String? = null,
    val term: Long? = null,
    val message: String,
    val metadata: JsonElement? = null,
)

private fun LogRow.toAuditLogRecord(): AuditLogRecord = AuditLogRecord(
    recordedAt = createdAt,
    level = LogLevel.valueOf(level),
    category = category,
    deviceId = deviceId?.let(::DeviceId),
    term = term?.let(::LeaseTerm),
    message = message,
    metadataJson = metadata?.toString(),
)

@Serializable
private data class OrderRow(
    @SerialName("exchange_order_id") val exchangeOrderId: String? = null,
    @SerialName("client_order_id") val clientOrderId: String,
    @SerialName("pair_id") val pairId: String,
    val side: String,
    @SerialName("order_type") val orderType: String,
    val status: String,
    val price: JsonElement? = null,
    val quantity: JsonElement? = null,
    @SerialName("executed_quantity") val executedQuantity: JsonElement? = null,
    @SerialName("remaining_quantity") val remainingQuantity: JsonElement? = null,
    @SerialName("fee_paid") val feePaid: JsonElement? = null,
    @SerialName("opened_at") val openedAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)

private fun OrderRow.toOrderSnapshot(): OrderSnapshot = OrderSnapshot(
    orderId = OrderId(exchangeOrderId ?: clientOrderId),
    clientOrderId = com.kibot.shared.models.ClientOrderId(clientOrderId),
    pairId = PairId(pairId),
    side = OrderSide.valueOf(side),
    orderType = OrderType.valueOf(orderType),
    status = OrderStatus.valueOf(status),
    price = DecimalValue(price.toDecimalString()),
    originalQuantity = DecimalValue(quantity.toDecimalString()),
    executedQuantity = DecimalValue(executedQuantity.toDecimalString()),
    remainingQuantity = DecimalValue(remainingQuantity.toDecimalString()),
    feePaid = DecimalValue(feePaid.toDecimalString()),
    createdAt = openedAt,
    updatedAt = updatedAt,
)

private fun JsonElement?.toDecimalString(): String = when (this) {
    null -> "0"
    is JsonPrimitive -> content
    else -> toString()
}

@Serializable
private data class WeeklyLearningReviewRow(
    @SerialName("bot_id") val botId: String,
    @SerialName("period_start") val periodStart: LocalDate,
    @SerialName("period_end") val periodEnd: LocalDate,
    @SerialName("trade_count") val tradeCount: Int = 0,
    @SerialName("best_pairs") val bestPairs: JsonElement? = null,
    @SerialName("worst_pairs") val worstPairs: JsonElement? = null,
    @SerialName("best_setups") val bestSetups: JsonElement? = null,
    @SerialName("worst_setups") val worstSetups: JsonElement? = null,
    @SerialName("best_hours") val bestHours: JsonElement? = null,
    @SerialName("worst_hours") val worstHours: JsonElement? = null,
    @SerialName("false_entry_rate") val falseEntryRate: Double = 0.0,
    @SerialName("no_trade_quality_score") val noTradeQualityScore: Double = 0.0,
    @SerialName("avoided_bad_trades_indicator") val avoidedBadTradesIndicator: Double = 0.0,
    @SerialName("capital_utilization_pct") val capitalUtilizationPct: Double = 0.0,
    @SerialName("productive_utilization_pct") val productiveUtilizationPct: Double = 0.0,
    @SerialName("missed_opportunity_rate") val missedOpportunityRate: Double = 0.0,
    @SerialName("tactical_expectancy") val tacticalExpectancy: Double = 0.0,
    @SerialName("swing_expectancy") val swingExpectancy: Double = 0.0,
    @SerialName("adaptation_plan") val adaptationPlan: JsonElement? = null,
    val notes: JsonElement? = null,
)

private fun WeeklyLearningReviewRow.toWeeklyLearningSummary(): WeeklyLearningSummary = WeeklyLearningSummary(
    botId = BotId(botId),
    periodStart = periodStart,
    periodEnd = periodEnd,
    tradeCount = tradeCount,
    bestPairs = bestPairs.decodePairIdListOrEmpty(),
    worstPairs = worstPairs.decodePairIdListOrEmpty(),
    bestSetups = bestSetups.decodeSetupTypeListOrEmpty(),
    worstSetups = worstSetups.decodeSetupTypeListOrEmpty(),
    bestHours = bestHours.decodeIntListOrEmpty(),
    worstHours = worstHours.decodeIntListOrEmpty(),
    falseEntryRate = falseEntryRate,
    noTradeQualityScore = noTradeQualityScore,
    avoidedBadTradesIndicator = avoidedBadTradesIndicator,
    capitalUtilizationPct = capitalUtilizationPct,
    productiveUtilizationPct = productiveUtilizationPct,
    missedOpportunityRate = missedOpportunityRate,
    tacticalExpectancy = tacticalExpectancy,
    swingExpectancy = swingExpectancy,
    adaptationPlan = adaptationPlan.decodeWeeklyAdaptationPlanOrDefault(),
    notes = notes.decodeStringListOrEmpty(),
)

@Serializable
private data class ParameterVersionRow(
    @SerialName("bot_id") val botId: String,
    val scope: String,
    @SerialName("version_tag") val versionTag: String,
    @SerialName("created_by_device_id") val createdByDeviceId: String? = null,
    val parameters: JsonElement? = null,
    @SerialName("change_summary") val changeSummary: JsonElement? = null,
    @SerialName("created_at") val createdAt: Instant,
)

private fun ParameterVersionRow.toBotUpdateRecommendation(): BotUpdateRecommendation {
    val summaryObject = changeSummary as? JsonObject
    val parameterObject = parameters as? JsonObject
    val actions = (summaryObject?.get("recommended_actions") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    val evidence = parameterObject
        ?.mapNotNull { (key, value) ->
            val parsed = (value as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            key to parsed
        }
        ?.toMap()
        .orEmpty()

    return BotUpdateRecommendation(
        botId = BotId(botId),
        scope = scope,
        versionTag = versionTag,
        reasonCode = summaryObject.stringValue("reason_code").ifBlank { versionTag.substringAfter('-', versionTag) },
        severity = summaryObject.stringValue("severity").toAdvisorySeverity(),
        title = summaryObject.stringValue("title").ifBlank { "Update recommendation" },
        summary = summaryObject.stringValue("summary"),
        source = summaryObject.stringValue("source").ifBlank { "control_plane" },
        confidenceScore = summaryObject.doubleValue("confidence_score"),
        evidence = evidence,
        recommendedActions = actions,
        createdByDeviceId = createdByDeviceId?.let(::DeviceId),
        createdAt = createdAt,
    )
}

@Serializable
private data class EncryptedCredentialRow(
    @SerialName("bot_id") val botId: String,
    @SerialName("cipher_version") val cipherVersion: String,
    @SerialName("kdf_algorithm") val kdfAlgorithm: String,
    @SerialName("kdf_params") val kdfParams: JsonElement,
    @SerialName("secret_bundle_ciphertext") val secretBundleCiphertext: String,
    @SerialName("secret_bundle_nonce") val secretBundleNonce: String,
    @SerialName("secret_bundle_salt") val secretBundleSalt: String,
    @SerialName("updated_at") val updatedAt: Instant? = null,
)

private fun EncryptedCredentialRow.toEncryptedCredentialBundle(): EncryptedCredentialBundle = EncryptedCredentialBundle(
    botId = BotId(botId),
    cipherVersion = cipherVersion,
    kdfAlgorithm = kdfAlgorithm,
    kdfParamsJson = kdfParams.toString(),
    secretBundleCiphertext = secretBundleCiphertext,
    secretBundleNonce = secretBundleNonce,
    secretBundleSalt = secretBundleSalt,
    updatedAt = updatedAt,
)

private fun JsonObject?.stringValue(key: String): String {
    return ((this?.get(key) as? JsonPrimitive)?.contentOrNull).orEmpty()
}

private fun JsonObject?.doubleValue(key: String): Double {
    return ((this?.get(key) as? JsonPrimitive)?.doubleOrNull) ?: 0.0
}

private fun String.toAdvisorySeverity(): AdvisorySeverity = runCatching {
    AdvisorySeverity.valueOf(this)
}.getOrDefault(AdvisorySeverity.MEDIUM)

private fun JsonElement?.decodeEnumListOrEmpty(): List<DistrustLabel> {
    if (this == null || this is JsonNull) return emptyList()
    return runCatching {
        controlPlaneDecodeJson.decodeFromJsonElement<List<String>>(this).map(DistrustLabel::valueOf)
    }.getOrElse { emptyList() }
}

private fun JsonElement?.decodePairIdListOrEmpty(): List<PairId> {
    if (this == null || this is JsonNull) return emptyList()
    return runCatching {
        controlPlaneDecodeJson.decodeFromJsonElement<List<String>>(this).map(::PairId)
    }.getOrElse { emptyList() }
}

private fun JsonElement?.decodeSetupTypeListOrEmpty(): List<SetupType> {
    if (this == null || this is JsonNull) return emptyList()
    return runCatching {
        controlPlaneDecodeJson.decodeFromJsonElement<List<String>>(this).map(SetupType::valueOf)
    }.getOrElse { emptyList() }
}

private fun JsonElement?.decodeIntListOrEmpty(): List<Int> {
    if (this == null || this is JsonNull) return emptyList()
    return runCatching {
        controlPlaneDecodeJson.decodeFromJsonElement<List<Int>>(this)
    }.getOrElse { emptyList() }
}

private fun JsonElement?.decodeStringListOrEmpty(): List<String> {
    if (this == null || this is JsonNull) return emptyList()
    return runCatching {
        controlPlaneDecodeJson.decodeFromJsonElement<List<String>>(this)
    }.getOrElse { emptyList() }
}

private fun JsonElement?.decodeWeeklyAdaptationPlanOrDefault(): WeeklyAdaptationPlan {
    if (this == null || this is JsonNull) return WeeklyAdaptationPlan()
    return runCatching {
        controlPlaneDecodeJson.decodeFromJsonElement(WeeklyAdaptationPlan.serializer(), this)
    }.getOrElse { WeeklyAdaptationPlan() }
}

private val controlPlaneDecodeJson = Json {
    ignoreUnknownKeys = true
}
