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
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.html.FlowContent
import kotlinx.html.body
import kotlinx.html.button
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
                        title("KiBot Mac Engine")
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
                                      document.getElementById('status-message').textContent = 'Bot trading otomatis 24/7 di server Singapore. Tidak ada kontrol manual.';
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
                                      document.getElementById('edge-confidence').textContent = state.edgeConfidence;
                                      document.getElementById('exchange-ping').textContent = state.exchangePingMs;
                                      
                                      const heldAssetsDiv = document.getElementById('held-assets');
                                      heldAssetsDiv.innerHTML = '';
                                      if (state.heldAssets.length === 0) {
                                        const p = document.createElement('p');
                                        p.textContent = 'No assets held.';
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
                        script {
                            unsafe {
                                +"""
                                const commandStatus = document.getElementById('command-status');
                                const buttons = document.querySelectorAll('.command-button');
                                buttons.forEach(button => {
                                  button.addEventListener('click', () => {
                                    const action = button.dataset.action;
                                    commandStatus.textContent = 'Sending ' + action + '...';
                                    buttons.forEach(b => b.disabled = true);
                                    
                                    const formData = new URLSearchParams();
                                    formData.append('action', action);

                                    fetch('/command', {
                                      method: 'POST',
                                      body: formData
                                    }).then(r => {
                                      if (r.ok) {
                                        commandStatus.textContent = 'Command ' + action + ' sent successfully.';
                                      } else {
                                        commandStatus.textContent = 'Error sending ' + action + '.';
                                      }
                                    }).catch(err => {
                                        commandStatus.textContent = 'Network error.';
                                    }).finally(() => {
                                        setTimeout(() => {
                                            buttons.forEach(b => b.disabled = false);
                                            commandStatus.textContent = '';
                                        }, 2000);
                                    });
                                  });
                                });
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
                val action = call.receiveParameters()["action"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                dispatchCommand(action.toMacCommand())
                call.respondText("ok", ContentType.Text.Plain)
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
        div("hero") {
            div("hero-copy") {
                h1 { +"KiBot Dashboard" }
                p {
                    attributes["id"] = "status-message"
                    +"Bot trading otomatis 24/7 di server Singapore. Tidak ada kontrol manual."
                }
                div("hero-metrics") {
                    metricTile("Portfolio", state.portfolioValueIdr, "portfolio-value")
                    metricTile("PnL Hari Ini", state.pnlTodayIdr, "pnl-today")
                    metricTile("Update", state.lastUpdatedLabel, "last-updated")
                }
            }
            div("hero-actions") {
                commandButton("START BOT", "START_BOT")
                commandButton("STOP BOT", "STOP_BOT")
                commandButton("FORCE TAKEOVER", "FORCE_SAFE_TAKEOVER")
                commandButton("RELEASE CONTROL", "RELEASE_CONTROL")
                p("command-status") {
                    attributes["id"] = "command-status"
                    +""
                }
            }
        }

        div("grid") {
            div("card accent") {
                h2 { +"Wallet Snapshot" }
                statusLine("Saldo", state.portfolioValueIdr, "portfolio-value-card")
                statusLine("PnL Hari Ini", state.pnlTodayIdr, "pnl-today-card")
                statusLine("Lease Term", "#${state.leaseTerm}", "lease-term")
                statusLine("Update", state.lastUpdatedLabel, "last-updated-card")
            }
            div("card") {
                h2 { +"Status Bot" }
                statusLine("Status", "RUNNING (Otomatis 24/7)", "bot-running")
                statusLine("Execution", if (state.liveExecutionEnabled) "LIVE" else "SHADOW", "live-execution")
                statusLine("Server", "Singapore (Oracle Cloud)", "server-location")
                statusLine("Uptime", state.serverUptime, "server-uptime")
                statusLine("Ping", state.exchangePingMs, "exchange-ping")
            }
            div("card") {
                h2 { +"Market Info" }
                statusLine("Regime", state.marketRegime, "market-regime")
                statusLine("Top Candidate", state.topCandidate, "top-candidate")
                statusLine("Edge Confidence", state.edgeConfidence, "edge-confidence")
            }
            div("card") {
                h2 { +"Held Assets" }
                div("status-list") {
                    attributes["id"] = "held-assets"
                    if (state.heldAssets.isEmpty()) {
                        p { +"No assets held." }
                    } else {
                        state.heldAssets.forEach {
                            p { +it }
                        }
                    }
                }
            }
            div("card full-width") {
                h2 { +"Logs" }
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

private fun FlowContent.metricTile(label: String, value: String, idValue: String) {
    div("metric-tile") {
        span("metric-label") { +label }
        span("metric-value") {
            attributes["id"] = idValue
            +value
        }
    }
}

private fun FlowContent.commandButton(label: String, action: String) {
    button(classes = "command-button") {
        attributes["data-action"] = action
        +label
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
      max-width: 1160px;
      margin: 0 auto;
      padding: 32px 24px 48px;
    }
    .hero {
      display: grid;
      grid-template-columns: 1.2fr 1fr;
      gap: 24px;
      padding: 28px;
      border-radius: 28px;
      background: linear-gradient(135deg, rgba(255,255,255,0.10), rgba(255,255,255,0.04));
      border: 1px solid var(--stroke);
      backdrop-filter: blur(18px);
      box-shadow: 0 30px 70px rgba(0,0,0,0.28);
    }
    .hero h1 {
      margin: 0 0 12px;
      font-size: 40px;
      line-height: 1;
    }
    .hero p {
      margin: 0;
      color: var(--muted);
      font-size: 16px;
      line-height: 1.6;
    }
    .hero-metrics {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
      margin-top: 18px;
    }
    .metric-tile {
      display: grid;
      gap: 8px;
      padding: 16px;
      border-radius: 20px;
      background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.08);
    }
    .metric-label {
      color: var(--muted);
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }
    .metric-value {
      font-size: 22px;
      font-weight: 800;
    }
    .hero-actions {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
      align-content: start;
    }
    .command-button {
      width: 100%;
      border: 0;
      border-radius: 18px;
      padding: 14px 16px;
      font-size: 14px;
      font-weight: 700;
      cursor: pointer;
      color: #07111f;
      background: linear-gradient(135deg, var(--accent), #9fb7ff);
    }
    .command-button:disabled { opacity: 0.72; cursor: wait; }
    .command-status {
      margin: 8px 0 0;
      min-height: 22px;
      color: var(--muted);
      font-size: 14px;
      grid-column: 1 / -1;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
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
    .full-width {
        grid-column: 1 / -1;
    }
    .log-list {
        height: 200px;
        overflow-y: auto;
        font-family: "SF Mono", "Menlo", monospace;
        font-size: 12px;
        color: var(--muted);
    }
    .log-list p {
        margin: 0;
        padding: 4px 0;
    }
    .accent {
      background: linear-gradient(135deg, rgba(123,211,255,0.14), rgba(142,255,193,0.09));
    }
    .card h2 {
      margin: 0 0 14px;
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
      .hero, .grid {
        grid-template-columns: 1fr;
      }
      .hero-metrics {
        grid-template-columns: 1fr;
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
