package com.kicryp.binance

import com.kicryp.core.ExchangeGateway
import com.kicryp.core.MarketBuyImpactEstimate
import com.kicryp.core.ExchangeOrderVisibilityException
import com.kicryp.core.ExchangeRejectedException
import com.kicryp.core.data.CoinUniverse
import com.kicryp.shared.models.BalanceSnapshot
import com.kicryp.shared.models.ClientOrderId
import com.kicryp.shared.models.DecimalValue
import com.kicryp.shared.models.ExecutionPlan
import com.kicryp.shared.models.FillId
import com.kicryp.shared.models.FillSnapshot
import com.kicryp.shared.models.MarketQuote
import com.kicryp.shared.models.OrderId
import com.kicryp.shared.models.OrderSide
import com.kicryp.shared.models.OrderSnapshot
import com.kicryp.shared.models.OrderStatus
import com.kicryp.shared.models.OrderType
import com.kicryp.shared.models.PairId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.absoluteValue
import kotlin.math.max
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class BinanceGateway internal constructor(
    private val config: BinanceClientConfig,
    private val credentials: BinanceCredentials,
    private val client: HttpClient,
    private val json: Json,
) : ExchangeGateway {
    private data class CachedMarketQuotes(
        val quotes: List<MarketQuote>,
        val fetchedAtEpochMs: Long,
    ) {
        fun isFresh(nowEpochMs: Long, ttlMs: Long): Boolean = nowEpochMs - fetchedAtEpochMs < ttlMs
    }

    private val privateApiEnabled =
        credentials.apiKey.isNotBlank() &&
            credentials.apiSecret.isNotBlank()
    private val marketDataCacheTtlMs = 30_000L
    private val marketDataTimeoutMs = 5_000L
    private val selectiveTickerSymbolBatches = buildTicker24hSymbolBatches()
    @Volatile private var cachedMarketQuotes: CachedMarketQuotes? = null

    override suspend fun ping(): Boolean {
        return runCatching {
            withUrlFailover(config.publicRestUrls("ping")) { url ->
                client.get(url).status.isSuccess()
            }
        }.getOrDefault(false)
    }

    override suspend fun fetchMarketQuotes(): List<MarketQuote> {
        val nowEpochMs = Clock.System.now().toEpochMilliseconds()
        cachedMarketQuotes?.takeIf { it.isFresh(nowEpochMs, marketDataCacheTtlMs) }?.let { cached ->
            return cached.quotes
        }

        val response = runCatching {
            val aggregatedRows = mutableListOf<Ticker24hRow>()
            withUrlFailover(config.publicRestUrls("ticker/24hr")) { url ->
                selectiveTickerSymbolBatches.forEach { batchSymbolsJson ->
                    val batchRows = client.get(url) {
                        timeout {
                            requestTimeoutMillis = marketDataTimeoutMs
                            socketTimeoutMillis = marketDataTimeoutMs
                        }
                        parameter("symbols", batchSymbolsJson)
                    }.body<List<Ticker24hRow>>()
                    aggregatedRows += batchRows
                }
                aggregatedRows
            }
        }.getOrElse { error ->
            cachedMarketQuotes?.let { cached -> return cached.quotes }
            throw error
        }
        val primaryQuote = config.primaryQuoteAsset.lowercase()
        val quotes = response.mapNotNull { ticker ->
            if (ticker.symbol.length <= primaryQuote.length) return@mapNotNull null
            if (!ticker.symbol.lowercase().endsWith(primaryQuote)) return@mapNotNull null
            val pairId = ticker.symbol.toPairId(primaryQuote)
            val bid = ticker.bidPrice.toDoubleOrZero()
            val ask = ticker.askPrice.toDoubleOrZero()
            val last = ticker.lastPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: return@mapNotNull null
            val effectiveBid = if (bid > 0.0) bid else last
            val effectiveAsk = if (ask > 0.0) ask else last
            val mid = ((effectiveBid + effectiveAsk) / 2.0).takeIf { it > 0.0 } ?: last
            val spreadPct = ((effectiveAsk - effectiveBid) / mid * 100.0).coerceAtLeast(0.0)
            val high = ticker.highPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: last
            val low = ticker.lowPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: last
            val quoteVolume = DecimalValue(ticker.quoteVolume)
            val baseVolume = DecimalValue(ticker.volume)
            val quoteVolumeUnits = quoteVolume.toDoubleOrZero()
            val volumeFactor = (quoteVolumeUnits / 250_000.0).coerceIn(0.0, 1.0)
            val stability = (1.0 - (spreadPct / 0.8)).coerceIn(0.0, 1.0) * 0.45 + (volumeFactor * 0.55)
            val openPrice = ticker.openPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: mid
            val weightedAvg = ticker.weightedAvgPrice.toDoubleOrZero().takeIf { it > 0.0 } ?: openPrice
            val shortTermReturnPct = percentChange(last, openPrice)
            val mediumTermReturnPct = percentChange(last, weightedAvg)
            val realizedVolatilityPct = if (low > 0.0) (((high - low) / low) * 100.0).coerceAtLeast(0.0) else 0.0
            val slippagePct = (spreadPct * 0.5).coerceAtLeast(0.01)
            val tradeCount24h = ticker.count.coerceAtLeast(0)
            val top5DepthQuote = estimateTopDepthQuote(quoteVolumeUnits, stability, spreadPct)
            val trendQualityScore = deriveTrendQualityScore(shortTermReturnPct, mediumTermReturnPct)
            val volatilityQualityScore = deriveVolatilityQualityScore(realizedVolatilityPct, spreadPct)
            val fillQualityScore = weightedScore(
                stability to 0.42,
                volumeFactor to 0.34,
                (1.0 - (slippagePct / 0.8)).coerceIn(0.0, 1.0) to 0.24,
            )
            val historicalExpectancyScore = weightedScore(
                trendQualityScore to 0.4,
                volatilityQualityScore to 0.22,
                fillQualityScore to 0.22,
                volumeFactor to 0.16,
            )
            val holdabilityScore = weightedScore(
                trendQualityScore to 0.42,
                stability to 0.24,
                volumeFactor to 0.18,
                (1.0 - (realizedVolatilityPct / 20.0)).coerceIn(0.0, 1.0) to 0.16,
            )
            MarketQuote(
                pairId = pairId,
                bestBid = DecimalValue(ticker.bidPrice.takeIf { it.isNotBlank() } ?: last.toString()),
                bestAsk = DecimalValue(ticker.askPrice.takeIf { it.isNotBlank() } ?: last.toString()),
                midPrice = DecimalValue.fromDouble(mid),
                spreadPct = spreadPct,
                quoteVolume24h = quoteVolume,
                baseVolume24h = baseVolume,
                estimatedSlippagePct = slippagePct,
                orderBookStabilityScore = stability.coerceIn(0.0, 1.0),
                tradeCount24h = tradeCount24h,
                bidDepthTop5Idr = DecimalValue.fromDouble(top5DepthQuote),
                askDepthTop5Idr = DecimalValue.fromDouble(top5DepthQuote * 0.98),
                shortTermReturnPct = shortTermReturnPct,
                mediumTermReturnPct = mediumTermReturnPct,
                realizedVolatilityPct = realizedVolatilityPct,
                recentTradeActivityScore = tradeActivityScore(tradeCount24h, volumeFactor),
                volatilityQualityScore = volatilityQualityScore,
                trendQualityScore = trendQualityScore,
                historicalExpectancyScore = historicalExpectancyScore,
                fillQualityScore = fillQualityScore,
                holdabilityScore = holdabilityScore,
                capturedAt = Clock.System.now(),
            )
        }
        cachedMarketQuotes = CachedMarketQuotes(
            quotes = quotes,
            fetchedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
        return quotes
    }

    override suspend fun fetchBalances(): List<BalanceSnapshot> {
        if (!privateApiEnabled) return emptyList()
        val response = signedGet<AccountInfoResponse>("account")
        return response.balances.mapNotNull { balance ->
            val free = DecimalValue(balance.free)
            val locked = DecimalValue(balance.locked)
            if (free.toDoubleOrZero() <= 0.0 && locked.toDoubleOrZero() <= 0.0) return@mapNotNull null
            BalanceSnapshot(
                asset = balance.asset.lowercase(),
                free = free,
                locked = locked,
                totalValueInIdr = if (balance.asset.equals(config.primaryQuoteAsset, ignoreCase = true)) {
                    DecimalValue.fromDouble(free.toDoubleOrZero() + locked.toDoubleOrZero())
                } else {
                    null
                },
            )
        }
    }

    override suspend fun fetchOpenOrders(): List<OrderSnapshot> {
        if (!privateApiEnabled) return emptyList()
        val response = signedGet<List<OrderRow>>("openOrders")
        return response.map { it.toOrderSnapshot() }
    }

    override suspend fun fetchRecentFills(pairId: PairId?, limit: Int): List<FillSnapshot> {
        if (!privateApiEnabled || pairId == null) return emptyList()
        val response = signedGet<List<MyTradeRow>>(
            path = "myTrades",
            params = linkedMapOf(
                "symbol" to pairId.toBinanceSymbol(),
                "limit" to limit.coerceIn(1, 1000).toString(),
            ),
        )
        return response.map { trade ->
            FillSnapshot(
                fillId = FillId(trade.id.toString()),
                orderId = OrderId(trade.orderId.toString()),
                pairId = pairId,
                side = if (trade.isBuyer) OrderSide.BUY else OrderSide.SELL,
                quantity = DecimalValue(trade.qty),
                price = DecimalValue(trade.price),
                fee = DecimalValue(trade.commission),
                feeAsset = trade.commissionAsset.lowercase(),
                executedAt = Instant.fromEpochMilliseconds(trade.time),
            )
        }
    }

    override suspend fun placeOrder(plan: ExecutionPlan, clientOrderId: ClientOrderId): OrderSnapshot {
        requirePrivateApi("place Binance order")
        if (config.shadowMode) {
            return buildShadowFilledOrder(plan, clientOrderId)
        }
        val pairId = plan.signal.pairId
        val symbol = pairId.toBinanceSymbol()
        val params = linkedMapOf(
            "symbol" to symbol,
            "side" to plan.side.toApiValue(),
            "newClientOrderId" to clientOrderId.value,
            "newOrderRespType" to "FULL",
        )
        when (plan.orderType) {
            OrderType.MARKET -> {
                params["type"] = "MARKET"
                if (plan.side == OrderSide.BUY) {
                    params["quoteOrderQty"] = formatDecimal(
                        plan.quoteBudget?.value ?: error("Market buy requires quoteBudget."),
                    )
                } else {
                    params["quantity"] = formatDecimal(plan.quantity.value)
                }
            }
            OrderType.LIMIT -> {
                if (plan.postOnlyPreferred) {
                    params["type"] = "LIMIT_MAKER"
                } else {
                    params["type"] = "LIMIT"
                    params["timeInForce"] = "GTC"
                }
                params["quantity"] = formatDecimal(plan.quantity.value)
                params["price"] = formatDecimal(
                    plan.limitPrice?.value ?: error("Limit order requires limitPrice."),
                )
            }
        }
        val response = signedPost<OrderPlacementResponse>("order", params)
        return response.toOrderSnapshot(pairId)
    }

    private fun buildShadowFilledOrder(
        plan: ExecutionPlan,
        clientOrderId: ClientOrderId,
    ): OrderSnapshot {
        val now = Clock.System.now()
        val simulatedPrice = plan.limitPrice
            ?: plan.signal.entryPrice
            ?: plan.signal.takeProfitPrice
            ?: plan.signal.stopPrice
            ?: DecimalValue("1")
        val simulatedQuantity = when {
            plan.quantity.toDoubleOrZero() > 0.0 -> plan.quantity
            plan.quoteBudget != null && simulatedPrice.toDoubleOrZero() > 0.0 -> {
                val quoteBudget = plan.quoteBudget
                DecimalValue.fromDouble((quoteBudget?.toDoubleOrZero() ?: 0.0) / simulatedPrice.toDoubleOrZero())
            }
            else -> DecimalValue.Zero
        }
        println(
            "[SHADOW MODE] Executed ${plan.side.name} ${plan.signal.pairId.value} " +
                "at ${simulatedPrice.value} for ${simulatedQuantity.value}",
        )
        return OrderSnapshot(
            orderId = OrderId("shadow-${clientOrderId.value}"),
            clientOrderId = clientOrderId,
            pairId = plan.signal.pairId,
            side = plan.side,
            orderType = plan.orderType,
            status = OrderStatus.FILLED,
            price = simulatedPrice,
            originalQuantity = simulatedQuantity,
            executedQuantity = simulatedQuantity,
            remainingQuantity = DecimalValue.Zero,
            feePaid = DecimalValue.Zero,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun cancelOrder(clientOrderId: ClientOrderId): Boolean {
        if (!privateApiEnabled) return false
        val openOrder = runCatching {
            fetchOpenOrders().firstOrNull { it.clientOrderId == clientOrderId }
        }.getOrNull() ?: return false
        return runCatching {
            signedDelete<OrderRow>(
                path = "order",
                params = linkedMapOf(
                    "symbol" to openOrder.pairId.toBinanceSymbol(),
                    "origClientOrderId" to clientOrderId.value,
                ),
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun estimateMarketBuyImpact(
        pairId: PairId,
        quoteBudget: Double,
    ): MarketBuyImpactEstimate? = null

    suspend fun fetchOrderByClientOrderId(clientOrderId: ClientOrderId, pairId: PairId): OrderSnapshot {
        requirePrivateApi("fetch Binance order by client order id")
        val response = signedGet<OrderRow>(
            path = "order",
            params = linkedMapOf(
                "symbol" to pairId.toBinanceSymbol(),
                "origClientOrderId" to clientOrderId.value,
            ),
        )
        return response.toOrderSnapshot()
    }

    private suspend inline fun <reified T> signedGet(
        path: String,
        params: LinkedHashMap<String, String> = linkedMapOf(),
    ): T {
        requirePrivateApi("perform Binance private GET")
        val query = signedQuery(params)
        val responseText = withUrlFailover(config.privateRestUrls(path)) { url ->
            client.get(url) {
                url {
                    query.parameters.forEach { (key, value) -> parameter(key, value) }
                    parameter("signature", query.signature)
                }
                header("X-MBX-APIKEY", credentials.apiKey)
            }.bodyAsText()
        }
        return decodeResponse(responseText)
    }

    private suspend inline fun <reified T> signedPost(
        path: String,
        params: LinkedHashMap<String, String>,
    ): T {
        requirePrivateApi("perform Binance private POST")
        val query = signedQuery(params)
        val responseText = withUrlFailover(config.privateRestUrls(path)) { url ->
            client.post(url) {
                header("X-MBX-APIKEY", credentials.apiKey)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody((query.parameters + ("signature" to query.signature)).toFormBody())
            }.bodyAsText()
        }
        return decodeResponse(responseText)
    }

    private suspend inline fun <reified T> signedDelete(
        path: String,
        params: LinkedHashMap<String, String>,
    ): T {
        requirePrivateApi("perform Binance private DELETE")
        val query = signedQuery(params)
        val responseText = withUrlFailover(config.privateRestUrls(path)) { url ->
            client.delete(url) {
                url {
                    query.parameters.forEach { (key, value) -> parameter(key, value) }
                    parameter("signature", query.signature)
                }
                header("X-MBX-APIKEY", credentials.apiKey)
            }.bodyAsText()
        }
        return decodeResponse(responseText)
    }

    private suspend fun <T> withUrlFailover(urls: List<String>, block: suspend (String) -> T): T {
        var lastError: Throwable? = null
        val candidates = urls.ifEmpty { error("No Binance endpoint candidates provided for request.") }
        for ((idx, url) in candidates.withIndex()) {
            try {
                return block(url)
            } catch (error: Throwable) {
                lastError = error
                if (idx == candidates.lastIndex) break
                if (!shouldRetryAgainstNextEndpoint(error)) throw error
            }
        }
        throw (lastError ?: error("Binance request failed with unknown error."))
    }

    private fun shouldRetryAgainstNextEndpoint(error: Throwable): Boolean {
        return when (error) {
            is ConnectException,
            is SocketTimeoutException,
            is UnknownHostException,
            is IOException,
            -> true
            is ResponseException -> {
                val status = error.response.status.value
                status == 429 || status in 500..599
            }
            else -> false
        }
    }

    private inline fun <reified T> decodeResponse(responseText: String): T {
        val error = runCatching { json.decodeFromString(BinanceErrorResponse.serializer(), responseText) }.getOrNull()
        if (error?.code != null && error.msg != null) {
            throw ExchangeRejectedException("${error.msg} (${error.code})")
        }
        return json.decodeFromString(responseText)
    }

    private fun signedQuery(params: LinkedHashMap<String, String>): SignedQuery {
        val payload = LinkedHashMap(params).apply {
            put("timestamp", Clock.System.now().toEpochMilliseconds().toString())
            put("recvWindow", config.receiveWindowMillis.toString())
        }
        val queryString = payload.entries.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }
        return SignedQuery(payload, HmacSha256Signer.sign(credentials.apiSecret, queryString))
    }

    private fun requirePrivateApi(action: String) {
        if (!privateApiEnabled) {
            throw ExchangeRejectedException("Binance private credentials not configured; cannot $action.")
        }
    }

    companion object {
        private val orderVisibilityRetryDelaysMs = listOf(250L, 500L, 900L, 1_500L)
        private const val orderVisibilityRetries = 4
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    constructor(
        config: BinanceClientConfig,
        credentials: BinanceCredentials,
    ) : this(
        config = config,
        credentials = credentials,
        client = createPlatformHttpClient(defaultJson),
        json = defaultJson,
    )
}

internal fun buildTicker24hSymbolsPayload(): String {
    val symbols = CoinUniverse.byBinance.keys
        .asSequence()
        .filterNot { it.equals("USDTUSDT", ignoreCase = true) }
        .sorted()
        .toList()
    return Json.encodeToString(ListSerializer(String.serializer()), symbols)
}

internal fun buildTicker24hSymbolBatches(batchSize: Int = 8): List<String> {
    val symbols = CoinUniverse.byBinance.keys
        .asSequence()
        .filterNot { it.equals("USDTUSDT", ignoreCase = true) }
        .sorted()
        .toList()
    return symbols
        .chunked(batchSize.coerceAtLeast(1))
        .map { Json.encodeToString(ListSerializer(String.serializer()), it) }
}

private data class SignedQuery(
    val parameters: Map<String, String>,
    val signature: String,
)

@Serializable
private data class BinanceErrorResponse(
    val code: Int? = null,
    val msg: String? = null,
)

@Serializable
private data class Ticker24hRow(
    val symbol: String,
    val priceChangePercent: String = "0",
    val weightedAvgPrice: String = "0",
    val lastPrice: String,
    val bidPrice: String = "0",
    val askPrice: String = "0",
    val openPrice: String = "0",
    val highPrice: String = "0",
    val lowPrice: String = "0",
    val volume: String,
    val quoteVolume: String,
    val count: Int = 0,
)

@Serializable
private data class AccountInfoResponse(
    val balances: List<AccountBalanceRow>,
)

@Serializable
private data class AccountBalanceRow(
    val asset: String,
    val free: String,
    val locked: String,
)

@Serializable
private data class OrderPlacementResponse(
    val symbol: String,
    @SerialName("orderId") val orderId: Long,
    @SerialName("clientOrderId") val clientOrderId: String,
    val price: String = "0",
    @SerialName("origQty") val origQty: String = "0",
    @SerialName("executedQty") val executedQty: String = "0",
    val status: String,
    val type: String,
    val side: String,
    val time: Long? = null,
    val transactTime: Long? = null,
    @SerialName("workingTime") val workingTime: Long? = null,
)

@Serializable
private data class OrderRow(
    val symbol: String,
    @SerialName("orderId") val orderId: Long,
    @SerialName("clientOrderId") val clientOrderId: String,
    val price: String = "0",
    @SerialName("origQty") val origQty: String = "0",
    @SerialName("executedQty") val executedQty: String = "0",
    val status: String,
    val type: String,
    val side: String,
    val time: Long? = null,
    val updateTime: Long? = null,
)

@Serializable
private data class MyTradeRow(
    val symbol: String,
    val id: Long,
    @SerialName("orderId") val orderId: Long,
    val price: String,
    val qty: String,
    val commission: String,
    @SerialName("commissionAsset") val commissionAsset: String,
    val time: Long,
    @SerialName("isBuyer") val isBuyer: Boolean,
)

private fun OrderPlacementResponse.toOrderSnapshot(pairId: PairId): OrderSnapshot {
    val createdAt = Instant.fromEpochMilliseconds(time ?: transactTime ?: workingTime ?: Clock.System.now().toEpochMilliseconds())
    val updatedAt = Instant.fromEpochMilliseconds(transactTime ?: workingTime ?: updateTimeOrNow())
    val originalQuantity = DecimalValue(origQty)
    val executedQuantity = DecimalValue(executedQty)
    return OrderSnapshot(
        orderId = OrderId(orderId.toString()),
        clientOrderId = ClientOrderId(clientOrderId),
        pairId = pairId,
        side = side.toOrderSide(),
        orderType = type.toOrderType(),
        status = status.toOrderStatus(),
        price = DecimalValue(price),
        originalQuantity = originalQuantity,
        executedQuantity = executedQuantity,
        remainingQuantity = DecimalValue.fromDouble((originalQuantity.toDoubleOrZero() - executedQuantity.toDoubleOrZero()).coerceAtLeast(0.0)),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun OrderPlacementResponse.updateTimeOrNow(): Long = transactTime ?: workingTime ?: Clock.System.now().toEpochMilliseconds()

private fun OrderRow.toOrderSnapshot(): OrderSnapshot {
    val pairId = symbol.toPairId()
    val createdAt = Instant.fromEpochMilliseconds(time ?: Clock.System.now().toEpochMilliseconds())
    val updatedAt = Instant.fromEpochMilliseconds(updateTime ?: time ?: Clock.System.now().toEpochMilliseconds())
    val originalQuantity = DecimalValue(origQty)
    val executedQuantity = DecimalValue(executedQty)
    return OrderSnapshot(
        orderId = OrderId(orderId.toString()),
        clientOrderId = ClientOrderId(clientOrderId),
        pairId = pairId,
        side = side.toOrderSide(),
        orderType = type.toOrderType(),
        status = status.toOrderStatus(),
        price = DecimalValue(price),
        originalQuantity = originalQuantity,
        executedQuantity = executedQuantity,
        remainingQuantity = DecimalValue.fromDouble((originalQuantity.toDoubleOrZero() - executedQuantity.toDoubleOrZero()).coerceAtLeast(0.0)),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun String.toOrderStatus(): OrderStatus = when (uppercase()) {
    "NEW" -> OrderStatus.OPEN
    "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED
    "FILLED" -> OrderStatus.FILLED
    "CANCELED", "PENDING_CANCEL", "EXPIRED" -> OrderStatus.CANCELED
    "REJECTED" -> OrderStatus.REJECTED
    else -> OrderStatus.UNKNOWN
}

private fun String.toOrderType(): OrderType = when (uppercase()) {
    "MARKET" -> OrderType.MARKET
    else -> OrderType.LIMIT
}

private fun String.toOrderSide(): OrderSide = if (equals("SELL", ignoreCase = true)) OrderSide.SELL else OrderSide.BUY

private fun OrderSide.toApiValue(): String = if (this == OrderSide.SELL) "SELL" else "BUY"

private fun PairId.toBinanceSymbol(): String {
    com.kicryp.core.data.CoinUniverse.indodaxToBinance(value.lowercase())?.let { return it }
    val base = value.lowercase().removeSuffix("_idr").removeSuffix("_usdt")
    return "${base.uppercase()}USDT"
}

private fun String.toPairId(primaryQuote: String? = null): PairId {
    val lower = lowercase()
    val quote = primaryQuote?.takeIf { lower.endsWith(it) }
        ?: listOf("usdt", "fdusd", "usdc", "btc", "eth", "bnb", "try").firstOrNull { lower.endsWith(it) }
        ?: error("Unsupported Binance symbol: $this")
    val base = lower.removeSuffix(quote)
    return PairId("${base}_${quote}")
}

private fun formatDecimal(value: String): String = value.trim().removeSuffix(".0")

private fun String.toDoubleOrZero(): Double = toDoubleOrNull() ?: 0.0

private fun Map<String, String>.toFormBody(): String = entries.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }

private fun String.urlEncode(): String = buildString {
    for (character in this@urlEncode) {
        when (character) {
            ' ' -> append("%20")
            '+' -> append("%2B")
            '&' -> append("%26")
            '=' -> append("%3D")
            else -> append(character)
        }
    }
}

private fun percentChange(current: Double, reference: Double): Double {
    if (reference <= 0.0) return 0.0
    return ((current - reference) / reference) * 100.0
}

private fun estimateTopDepthQuote(
    quoteVolume: Double,
    stabilityScore: Double,
    spreadPct: Double,
): Double {
    val depthFactor = (0.0028 + (stabilityScore * 0.0075) - (spreadPct * 0.0035)).coerceIn(0.0008, 0.016)
    return (quoteVolume * depthFactor).coerceAtLeast(0.0)
}

private fun deriveTrendQualityScore(shortTermReturnPct: Double, mediumTermReturnPct: Double): Double {
    val shortScore = ((shortTermReturnPct + 4.0) / 12.0).coerceIn(0.0, 1.0)
    val mediumScore = ((mediumTermReturnPct + 6.0) / 18.0).coerceIn(0.0, 1.0)
    return weightedScore(shortScore to 0.42, mediumScore to 0.58)
}

private fun deriveVolatilityQualityScore(realizedVolatilityPct: Double, spreadPct: Double): Double {
    val volatilityBand = when {
        realizedVolatilityPct in 1.5..22.0 -> 1.0
        realizedVolatilityPct < 1.5 -> (realizedVolatilityPct / 1.5).coerceIn(0.0, 1.0) * 0.85
        else -> (1.0 - ((realizedVolatilityPct - 22.0) / 35.0)).coerceIn(0.0, 1.0)
    }
    val spreadPenalty = (1.0 - (spreadPct / 1.2)).coerceIn(0.0, 1.0)
    return weightedScore(volatilityBand to 0.72, spreadPenalty to 0.28)
}

private fun tradeActivityScore(tradeCount24h: Int, volumeFactor: Double): Double {
    val tradeScore = (tradeCount24h.toDouble() / 20_000.0).coerceIn(0.0, 1.0)
    return weightedScore(tradeScore to 0.65, volumeFactor to 0.35)
}

private fun weightedScore(vararg parts: Pair<Double, Double>): Double {
    val weightTotal = parts.sumOf { it.second }.takeIf { it > 0.0 } ?: return 0.0
    return (parts.sumOf { it.first * it.second } / weightTotal).coerceIn(0.0, 1.0)
}
