package com.kibot.macengine.server

import com.kibot.macengine.state.MacCommand
import com.kibot.macengine.state.MacDashboardState
import com.kibot.macengine.state.MacStateRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.html.FlowContent
import kotlinx.html.body
import kotlinx.html.classes
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.net.NetworkInterface

class LocalDashboardServer(
    private val repository: MacStateRepository,
    private val dispatchCommand: suspend (MacCommand) -> Unit,
    private val host: String = "0.0.0.0",
    private val port: Int = 8787,
    private val androidReleaseDirectory: Path,
) {
    private val lanProbeUrl = detectLanProbeUrl(host, port)
    private val lanServiceAdvertiser = LanServiceAdvertiser(host, port)

    private val server = embeddedServer(CIO, host = host, port = port) {
        install(CallLogging)
        install(ContentNegotiation) { json() }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "unknown")))
            }
        }

        routing {
            get("/") {
                call.respondHtml {
                    head {
                        title("KiBot Server Monitor")
                        meta {
                            name = "viewport"
                            content = "width=device-width, initial-scale=1"
                        }
                        style {
                            unsafe {
                                +dashboardStyles()
                            }
                        }
                    }
                    body {
                        renderDashboard(repository.state.value, lanProbeUrl)
                        script {
                            unsafe {
                                +"""
                                setInterval(() => {
                                  fetch('/api/state')
                                    .then(r => r.json())
                                    .then(state => {
                                      document.getElementById('status-message').textContent = 'View-only monitor hasil trade server Oracle. Semua kontrol manual dimatikan.';
                                      document.getElementById('portfolio-value').textContent = state.portfolioValueIdr;
                                      document.getElementById('portfolio-value-card').textContent = state.portfolioValueIdr;
                                      document.getElementById('pnl-today').textContent = state.pnlTodayIdr;
                                      document.getElementById('pnl-today-card').textContent = state.pnlTodayIdr;
                                      document.getElementById('last-updated').textContent = state.lastUpdatedLabel;
                                      document.getElementById('last-updated-card').textContent = state.lastUpdatedLabel;
                                      document.getElementById('lease-term').textContent = '#' + state.leaseTerm;
                                      document.getElementById('live-execution').textContent = state.liveExecutionEnabled ? 'LIVE' : 'SHADOW';
                                      document.getElementById('server-uptime').textContent = state.serverUptime;
                                      document.getElementById('market-regime').textContent = state.marketRegime;
                                      document.getElementById('top-candidate').textContent = state.topCandidate;
                                      document.getElementById('top-candidate-card').textContent = state.topCandidate;
                                      document.getElementById('edge-confidence').textContent = state.edgeConfidence;
                                      document.getElementById('exchange-ping').textContent = state.exchangePingMs;
                                      document.getElementById('exchange-ping-card').textContent = state.exchangePingMs;
                                      document.getElementById('sync-path').textContent = state.syncPathLabel;
                                      document.getElementById('sync-path-card').textContent = state.syncPathLabel;
                                      document.getElementById('sync-health').textContent = state.syncHealth;
                                      document.getElementById('server-location').textContent = 'Oracle 24/7';
                                      
                                      const heldAssetsDiv = document.getElementById('held-assets');
                                      heldAssetsDiv.innerHTML = '';
                                      if (state.heldAssets.length === 0) {
                                        const p = document.createElement('p');
                                        p.textContent = 'Belum ada aset aktif.';
                                        heldAssetsDiv.appendChild(p);
                                      } else {
                                        state.heldAssets.forEach(asset => {
                                          const p = document.createElement('p');
                                          p.textContent = asset;
                                          heldAssetsDiv.appendChild(p);
                                        });
                                      }
                                    });
                                }, 5000);
                                
                                setInterval(() => {
                                  fetch('/api/logs')
                                    .then(r => r.json())
                                    .then(logs => {
                                      const logLinesDiv = document.getElementById('log-lines');
                                      logLinesDiv.innerHTML = '';
                                      if (logs.length === 0) {
                                        const p = document.createElement('p');
                                        p.textContent = 'No logs available.';
                                        logLinesDiv.appendChild(p);
                                      } else {
                                        logs.forEach(line => {
                                          const p = document.createElement('p');
                                          p.textContent = line;
                                          logLinesDiv.appendChild(p);
                                        });
                                      }
                                    });
                                }, 5000);
                                """.trimIndent()
                            }
                        }
                    }
                }
            }

            get("/api/state") {
                call.respond(repository.state.value)
            }

            get("/api/logs") {
                val logFile = File(System.getProperty("java.io.tmpdir"), "kibot-mac-engine.log")
                if (logFile.exists()) {
                    val lines = logFile.readLines().takeLast(50)
                    call.respond(lines)
                } else {
                    call.respond(HttpStatusCode.NotFound, emptyList<String>())
                }
            }

            get("/api/lan/ping") {
                call.respond(LanPingResponse(ok = true, host = host, port = port, lanProbeUrl = lanProbeUrl))
            }

            get("/api/releases/android/latest") {
                val manifestPath = androidReleaseDirectory.resolve("latest.json")
                if (!Files.exists(manifestPath)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("available" to false))
                } else {
                    call.respondText(
                        Files.readString(manifestPath),
                        ContentType.Application.Json,
                    )
                }
            }

            get("/releases/android/kibot-android-latest.apk") {
                val apkPath = androidReleaseDirectory.resolve("kibot-android-latest.apk")
                if (!Files.exists(apkPath)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("available" to false))
                } else {
                    call.respondFile(apkPath.toFile())
                }
            }

            post("/command") {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "dashboard view-only"))
            }
        }
    }

    fun start() {
        lanServiceAdvertiser.start()
        server.start(wait = true)
    }

    fun stop() {
        lanServiceAdvertiser.stop()
        server.stop(1_000, 2_000)
    }
}
private fun String.toMacCommand(): MacCommand = when (uppercase()) {
    "REQUEST_TAKEOVER" -> MacCommand.REQUEST_TAKEOVER
    "FORCE_SAFE_TAKEOVER" -> MacCommand.FORCE_SAFE_TAKEOVER
    "RELEASE_CONTROL" -> MacCommand.RELEASE_CONTROL
    "SYNC_NOW" -> MacCommand.SYNC_NOW
    "START_BOT" -> MacCommand.START_BOT
    "STOP_BOT" -> MacCommand.STOP_BOT
    "TOGGLE_LIVE_EXECUTION" -> MacCommand.TOGGLE_LIVE_EXECUTION
    else -> error("Unknown action: $this")
}

