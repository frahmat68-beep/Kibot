package com.kibot.indodax

import com.kibot.core.ExchangeGateway
import com.kibot.shared.models.BalanceSnapshot
import com.kibot.shared.models.ClientOrderId
import com.kibot.shared.models.DecimalValue
import com.kibot.shared.models.ExecutionPlan
import com.kibot.shared.models.FillId
import com.kibot.shared.models.FillSnapshot
import com.kibot.shared.models.MarketQuote
import com.kibot.shared.models.OrderId
import com.kibot.shared.models.OrderSide
import com.kibot.shared.models.OrderSnapshot
import com.kibot.shared.models.OrderStatus
import com.kibot.shared.models.OrderType
import com.kibot.shared.models.PairId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.atomicfu.atomic
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IndodaxGateway internal constructor(
    private val config: IndodaxClientConfig,
    private val credentials: IndodaxCredentials,
    private val client: HttpClient,
    private val json: Json,
) : ExchangeGateway {
    private val privateRequestFactory = IndodaxPrivateRequestFactory(credentials)
    private val nonceCounter = atomic(Clock.System.now().toEpochMilliseconds())

    override suspend fun ping(): Boolean {
        return runCatching {
            client.get("${config.publicBaseUrl}/summaries").status.isSuccess()
        }.getOrDefault(false)
    }

    override suspend fun fetchMarketQuotes(): List<MarketQuote> {
        val response = client.get("${config.publicBaseUrl}/summaries").body<SummariesResponse>()
        return response.tickers.entries.mapNotNull { (pairKey, ticker) ->
            val pairId = PairId(pairKey)
            val pairParts = pairId.assets()
            val bestBid = DecimalValue(ticker.buy)
            val bestAsk = DecimalValue(ticker.sell)
            val bid = bestBid.toDoubleOrZero()
            val ask = bestAsk.toDoubleOrZero()
            val mid = ((bid + ask) / 2.0).takeIf { it > 0.0 } ?: return@mapNotNull null
            val spreadPct = ((ask - bid) / mid * 100.0).coerceAtLeast(0.0)
            val lastPrice = DecimalValue(ticker.last ?: ticker.sell)
            val last = lastPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: mid
            val high = DecimalValue(ticker.high ?: ticker.sell).toDoubleOrZero().takeIf { it > 0.0 } ?: last
            val low = DecimalValue(ticker.low ?: ticker.buy).toDoubleOrZero().takeIf { it > 0.0 } ?: last
            val quoteVolume = DecimalValue(
                ticker.volumeFor(pairParts.quoteAsset) ?: ticker.firstVolumeValue() ?: "0",
            )
            val baseVolume = DecimalValue(
                ticker.volumeFor(pairParts.baseAsset) ?: "0",
            )
            val volumeIdr = quoteVolume.toDoubleOrZero()
            val volumeFactor = (volumeIdr / 50_000_000.0).coerceIn(0.0, 1.0)
            val stability = (1.0 - (spreadPct / 1.5)).coerceIn(0.0, 1.0) * 0.5 + (volumeFactor * 0.5)
            val price24h = response.prices24h[pairId.value.replace("_", "")]?.toDoubleOrZero()?.takeIf { it > 0.0 }
            val price7d = response.prices7d[pairId.value.replace("_", "")]?.toDoubleOrZero()?.takeIf { it > 0.0 }
            val shortTermReturnPct = percentChange(last, price24h ?: mid)
            val mediumTermReturnPct = percentChange(last, price7d ?: price24h ?: mid)
            val realizedVolatilityPct = if (low > 0.0) (((high - low) / low) * 100.0).coerceAtLeast(0.0) else 0.0
            val slippagePct = (spreadPct * 0.65).coerceAtLeast(0.05)
            val tradeCount24h = estimateTradeCount(volumeIdr, mid)
            val top5DepthIdr = estimateTopDepthIdr(
                volumeIdr = volumeIdr,
                stabilityScore = stability,
                spreadPct = spreadPct,
            )
            val trendQualityScore = deriveTrendQualityScore(shortTermReturnPct, mediumTermReturnPct)
            val volatilityQualityScore = deriveVolatilityQualityScore(realizedVolatilityPct, spreadPct)
            val fillQualityScore = weightedScore(
                stability to 0.42,
                volumeFactor to 0.32,
                (1.0 - (slippagePct / 1.2)).coerceIn(0.0, 1.0) to 0.26,
            )
            val historicalExpectancyScore = weightedScore(
                trendQualityScore to 0.38,
                volatilityQualityScore to 0.22,
                fillQualityScore to 0.22,
                volumeFactor to 0.18,
            )
            val holdabilityScore = weightedScore(
                trendQualityScore to 0.45,
                stability to 0.25,
                volumeFactor to 0.15,
                (1.0 - (realizedVolatilityPct / 22.0)).coerceIn(0.0, 1.0) to 0.15,
            )

            MarketQuote(
                pairId = pairId,
                bestBid = bestBid,
                bestAsk = bestAsk,
                midPrice = DecimalValue.fromDouble(mid),
                spreadPct = spreadPct,
                quoteVolume24h = quoteVolume,
                baseVolume24h = baseVolume,
                estimatedSlippagePct = slippagePct,
                orderBookStabilityScore = stability.coerceIn(0.0, 1.0),
                tradeCount24h = tradeCount24h,
                bidDepthTop5Idr = DecimalValue.fromDouble(top5DepthIdr),
                askDepthTop5Idr = DecimalValue.fromDouble(top5DepthIdr * 0.96),
                shortTermReturnPct = shortTermReturnPct,
                mediumTermReturnPct = mediumTermReturnPct,
                realizedVolatilityPct = realizedVolatilityPct,
                recentTradeActivityScore = tradeActivityScore(tradeCount24h, volumeFactor),
                volatilityQualityScore = volatilityQualityScore,
                trendQualityScore = trendQualityScore,
                historicalExpectancyScore = historicalExpectancyScore,
                fillQualityScore = fillQualityScore,
                holdabilityScore = holdabilityScore,
                capturedAt = ticker.serverTime?.let(Instant::fromEpochSeconds) ?: Clock.System.now(),
            )
        }
    }

    override suspend fun fetchBalances(): List<BalanceSnapshot> {
        val response = submitPrivate<PrivateBalanceResponse>("getInfo")
        return response.result.balance.entries
            .sortedBy { it.key }
            .mapNotNull { (asset, freeValue) ->
                val free = DecimalValue(normalizeNumeric(freeValue))
                val locked = DecimalValue(
                    normalizeNumeric(response.result.balanceHold[asset] ?: "0"),
                )
                if (free.toDoubleOrZero() <= 0.0 && locked.toDoubleOrZero() <= 0.0) {
                    return@mapNotNull null
                }
                BalanceSnapshot(
                    asset = asset,
                    free = free,
                    locked = locked,
                    totalValueInIdr = if (asset.equals("idr", ignoreCase = true)) {
                        DecimalValue.fromDouble(free.toDoubleOrZero() + locked.toDoubleOrZero())
                    } else {
                        null
                    },
                )
            }
    }

    override suspend fun fetchOpenOrders(): List<OrderSnapshot> {
        val response = submitPrivate<PrivateEnvelope<OpenOrdersPayload>>("openOrders")
        val rawOrders = when (val orders = response.result.orders) {
            is JsonArray -> orders.toOrderRows()
            is JsonObject -> orders.entries.flatMap { (pair, value) -> value.jsonArray.toOrderRows(pair) }
            else -> emptyList()
        }
        return rawOrders.map { row -> row.toOpenOrderSnapshot() }
    }

    override suspend fun fetchRecentFills(pairId: PairId?, limit: Int): List<FillSnapshot> {
        if (pairId == null) return emptyList()
        val response = client.get("${config.tradeApiV2BaseUrl}/api/v2/myTrades") {
            val query = signedGetQuery(
                params = linkedMapOf(
                    "symbol" to pairId.toTradeApiV2Symbol(),
                    "limit" to limit.coerceIn(10, 1000).toString(),
                    "timestamp" to Clock.System.now().toEpochMilliseconds().toString(),
                    "recvWindow" to config.receiveWindowMillis.toString(),
                ),
            )
            url {
                query.parameters.forEach { (key, value) -> parameter(key, value) }
            }
            header("X-APIKEY", credentials.apiKey)
            header("Sign", query.signature)
        }.body<TradeHistoryV2Response>()

        return response.data.map { trade ->
            FillSnapshot(
                fillId = FillId(trade.tradeId),
                orderId = OrderId(trade.orderId),
                pairId = pairId,
                side = if (trade.isBuyer) OrderSide.BUY else OrderSide.SELL,
                quantity = DecimalValue(trade.qty),
                price = DecimalValue(trade.price),
                fee = DecimalValue(trade.commission),
                feeAsset = trade.commissionAsset,
                executedAt = Instant.fromEpochMilliseconds(trade.time),
            )
        }
    }

    override suspend fun placeOrder(plan: ExecutionPlan, clientOrderId: ClientOrderId): OrderSnapshot {
        val pairParts = plan.signal.pairId.assets()
        val params = linkedMapOf(
            "pair" to plan.signal.pairId.value,
            "type" to plan.side.apiValue(),
            "client_order_id" to clientOrderId.value,
            "order_type" to plan.orderType.apiValue(),
        )

        when {
            plan.side == OrderSide.BUY && plan.orderType == OrderType.MARKET -> {
                require(pairParts.quoteAsset == "idr") {
                    "Market buy is only supported safely for *_idr pairs in the current adapter."
                }
                params["idr"] = plan.quoteBudget?.value ?: error("Market buy requires quoteBudget.")
            }

            plan.side == OrderSide.BUY -> {
                params[pairParts.baseAsset] = plan.quantity.value
                params["price"] = plan.limitPrice?.value ?: error("Limit buy requires limitPrice.")
            }

            else -> {
                params[pairParts.baseAsset] = plan.quantity.value
                params["price"] = plan.limitPrice?.value ?: plan.signal.entryPrice?.value ?: "0"
            }
        }

        submitPrivate<TradeResponse>("trade", params)
        return fetchOrderByClientOrderId(clientOrderId, plan.signal.pairId)
    }

    override suspend fun cancelOrder(clientOrderId: ClientOrderId): Boolean {
        return runCatching {
            submitPrivate<CancelByClientOrderIdResponse>(
                method = "cancelByClientOrderId",
                params = mapOf("client_order_id" to clientOrderId.value),
            )
            true
        }.getOrDefault(false)
    }

    suspend fun fetchOrderByClientOrderId(clientOrderId: ClientOrderId, pairId: PairId): OrderSnapshot {
        val response = submitPrivate<GetOrderByClientOrderIdResponse>(
            method = "getOrderByClientOrderId",
            params = mapOf("client_order_id" to clientOrderId.value),
        )
        return response.result.order.toOrderSnapshot(pairId)
    }

    private suspend inline fun <reified T> submitPrivate(
        method: String,
        params: Map<String, String> = emptyMap(),
    ): T {
        val signed = privateRequestFactory.build(
            method = method,
            nonce = nextNonce(),
            params = params,
        )
        return client.submitForm(
            url = config.privateBaseUrl,
            formParameters = Parameters.build {
                signed.body.forEach { (key, value) -> append(key, value) }
            },
        ) {
            signed.headers.forEach { (key, value) -> header(key, value) }
        }.body()
    }

    private fun nextNonce(): Long {
        while (true) {
            val current = nonceCounter.value
            val candidate = maxOf(current + 1, Clock.System.now().toEpochMilliseconds())
            if (nonceCounter.compareAndSet(current, candidate)) {
                return candidate
            }
        }
    }

    private fun signedGetQuery(params: LinkedHashMap<String, String>): SignedQuery {
        val payload = params.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        return SignedQuery(
            parameters = params,
            signature = HmacSha512Signer.sign(credentials.apiSecret, payload),
        )
    }

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    constructor(
        config: IndodaxClientConfig,
        credentials: IndodaxCredentials,
    ) : this(
        config = config,
        credentials = credentials,
        client = createPlatformHttpClient(defaultJson),
        json = defaultJson,
    )
}

