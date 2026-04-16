package com.kicryp.binance

import kotlinx.serialization.Serializable

@Serializable
data class BinanceCredentials(
    val apiKey: String,
    val apiSecret: String,
)

@Serializable
data class BinanceClientConfig(
    val publicBaseUrl: String = "https://api.binance.com",
    val privateBaseUrl: String = "https://api.binance.com",
    val publicRestPathPrefix: String = "/api/v3",
    val privateRestPathPrefix: String = "/api/v3",
    val publicWebSocketUrl: String = "wss://stream.binance.com:9443/ws",
    /**
     * Optional fallback base URLs (comma/space separated via runtime config).
     * When set, the gateway will retry network failures against these endpoints in order.
     */
    val publicBaseUrls: List<String> = emptyList(),
    val privateBaseUrls: List<String> = emptyList(),
    val publicWebSocketUrls: List<String> = emptyList(),
    val receiveWindowMillis: Long = 10_000,
    val defaultFeePct: Double = 0.001,
    val primaryQuoteAsset: String = "usdt",
    val shadowMode: Boolean = false,
) {
    fun publicRestUrl(path: String): String = "${publicBaseUrl.trimEnd('/')}/${normalizedPublicPath(path)}"

    fun privateRestUrl(path: String): String = "${privateBaseUrl.trimEnd('/')}/${normalizedPrivatePath(path)}"

    fun publicWebSocketStreamUrl(stream: String): String =
        "${publicWebSocketUrl.trimEnd('/')}/${stream.trimStart('/')}"

    fun publicRestUrls(path: String): List<String> =
        baseUrlCandidates(publicBaseUrl, publicBaseUrls).map { base -> "${base.trimEnd('/')}/${normalizedPublicPath(path)}" }

    fun privateRestUrls(path: String): List<String> =
        baseUrlCandidates(privateBaseUrl, privateBaseUrls).map { base -> "${base.trimEnd('/')}/${normalizedPrivatePath(path)}" }

    private fun normalizedPublicPath(path: String): String =
        joinRestPath(publicRestPathPrefix, path)

    private fun normalizedPrivatePath(path: String): String =
        joinRestPath(privateRestPathPrefix, path)

    private fun baseUrlCandidates(primary: String, fallbacks: List<String>): List<String> {
        val normalizedPrimary = primary.trim().takeIf { it.isNotBlank() }
        val normalizedFallbacks = fallbacks.map { it.trim() }.filter { it.isNotBlank() }
        return (listOfNotNull(normalizedPrimary) + normalizedFallbacks).distinct()
    }
}

private fun joinRestPath(prefix: String, path: String): String {
    val normalizedPrefix = prefix.trim().trim('/').takeIf { it.isNotBlank() }
    val normalizedPath = path.trim().trim('/').takeIf { it.isNotBlank() }
    return listOfNotNull(normalizedPrefix, normalizedPath).joinToString("/")
}