private fun kotlinx.html.BODY.renderDashboard(state: MacDashboardState, lanProbeUrl: String?) {
    div("page-shell") {
        div("hero-card") {
            div("hero-topbar") {
                h1 { +"KiBot" }
                div("hero-topbar-right") {
                    div("live-pill") {
                        attributes["id"] = "live-execution"
                        +(if (state.liveExecutionEnabled) "LIVE" else "SHADOW")
                    }
                    p("hero-update") {
                        +"Update "
                        span {
                            attributes["id"] = "last-updated"
                            +state.lastUpdatedLabel
                        }
                    }
                }
            }
            div("hero-balance") {
                span {
                    attributes["id"] = "portfolio-value"
                    +state.portfolioValueIdr
                }
            }
            div("hero-pnl-row") {
                span("hero-pnl") {
                    attributes["id"] = "pnl-today"
                    +state.pnlTodayIdr
                }
                span("hero-ping-pill") {
                    attributes["id"] = "exchange-ping"
                    +state.exchangePingMs
                }
            }
            p("hero-status") {
                attributes["id"] = "status-message"
                +"View-only monitor hasil trade server Oracle. Semua kontrol manual dimatikan."
            }
        }

        div("dashboard-grid") {
            div("card live-pair-card") {
                div("card-header-row") {
                    h2 { +"Live Pair" }
                    div("chip chip-green") {
                        attributes["id"] = "sync-path"
                        +state.syncPathLabel
                    }
                }
                div("pair-hero") {
                    attributes["id"] = "top-candidate"
                    +state.topCandidate
                }
                div("pair-meta-grid") {
                    div("pair-meta-chip") {
                        span("pair-meta-label") { +"Mode" }
                        span("pair-meta-value") { +state.operatingMode }
                    }
                    div("pair-meta-chip") {
                        span("pair-meta-label") { +"Regime" }
                        span("pair-meta-value") {
                            attributes["id"] = "market-regime"
                            +state.marketRegime
                        }
                    }
                    div("pair-meta-chip") {
                        span("pair-meta-label") { +"Edge" }
                        span("pair-meta-value") {
                            attributes["id"] = "edge-confidence"
                            +state.edgeConfidence
                        }
                    }
                }
            }

            div("card") {
                div("card-header-row") {
                    h2 { +"Holdings" }
                    div("chip chip-blue") {
                        attributes["id"] = "server-location"
                        +"Oracle 24/7"
                    }
                }
                div("status-list") {
                    attributes["id"] = "held-assets"
                    if (state.heldAssets.isEmpty()) {
                        p { +"Belum ada aset aktif." }
                    } else {
                        state.heldAssets.forEach {
                            p { +it }
                        }
                    }
                }
            }

            div("card") {
                div("card-header-row") {
                    h2 { +"Server" }
                    div("chip chip-neutral") {
                        attributes["id"] = "sync-health"
                        +state.syncHealth
                    }
                }
                statusLine("Saldo", state.portfolioValueIdr, "portfolio-value-card")
                statusLine("PnL Hari Ini", state.pnlTodayIdr, "pnl-today-card")
                statusLine("Feed", state.syncPathLabel, "sync-path-card")
                statusLine("Latency", state.exchangePingMs, "exchange-ping-card")
                statusLine("Uptime", state.serverUptime, "server-uptime")
                statusLine("Lease Term", "#${state.leaseTerm}", "lease-term")
                statusLine("Update", state.lastUpdatedLabel, "last-updated-card")
            }

            div("card activity-card") {
                div("card-header-row") {
                    h2 { +"Timeline" }
                    div("chip chip-purple") {
                        attributes["id"] = "top-candidate-card"
                        +state.topCandidate
                    }
                }
                div("log-list") {
                    attributes["id"] = "log-lines"
                    p { +"Loading logs..." }
                }
            }
        }
    }
}