internal data class SignedQuery(
    val parameters: Map<String, String>,
    val signature: String,
)

@Serializable
private data class SummariesResponse(
    val tickers: Map<String, SummaryTicker>,
    @SerialName("prices_24h") val prices24h: Map<String, String> = emptyMap(),
    @SerialName("prices_7d") val prices7d: Map<String, String> = emptyMap(),
)

@Serializable
private data class SummaryTicker(
    val high: String? = null,
    val low: String? = null,
    val last: String? = null,
    val buy: String,
    val sell: String,
    @SerialName("server_time") val serverTime: Long? = null,
    @SerialName("vol_btc") val volBtc: String? = null,
    @SerialName("vol_idr") val volIdr: String? = null,
    @SerialName("vol_usdt") val volUsdt: String? = null,
    val name: String? = null,
)

private fun SummaryTicker.volumeFor(asset: String): String? = when (asset.lowercase()) {
    "btc" -> volBtc
    "idr" -> volIdr
    "usdt" -> volUsdt
    else -> null
}

private fun SummaryTicker.firstVolumeValue(): String? = listOfNotNull(volIdr, volUsdt, volBtc).firstOrNull()

private fun percentChange(current: Double, reference: Double): Double {
    if (reference <= 0.0) return 0.0
    return ((current - reference) / reference) * 100.0
}

