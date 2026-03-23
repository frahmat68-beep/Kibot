package com.kibot.macengine.server

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
import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
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
    private val host: String = "0.0.0.0",
    private val port: Int = 8787,
    private val androidReleaseDirectory: Path,
    private val enableLanAdvertising: Boolean = true,
    private val statePollIntervalMillis: Long = 15_000L,
    private val logPollIntervalMillis: Long = 20_000L,
) {
    private val lanProbeUrl = detectLanProbeUrl(host, port)
    private val lanServiceAdvertiser = if (enableLanAdvertising) LanServiceAdvertiser(host, port) else null

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
                        renderDashboard(repository.state.value)
                        script {
                            unsafe {
                                +"""
                                function updateStatusBadge(state) {
                                  const badge = document.getElementById('status-badge');
                                  const label = document.getElementById('status-badge-label');
                                  const effective = (state.effectiveState || '').toUpperCase();
                                  const health = (state.syncHealth || '').toUpperCase();
                                  let css = 'pill-live';
                                  let text = 'LIVE';
                                  if (effective === 'SAFE_MODE') {
                                    css = 'pill-safe';
                                    text = 'SAFE';
                                  } else if (effective === 'DEGRADED' || health === 'DEGRADED') {
                                    css = 'pill-warm';
                                    text = 'WARM';
                                  } else if (health === 'BROKEN') {
                                    css = 'pill-lag';
                                    text = 'LAG';
                                  } else if (effective === 'STOPPED') {
                                    css = 'pill-off';
                                    text = 'OFF';
                                  }
                                  badge.className = 'pill ' + css;
                                  label.textContent = text;
                                }

                                function renderHeldAssets(items) {
                                  const container = document.getElementById('held-assets');
                                  container.innerHTML = '';
                                  if (!items || items.length === 0) {
                                    const empty = document.createElement('p');
                                    empty.textContent = 'Belum ada aset aktif.';
                                    empty.className = 'muted-copy';
                                    container.appendChild(empty);
                                    return;
                                  }
                                  items.forEach(item => {
                                    const row = document.createElement('div');
                                    row.className = 'holding-row';
                                    row.textContent = item;
                                    container.appendChild(row);
                                  });
                                }

                                function renderLogs(lines) {
                                  const container = document.getElementById('log-lines');
                                  container.innerHTML = '';
                                  if (!lines || lines.length === 0) {
                                    const empty = document.createElement('p');
                                    empty.textContent = 'Belum ada aktivitas server terbaru.';
                                    empty.className = 'muted-copy';
                                    container.appendChild(empty);
                                    return;
                                  }
                                  lines.forEach(line => {
                                    const row = document.createElement('div');
                                    row.className = 'timeline-row';
                                    row.textContent = line;
                                    container.appendChild(row);
                                  });
                                }

                                function refreshState() {
                                  fetch('/api/state')
                                    .then(r => r.json())
                                    .then(state => {
                                      updateStatusBadge(state);
                                      document.getElementById('portfolio-value').textContent = state.portfolioValueIdr;
                                      document.getElementById('hero-pnl').textContent = state.pnlTodayIdr;
                                      document.getElementById('last-updated').textContent = state.lastUpdatedLabel;
                                      document.getElementById('hero-summary').textContent = state.statusMessage;
                                      document.getElementById('top-candidate').textContent = state.topCandidate;
                                      document.getElementById('market-regime').textContent = state.marketRegime;
                                      document.getElementById('edge-confidence').textContent = state.edgeConfidence;
                                      document.getElementById('operating-mode').textContent = state.operatingMode;
                                      document.getElementById('feed-label').textContent = state.syncPathLabel;
                                      document.getElementById('health-summary').textContent = state.healthSummary;
                                      document.getElementById('active-engine').textContent = state.activeEngine;
                                      document.getElementById('sync-health').textContent = state.syncHealth;
                                      document.getElementById('exchange-ping').textContent = state.exchangePingMs;
                                      document.getElementById('server-uptime').textContent = state.serverUptime;
                                      document.getElementById('heartbeat').textContent = state.lastHeartbeatLabel;
                                      document.getElementById('portfolio-card').textContent = state.portfolioValueIdr;
                                      document.getElementById('pnl-card').textContent = state.pnlTodayIdr;
                                      document.getElementById('update-card').textContent = state.lastUpdatedLabel;
                                      renderHeldAssets(state.heldAssets);
                                    })
                                    .catch(() => {
                                      document.getElementById('hero-summary').textContent = 'Gagal ambil status server. Cek deploy Oracle atau health endpoint.';
                                    });
                                }

                                function refreshLogs() {
                                  fetch('/api/logs')
                                    .then(r => r.json())
                                    .then(renderLogs);
                                }

                                refreshState();
                                refreshLogs();
                                setInterval(refreshState, ${statePollIntervalMillis});
                                setInterval(refreshLogs, ${logPollIntervalMillis});
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
                    val lines = logFile.readLines().takeLast(25)
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
                    call.respondText(Files.readString(manifestPath), ContentType.Application.Json)
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
        lanServiceAdvertiser?.start()
        server.start(wait = true)
    }

    fun stop() {
        lanServiceAdvertiser?.stop()
        server.stop(1_000, 2_000)
    }
}

private fun BODY.renderDashboard(state: MacDashboardState) {
    div("page-shell") {
        div("hero-card") {
            div("hero-topbar") {
                h1 { +"KiBot" }
                div("hero-topbar-right") {
                    div("pill ${dashboardStatusClass(state)}") {
                        attributes["id"] = "status-badge"
                        span {
                            attributes["id"] = "status-badge-label"
                            +dashboardStatusLabel(state)
                        }
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
                    attributes["id"] = "hero-pnl"
                    +state.pnlTodayIdr
                }
                span("pill pill-neutral") {
                    attributes["id"] = "feed-label"
                    +state.syncPathLabel
                }
            }
            p("hero-status") {
                attributes["id"] = "hero-summary"
                +state.statusMessage
            }
        }

        div("hero-metrics-grid") {
            metricCard("Portfolio", state.portfolioValueIdr, "portfolio-card")
            metricCard("PnL Hari Ini", state.pnlTodayIdr, "pnl-card")
            metricCard("Update", state.lastUpdatedLabel, "update-card")
        }

        div("dashboard-grid") {
            div("card live-pair-card") {
                div("card-header-row") {
                    h2 { +"Live Pair" }
                    div("pill pill-blue") { +state.syncHealth }
                }
                div("pair-hero") {
                    attributes["id"] = "top-candidate"
                    +state.topCandidate
                }
                p("pair-support-copy") {
                    attributes["id"] = "health-summary"
                    +state.healthSummary
                }
                div("pair-meta-grid") {
                    metaChip("Mode", state.operatingMode, "operating-mode")
                    metaChip("Regime", state.marketRegime, "market-regime")
                    metaChip("Edge", state.edgeConfidence, "edge-confidence")
                }
            }

            div("card") {
                div("card-header-row") {
                    h2 { +"Holdings" }
                    div("pill pill-neutral") { +"Oracle 24/7" }
                }
                div("holdings-list") {
                    attributes["id"] = "held-assets"
                    if (state.heldAssets.isEmpty()) {
                        p("muted-copy") { +"Belum ada aset aktif." }
                    } else {
                        state.heldAssets.forEach { asset ->
                            div("holding-row") { +asset }
                        }
                    }
                }
            }

            div("card") {
                div("card-header-row") {
                    h2 { +"Server Snapshot" }
                    div("pill pill-neutral") {
                        attributes["id"] = "exchange-ping"
                        +state.exchangePingMs
                    }
                }
                statusLine("Runtime", state.effectiveState.name, "effective-state")
                statusLine("Server", state.activeEngine, "active-engine")
                statusLine("Sync", state.syncHealth, "sync-health")
                statusLine("Heartbeat", state.lastHeartbeatLabel, "heartbeat")
                statusLine("Uptime", state.serverUptime, "server-uptime")
            }

            div("card activity-card") {
                div("card-header-row") {
                    h2 { +"Live Timeline" }
                    div("pill pill-purple") {
                        attributes["id"] = "feed-chip"
                        +state.syncPathLabel
                    }
                }
                div("log-list") {
                    attributes["id"] = "log-lines"
                    p("muted-copy") { +"Loading timeline..." }
                }
            }
        }
    }
}

private fun FlowContent.metricCard(label: String, value: String, valueId: String) {
    div("metric-card") {
        span("metric-label") { +label }
        span("metric-value") {
            attributes["id"] = valueId
            +value
        }
    }
}

private fun FlowContent.metaChip(label: String, value: String, valueId: String) {
    div("pair-meta-chip") {
        span("pair-meta-label") { +label }
        span("pair-meta-value") {
            attributes["id"] = valueId
            +value
        }
    }
}

private fun FlowContent.statusLine(label: String, value: String, valueId: String) {
    div("status-row") {
        span("status-label") { +label }
        span("status-value") {
            attributes["id"] = valueId
            +value
        }
    }
}

private fun dashboardStatusLabel(state: MacDashboardState): String = when {
    state.effectiveState.name == "SAFE_MODE" -> "SAFE"
    state.syncHealth.equals("BROKEN", ignoreCase = true) -> "LAG"
    state.effectiveState.name == "DEGRADED" || state.syncHealth.equals("DEGRADED", ignoreCase = true) -> "WARM"
    state.effectiveState.name == "STOPPED" -> "OFF"
    else -> "LIVE"
}

private fun dashboardStatusClass(state: MacDashboardState): String = when (dashboardStatusLabel(state)) {
    "SAFE" -> "pill-safe"
    "LAG" -> "pill-lag"
    "WARM" -> "pill-warm"
    "OFF" -> "pill-off"
    else -> "pill-live"
}

private fun dashboardStyles(): String = """
    :root {
      color-scheme: dark;
      --bg-0: #0b1220;
      --bg-1: #10182b;
      --card: rgba(255,255,255,0.06);
      --stroke: rgba(255,255,255,0.08);
      --text: #ecf2ff;
      --muted: #93a4c6;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      min-height: 100vh;
      font-family: "SF Pro Display", "Segoe UI", sans-serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(96,165,250,0.18), transparent 28%),
        radial-gradient(circle at top right, rgba(45,216,129,0.10), transparent 24%),
        linear-gradient(180deg, var(--bg-0), var(--bg-1));
    }
    .page-shell {
      max-width: 1240px;
      margin: 0 auto;
      padding: 28px 22px 44px;
    }
    .hero-card,
    .metric-card,
    .card {
      border-radius: 26px;
      border: 1px solid var(--stroke);
      background: linear-gradient(135deg, rgba(255,255,255,0.08), rgba(255,255,255,0.03));
      backdrop-filter: blur(16px);
      box-shadow: 0 24px 56px rgba(0,0,0,0.24);
    }
    .hero-card { padding: 28px; }
    .hero-topbar {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 18px;
    }
    .hero-topbar h1 {
      margin: 0;
      font-size: 56px;
      line-height: 1;
      letter-spacing: -0.04em;
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
      font-size: 15px;
    }
    .pill {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 8px 14px;
      border-radius: 999px;
      font-size: 13px;
      font-weight: 800;
      letter-spacing: 0.04em;
      border: 1px solid rgba(255,255,255,0.10);
    }
    .pill-live { color: #2dd881; background: rgba(45,216,129,0.12); border-color: rgba(45,216,129,0.28); }
    .pill-safe { color: #fb923c; background: rgba(251,146,60,0.12); border-color: rgba(251,146,60,0.28); }
    .pill-warm { color: #facc15; background: rgba(250,204,21,0.12); border-color: rgba(250,204,21,0.28); }
    .pill-lag { color: #f87171; background: rgba(248,113,113,0.12); border-color: rgba(248,113,113,0.28); }
    .pill-off { color: #cbd5e1; background: rgba(203,213,225,0.08); border-color: rgba(203,213,225,0.18); }
    .pill-blue { color: #60a5fa; background: rgba(96,165,250,0.12); }
    .pill-purple { color: #b799ff; background: rgba(183,153,255,0.14); }
    .pill-neutral { color: #dbe7ff; background: rgba(255,255,255,0.08); }
    .hero-balance {
      margin-top: 20px;
      font-size: clamp(56px, 7vw, 92px);
      font-weight: 900;
      line-height: 0.96;
      letter-spacing: -0.05em;
    }
    .hero-pnl-row {
      margin-top: 16px;
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
    }
    .hero-pnl {
      font-size: clamp(28px, 4vw, 44px);
      font-weight: 900;
      line-height: 1;
      color: #2dd881;
    }
    .hero-status {
      margin: 18px 0 0;
      color: var(--muted);
      font-size: 16px;
      line-height: 1.55;
    }
    .hero-metrics-grid,
    .dashboard-grid {
      display: grid;
      gap: 18px;
    }
    .hero-metrics-grid {
      margin-top: 18px;
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .metric-card {
      padding: 18px 20px;
      display: grid;
      gap: 8px;
    }
    .metric-label {
      color: var(--muted);
      font-size: 13px;
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }
    .metric-value {
      font-size: 22px;
      font-weight: 800;
    }
    .dashboard-grid {
      margin-top: 18px;
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .card { padding: 22px; }
    .live-pair-card { min-height: 224px; }
    .activity-card { min-height: 320px; }
    .card-header-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 14px;
    }
    .card h2 {
      margin: 0;
      font-size: 20px;
      line-height: 1.2;
    }
    .pair-hero {
      font-size: clamp(36px, 5vw, 58px);
      font-weight: 900;
      line-height: 1;
      letter-spacing: -0.04em;
      margin-bottom: 14px;
    }
    .pair-support-copy,
    .muted-copy {
      margin: 0;
      color: var(--muted);
      line-height: 1.55;
    }
    .pair-meta-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
      margin-top: 14px;
    }
    .pair-meta-chip {
      padding: 14px;
      border-radius: 18px;
      background: rgba(255,255,255,0.04);
      border: 1px solid rgba(255,255,255,0.06);
      display: grid;
      gap: 6px;
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
      word-break: break-word;
    }
    .status-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 14px;
      padding: 12px 0;
      border-bottom: 1px solid rgba(255,255,255,0.07);
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
    .holdings-list,
    .log-list {
      display: grid;
      gap: 10px;
    }
    .holdings-list {
      max-height: 260px;
      overflow-y: auto;
    }
    .holding-row,
    .timeline-row {
      padding: 12px 14px;
      border-radius: 16px;
      background: rgba(255,255,255,0.04);
      border: 1px solid rgba(255,255,255,0.05);
      line-height: 1.45;
    }
    .log-list {
      max-height: 260px;
      overflow-y: auto;
      font-family: "SF Pro Text", "Segoe UI", sans-serif;
      color: var(--muted);
      font-size: 14px;
    }
    @media (max-width: 920px) {
      .hero-metrics-grid,
      .dashboard-grid,
      .pair-meta-grid {
        grid-template-columns: 1fr;
      }
      .hero-topbar {
        flex-direction: column;
      }
      .hero-topbar-right {
        align-items: flex-start;
      }
      .page-shell {
        padding: 20px 14px 36px;
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
