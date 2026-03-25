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
import io.ktor.server.response.header
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
                call.response.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                call.response.header("Pragma", "no-cache")
                call.response.header("Expires", "0")
                call.respondHtml {
                    head {
                        title("KiBot Server Monitor")
                        meta {
                            name = "viewport"
                            content = "width=device-width, initial-scale=1"
                        }
                        unsafe {
                            +"""<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='14' fill='%23060d22'/%3E%3Ctext x='50%25' y='53%25' dominant-baseline='middle' text-anchor='middle' font-family='Arial, Helvetica, sans-serif' font-size='30' font-weight='700' fill='%232dd881'%3EK%3C/text%3E%3C/svg%3E">"""
                        }
                        style {
                            unsafe {
                                +dashboardStyles()
                            }
                        }
                    }
                    body {
                        div("sr-only") { +"server-monitor-v3" }
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

                                function renderTimeline(entries) {
                                  const container = document.getElementById('log-lines');
                                  container.innerHTML = '';
                                  if (!entries || entries.length === 0) {
                                    const empty = document.createElement('p');
                                    empty.textContent = 'Belum ada aktivitas server terbaru.';
                                    empty.className = 'muted-copy';
                                    container.appendChild(empty);
                                    return;
                                  }
                                  entries.forEach(entry => {
                                    const row = document.createElement('div');
                                    row.className = 'timeline-row';
                                    const category = entry.category ? entry.category + ' • ' : '';
                                    row.textContent = category + entry.message;
                                    container.appendChild(row);
                                  });
                                }

                                function renderTradeHistory(entries) {
                                  const container = document.getElementById('trade-lines');
                                  container.innerHTML = '';
                                  if (!entries || entries.length === 0) {
                                    const empty = document.createElement('p');
                                    empty.textContent = 'Belum ada trade history.';
                                    empty.className = 'muted-copy';
                                    container.appendChild(empty);
                                    return;
                                  }
                                  entries.slice(0, 10).forEach(entry => {
                                    const row = document.createElement('div');
                                    row.className = 'timeline-row';
                                    const pair = (entry.pair || '-').toLowerCase();
                                    const side = (entry.side || '-').toUpperCase();
                                    const status = (entry.status || '-').toUpperCase();
                                    const detail = entry.detail || '-';
                                    row.textContent = side + ' ' + pair + ' • ' + detail + ' • ' + status;
                                    container.appendChild(row);
                                  });
                                }

                                function refreshState() {
                                  fetch('/api/state', { cache: 'no-store' })
                                    .then(r => r.json())
                                    .then(state => {
                                      updateStatusBadge(state);
                                      document.getElementById('portfolio-value').textContent = state.portfolioValueIdr;
                                      document.getElementById('hero-pnl').textContent = state.pnlTodayIdr;
                                      document.getElementById('last-updated').textContent = state.lastUpdatedLabel;
                                      document.getElementById('hero-summary').textContent = state.statusMessage;
                                      document.getElementById('release-label').textContent = 'Oracle Active ' + (state.releaseLabel || '#0');
                                      document.getElementById('top-candidate').textContent = state.topCandidate;
                                      document.getElementById('market-regime').textContent = state.marketRegime;
                                      document.getElementById('edge-confidence').textContent = state.edgeConfidence;
                                      document.getElementById('operating-mode').textContent = state.operatingMode;
                                      document.getElementById('health-summary').textContent = state.healthSummary;
                                      document.getElementById('active-engine').textContent = state.activeEngine;
                                      document.getElementById('sync-health').textContent = state.syncHealth;
                                      document.getElementById('exchange-ping').textContent = state.exchangePingMs;
                                      document.getElementById('server-uptime').textContent = state.serverUptime;
                                      document.getElementById('heartbeat').textContent = state.lastHeartbeatLabel;
                                      document.getElementById('ret-1d').textContent = state.pnlTodayIdr;
                                      document.getElementById('ret-7d').textContent = state.pnlTodayIdr;
                                      document.getElementById('ret-30d').textContent = state.pnlTodayIdr;
                                      renderHeldAssets(state.heldAssets);
                                      renderTradeHistory(state.recentOrders || []);
                                      renderTimeline(state.liveTimeline || []);
                                    })
                                    .catch(() => {
                                      document.getElementById('hero-summary').textContent = 'Gagal ambil status server. Cek deploy Oracle atau health endpoint.';
                                    });
                                }

                                refreshState();
                                setInterval(refreshState, ${statePollIntervalMillis});
                                """.trimIndent()
                            }
                        }
                    }
                }
            }

            get("/api/state") {
                call.response.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                call.respond(repository.state.value)
            }

            get("/api/logs") {
                call.response.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                call.respond(repository.state.value.liveTimeline.map { "${it.category} • ${it.message}" })
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
        div("row row-top") {
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
                        attributes["id"] = "release-label"
                        +"Oracle Active ${state.releaseLabel}"
                    }
                }
                p("hero-status") {
                    attributes["id"] = "hero-summary"
                    +state.statusMessage
                }
            }

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
        }

        div("row row-middle") {
            div("returns-wrap") {
                div("returns-grid") {
                    metricCard("Return 1D", state.pnlTodayIdr, "ret-1d")
                    metricCard("Return 7D", state.pnlTodayIdr, "ret-7d")
                    metricCard("Return 30D", state.pnlTodayIdr, "ret-30d")
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
        }

        div("row row-bottom") {
            div("card activity-card") {
                div("card-header-row") {
                    h2 { +"Trade History" }
                    div("pill pill-neutral") { +"Live" }
                }
                div("log-list") {
                    attributes["id"] = "trade-lines"
                    p("muted-copy") { +"Loading trade history..." }
                }
            }

            div("card activity-card") {
                div("card-header-row") {
                    h2 { +"Timeline Logs" }
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
      height: 100vh;
      font-family: "SF Pro Display", "Segoe UI", sans-serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(96,165,250,0.18), transparent 28%),
        radial-gradient(circle at top right, rgba(45,216,129,0.10), transparent 24%),
        linear-gradient(180deg, var(--bg-0), var(--bg-1));
      overflow: hidden;
    }
    .page-shell {
      max-width: 1240px;
      margin: 0 auto;
      height: 100vh;
      padding: 14px 18px 16px;
      display: grid;
      grid-template-rows: auto auto 1fr;
      gap: 10px;
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
    .hero-card { padding: 16px 18px; }
    .hero-topbar {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 18px;
    }
    .hero-topbar h1 {
      margin: 0;
      font-size: 44px;
      line-height: 1;
      letter-spacing: -0.04em;
    }
    .hero-topbar-right {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      gap: 6px;
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
      margin-top: 10px;
      font-size: clamp(42px, 5.4vw, 70px);
      font-weight: 900;
      line-height: 0.96;
      letter-spacing: -0.05em;
    }
    .hero-pnl-row {
      margin-top: 10px;
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
    }
    .hero-pnl {
      font-size: clamp(24px, 3.2vw, 34px);
      font-weight: 900;
      line-height: 1;
      color: #2dd881;
    }
    .hero-status {
      margin: 10px 0 0;
      color: var(--muted);
      font-size: 14px;
      line-height: 1.4;
    }
    .row,
    .returns-grid {
      display: grid;
      gap: 12px;
    }
    .row-top {
      grid-template-columns: 1.55fr 1fr;
      align-items: stretch;
      min-height: 0;
    }
    .row-middle {
      grid-template-columns: 1.1fr 1fr;
      align-items: stretch;
      min-height: 0;
    }
    .row-bottom {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      min-height: 0;
      align-items: stretch;
      overflow: hidden;
    }
    .returns-wrap {
      display: grid;
      grid-template-rows: auto 1fr;
      gap: 12px;
      min-height: 0;
    }
    .returns-grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .metric-card {
      padding: 10px 12px;
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
      font-size: 18px;
      font-weight: 800;
    }
    .card { padding: 14px; min-height: 0; }
    .live-pair-card { min-height: 0; }
    .activity-card { min-height: 0; display: grid; grid-template-rows: auto 1fr; overflow: hidden; }
    .card-header-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 8px;
    }
    .card h2 {
      margin: 0;
      font-size: 18px;
      line-height: 1.2;
    }
    .pair-hero {
      font-size: clamp(30px, 3vw, 40px);
      font-weight: 900;
      line-height: 1;
      letter-spacing: -0.04em;
      margin-bottom: 8px;
    }
    .pair-support-copy,
    .muted-copy {
      margin: 0;
      color: var(--muted);
      line-height: 1.35;
      font-size: 13px;
    }
    .pair-meta-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 8px;
      margin-top: 8px;
    }
    .pair-meta-chip {
      padding: 10px;
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
      font-size: 14px;
      font-weight: 800;
      word-break: break-word;
    }
    .status-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 14px;
      padding: 8px 0;
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
      gap: 8px;
    }
    .holdings-list {
      max-height: 170px;
      overflow-y: auto;
    }
    .holding-row,
    .timeline-row {
      padding: 9px 11px;
      border-radius: 12px;
      background: rgba(255,255,255,0.04);
      border: 1px solid rgba(255,255,255,0.05);
      line-height: 1.45;
    }
    .log-list {
      max-height: 100%;
      overflow-y: auto;
      font-family: "SF Pro Text", "Segoe UI", sans-serif;
      color: var(--muted);
      font-size: 12px;
    }
    @media (max-width: 920px) {
      .row,
      .returns-grid,
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