private fun FlowContent.statusLine(label: String, value: String, idValue: String) {
    div("status-row") {
        span("status-label") { +label }
        span("status-value") {
            attributes["id"] = idValue
            +value
        }
    }
}

private fun MacDashboardState.isBotRunningLabel(): String = if (isBotRunning) "RUNNING" else "STOPPED"

private fun dashboardStyles(): String = """
    :root {
      color-scheme: light dark;
      --bg-0: #0b1220;
      --bg-1: #101a2d;
      --card: rgba(255,255,255,0.08);
      --stroke: rgba(255,255,255,0.12);
      --text: #ebf1ff;
      --muted: #8ca0c8;
      --accent: #7bd3ff;
      --accent-2: #8effc1;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: "SF Pro Display", "Segoe UI", sans-serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(123,211,255,0.16), transparent 28%),
        radial-gradient(circle at top right, rgba(142,255,193,0.10), transparent 24%),
        linear-gradient(180deg, var(--bg-0), var(--bg-1));
      min-height: 100vh;
    }
    .page-shell {
      max-width: 1180px;
      margin: 0 auto;
      padding: 32px 24px 48px;
    }
    .hero-card {
      padding: 28px;
      border-radius: 28px;
      background: linear-gradient(135deg, rgba(255,255,255,0.10), rgba(255,255,255,0.04));
      border: 1px solid var(--stroke);
      backdrop-filter: blur(18px);
      box-shadow: 0 30px 70px rgba(0,0,0,0.28);
    }
    .hero-topbar {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 18px;
    }
    .hero-topbar h1 {
      margin: 0 0 12px;
      font-size: 40px;
      line-height: 1;
    }
    .hero-topbar-right {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 10px;
    }
    .hero-update {
      margin: 0;
      color: var(--muted);
      font-size: 16px;
      line-height: 1.2;
    }
    .live-pill, .chip {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 8px 14px;
      border-radius: 999px;
      font-size: 13px;
      font-weight: 800;
      letter-spacing: 0.03em;
      border: 1px solid rgba(255,255,255,0.10);
    }
    .live-pill {
      color: #2dd881;
      background: rgba(45,216,129,0.10);
      border-color: rgba(45,216,129,0.45);
    }
    .chip-green {
      color: #2dd881;
      background: rgba(45,216,129,0.10);
    }
    .chip-blue {
      color: #7bd3ff;
      background: rgba(123,211,255,0.10);
    }
    .chip-purple {
      color: #b799ff;
      background: rgba(183,153,255,0.12);
    }
    .chip-neutral {
      color: #dbe7ff;
      background: rgba(255,255,255,0.06);
    }
    .hero-balance {
      margin-top: 18px;
      font-size: clamp(54px, 7vw, 82px);
      font-weight: 900;
      line-height: 0.98;
      letter-spacing: -0.05em;
    }
    .hero-pnl-row {
      margin-top: 14px;
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    .hero-pnl {
      font-size: clamp(28px, 4vw, 44px);
      font-weight: 900;
      color: var(--accent-2);
      line-height: 1;
    }
    .hero-ping-pill {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 8px 14px;
      border-radius: 999px;
      font-size: 14px;
      font-weight: 800;
      color: #facc15;
      background: rgba(250,204,21,0.10);
      border: 1px solid rgba(250,204,21,0.25);
    }
    .hero-status {
      margin: 16px 0 0;
      color: var(--muted);
      font-size: 16px;
      line-height: 1.6;
    }
    .dashboard-grid {
      display: grid;
      grid-template-columns: 1.15fr 1fr;
      gap: 18px;
      margin-top: 20px;
    }
    .card {
      padding: 22px;
      border-radius: 24px;
      background: var(--card);
      border: 1px solid var(--stroke);
      backdrop-filter: blur(14px);
    }
    .live-pair-card {
      min-height: 220px;
    }
    .activity-card {
      min-height: 320px;
    }
    .card-header-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 14px;
    }
    .pair-hero {
      font-size: clamp(34px, 5vw, 56px);
      font-weight: 900;
      line-height: 1;
      letter-spacing: -0.04em;
      margin-bottom: 14px;
    }
    .pair-meta-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
    }
    .pair-meta-chip {
      display: grid;
      gap: 6px;
      padding: 14px;
      border-radius: 18px;
      background: rgba(255,255,255,0.04);
      border: 1px solid rgba(255,255,255,0.06);
    }
    .pair-meta-label {
      color: var(--muted);
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }
    .pair-meta-value {
      font-size: 16px;
      font-weight: 800;
    }
    .log-list {
        height: 240px;
        overflow-y: auto;
        font-family: "SF Mono", "Menlo", monospace;
        font-size: 12px;
        color: var(--muted);
    }
    .log-list p {
        margin: 0;
        padding: 4px 0;
    }
    .card h2 {
      margin: 0;
      font-size: 18px;
    }
    .status-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 0;
      border-bottom: 1px solid rgba(255,255,255,0.08);
    }
    .status-row:last-child { border-bottom: 0; }
    .status-label {
      color: var(--muted);
      font-size: 13px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }
    .status-value {
      font-weight: 700;
      text-align: right;
    }
    .status-list p {
      margin: 0;
      padding: 8px 0;
      font-size: 14px;
      border-bottom: 1px solid rgba(255,255,255,0.08);
    }
    .status-list p:last-child {
      border-bottom: none;
    }
    @media (max-width: 900px) {
      .dashboard-grid {
        grid-template-columns: 1fr;
      }
      .pair-meta-grid {
        grid-template-columns: 1fr;
      }
      .hero-topbar {
        flex-direction: column;
        align-items: flex-start;
      }
      .hero-topbar-right {
        align-items: flex-start;
      }
    }
""".trimIndent()

private fun detectLanProbeUrl(host: String, port: Int): String? {
    if (host != "0.0.0.0") {
        return "http://$host:$port/api/lan/ping"
    }
    val lanAddress = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { address -> !address.isLoopbackAddress && address.hostAddress?.contains(':') == false }
            ?.hostAddress
    }.getOrNull()
    return lanAddress?.let { "http://$it:$port/api/lan/ping" }
}

@Serializable
private data class LanPingResponse(
    val ok: Boolean,
    val host: String,
    val port: Int,
    val lanProbeUrl: String?,
)