private fun estimateTradeCount(volumeIdr: Double, midPrice: Double): Int {
    if (volumeIdr <= 0.0 || midPrice <= 0.0) return 0
    val estimatedTrades = volumeIdr / maxOf(midPrice * 0.18, 25_000.0)
    return estimatedTrades.toInt().coerceIn(0, 20_000)
}

private fun estimateTopDepthIdr(
    volumeIdr: Double,
    stabilityScore: Double,
    spreadPct: Double,
): Double {
    val depthFactor = (0.0035 + (stabilityScore * 0.0085) - (spreadPct * 0.0015)).coerceIn(0.001, 0.018)
    return (volumeIdr * depthFactor).coerceAtLeast(0.0)
}

private fun deriveTrendQualityScore(shortTermReturnPct: Double, mediumTermReturnPct: Double): Double {
    val shortScore = ((shortTermReturnPct + 3.0) / 9.0).coerceIn(0.0, 1.0)
    val mediumScore = ((mediumTermReturnPct + 6.0) / 18.0).coerceIn(0.0, 1.0)
    return weightedScore(shortScore to 0.42, mediumScore to 0.58)
}

private fun deriveVolatilityQualityScore(realizedVolatilityPct: Double, spreadPct: Double): Double {
    val volatilityBand = when {
        realizedVolatilityPct in 1.5..18.0 -> 1.0
        realizedVolatilityPct < 1.5 -> (realizedVolatilityPct / 1.5).coerceIn(0.0, 1.0) * 0.85
        else -> (1.0 - ((realizedVolatilityPct - 18.0) / 24.0)).coerceIn(0.0, 1.0)
    }
    val spreadPenalty = (1.0 - (spreadPct / 1.5)).coerceIn(0.0, 1.0)
    return weightedScore(volatilityBand to 0.7, spreadPenalty to 0.3)
}

private fun tradeActivityScore(tradeCount24h: Int, volumeFactor: Double): Double {
    val tradeScore = (tradeCount24h.toDouble() / 450.0).coerceIn(0.0, 1.0)
    return weightedScore(tradeScore to 0.65, volumeFactor to 0.35)
}

private fun weightedScore(vararg parts: Pair<Double, Double>): Double {
    val weightTotal = parts.sumOf { it.second }.takeIf { it > 0.0 } ?: return 0.0
    return (parts.sumOf { it.first * it.second } / weightTotal).coerceIn(0.0, 1.0)
}

@Serializable
private data class PrivateEnvelope<T>(
    @SerialName("return") val result: T,
)

@Serializable
private data class PrivateBalanceResponse(
    @SerialName("return") val result: BalancePayload,
)

@Serializable
private data class BalancePayload(
    val balance: Map<String, JsonElement>,
    @SerialName("balance_hold") val balanceHold: Map<String, JsonElement>,
)

@Serializable
private data class OpenOrdersPayload(
    val orders: JsonElement,
)

@Serializable
private data class TradeHistoryV2Response(
    val data: List<TradeFillRow>,
)

@Serializable
private data class TradeFillRow(
    @SerialName("tradeId") val tradeId: String,
    @SerialName("orderId") val orderId: String,
    val price: String,
    val qty: String,
    val commission: String,
    @SerialName("commissionAsset") val commissionAsset: String,
    @SerialName("isBuyer") val isBuyer: Boolean,
    val time: Long,
)

@Serializable
private data class TradeResponse(
    @SerialName("return") val result: TradeResult,
)

@Serializable
private data class TradeResult(
    @SerialName("order_id") val orderId: Long? = null,
    @SerialName("client_order_id") val clientOrderId: String? = null,
)

@Serializable
private data class CancelByClientOrderIdResponse(
    @SerialName("return") val result: CancelResult,
)

@Serializable
private data class CancelResult(
    @SerialName("order_id") val orderId: Long,
    @SerialName("client_order_id") val clientOrderId: String,
)

@Serializable
private data class GetOrderByClientOrderIdResponse(
    @SerialName("return") val result: OrderPayload,
)

@Serializable
private data class OrderPayload(
    val order: JsonObject,
)

private fun JsonArray.toOrderRows(pairOverride: String? = null): List<JsonObject> = mapNotNull { element ->
    val row = element as? JsonObject ?: return@mapNotNull null
    if (pairOverride == null || row.containsKey("pair")) {
        row
    } else {
        JsonObject(row + ("pair" to JsonPrimitive(pairOverride)))
    }
}

private fun JsonObject.toOpenOrderSnapshot(): OrderSnapshot {
    val orderId = string("order_id") ?: error("Missing order_id in open order row.")
    val pairId = PairId(string("pair") ?: guessPairFromOrderId(orderId))
    val pairParts = pairId.assets()
    val originalQuantity = quantityFor(pairParts)
    val remainingQuantity = remainingFor(pairParts)
    return OrderSnapshot(
        orderId = OrderId(orderId),
        clientOrderId = ClientOrderId(string("client_order_id") ?: orderId),
        pairId = pairId,
        side = (string("type") ?: "buy").toOrderSide(),
        orderType = (string("order_type") ?: "limit").toOrderType(),
        status = if (remainingQuantity.toDoubleOrZero() < originalQuantity.toDoubleOrZero()) {
            OrderStatus.PARTIALLY_FILLED
        } else {
            OrderStatus.OPEN
        },
        price = DecimalValue(normalizeNumeric(string("price"))),
        originalQuantity = originalQuantity,
        executedQuantity = DecimalValue.fromDouble(
            (originalQuantity.toDoubleOrZero() - remainingQuantity.toDoubleOrZero()).coerceAtLeast(0.0),
        ),
        remainingQuantity = remainingQuantity,
        createdAt = (string("submit_time") ?: "0").toEpochSecondsInstant(),
        updatedAt = Clock.System.now(),
    )
}

private fun JsonObject.toOrderSnapshot(pairId: PairId): OrderSnapshot {
    val orderId = string("order_id") ?: error("Missing order_id in private order row.")
    val pairParts = pairId.assets()
    val originalQuantity = quantityFor(pairParts)
    val remainingQuantity = remainingFor(pairParts)
    return OrderSnapshot(
        orderId = OrderId(orderId),
        clientOrderId = ClientOrderId(string("client_order_id") ?: orderId),
        pairId = pairId,
        side = (string("type") ?: "buy").toOrderSide(),
        orderType = OrderType.LIMIT,
        status = string("status").toOrderStatus(
            originalQuantity = originalQuantity.toDoubleOrZero(),
            remainingQuantity = remainingQuantity.toDoubleOrZero(),
        ),
        price = DecimalValue(normalizeNumeric(string("price"))),
        originalQuantity = originalQuantity,
        executedQuantity = DecimalValue.fromDouble(
            (originalQuantity.toDoubleOrZero() - remainingQuantity.toDoubleOrZero()).coerceAtLeast(0.0),
        ),
        remainingQuantity = remainingQuantity,
        createdAt = (string("submit_time") ?: "0").toEpochSecondsInstant(),
        updatedAt = (string("finish_time") ?: string("submit_time") ?: "0").toEpochSecondsInstant(),
    )
}

private fun JsonObject.quantityFor(parts: PairParts): DecimalValue = when ((string("type") ?: "").lowercase()) {
    "buy" -> DecimalValue(normalizeNumeric(orderForQuoteOrBase(parts)))
    else -> DecimalValue(normalizeNumeric(orderForQuoteOrBase(parts)))
}

private fun JsonObject.remainingFor(parts: PairParts): DecimalValue = when ((string("type") ?: "").lowercase()) {
    "buy" -> DecimalValue(normalizeNumeric(remainForQuoteOrBase(parts)))
    else -> DecimalValue(normalizeNumeric(remainForQuoteOrBase(parts)))
}

private fun JsonObject.orderForQuoteOrBase(parts: PairParts): String? {
    return orderForAsset(parts.baseAsset) ?: orderForAsset(parts.quoteAsset)
}

private fun JsonObject.remainForQuoteOrBase(parts: PairParts): String? {
    return remainForAsset(parts.baseAsset) ?: remainForAsset(parts.quoteAsset)
}

private fun JsonObject.orderForAsset(asset: String): String? {
    return string("order_${asset.lowercase()}") ?: string("order_rp")
}

private fun JsonObject.remainForAsset(asset: String): String? {
    return string("remain_${asset.lowercase()}") ?: string("remain_rp")
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private data class PairParts(
    val baseAsset: String,
    val quoteAsset: String,
)

private fun PairId.assets(): PairParts {
    val normalized = value.lowercase()
    val parts = normalized.split("_")
    if (parts.size == 2) {
        return PairParts(parts[0], parts[1])
    }
    val quote = listOf("idr", "usdt", "btc", "eth").firstOrNull { normalized.endsWith(it) }
        ?: error("Unsupported pair format: $value")
    val base = normalized.removeSuffix(quote)
    return PairParts(base, quote)
}

private fun PairId.toTradeApiV2Symbol(): String = value.replace("_", "")

private fun String.toOrderSide(): OrderSide = if (equals("buy", ignoreCase = true)) OrderSide.BUY else OrderSide.SELL

private fun String.toOrderType(): OrderType = if (equals("market", ignoreCase = true)) OrderType.MARKET else OrderType.LIMIT

private fun String?.toOrderStatus(originalQuantity: Double, remainingQuantity: Double): OrderStatus = when (this?.lowercase()) {
    "filled" -> OrderStatus.FILLED
    "cancelled", "canceled" -> OrderStatus.CANCELED
    "open" -> if (remainingQuantity < originalQuantity) OrderStatus.PARTIALLY_FILLED else OrderStatus.OPEN
    "rejected" -> OrderStatus.REJECTED
    else -> if (remainingQuantity < originalQuantity && remainingQuantity > 0.0) {
        OrderStatus.PARTIALLY_FILLED
    } else {
        OrderStatus.UNKNOWN
    }
}

private fun OrderSide.apiValue(): String = if (this == OrderSide.BUY) "buy" else "sell"

private fun OrderType.apiValue(): String = if (this == OrderType.MARKET) "market" else "limit"

private fun normalizeNumeric(value: Any?): String = when (value) {
    null -> "0"
    is JsonPrimitive -> value.content
    else -> value.toString()
}

private fun String.toEpochSecondsInstant(): Instant = Instant.fromEpochSeconds(toLong())

private fun guessPairFromOrderId(orderId: String): String {
    val prefix = orderId.substringBefore("-").lowercase()
    val match = Regex("([a-z0-9]+)(idr|usdt|btc|eth)$").matchEntire(prefix)
        ?: return prefix
    return "${match.groupValues[1]}_${match.groupValues[2]}"
}
