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
import kotlinx.html.strong
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
                applyDashboardSecurityHeaders(call)
                call.respondHtml {
                    head {
                        title("KiBot Server Monitor")
                        meta {
                            name = "viewport"
                            content = "width=device-width, initial-scale=1"
                        }
                        unsafe { +"""<link rel="icon" type="image/png" href="/favicon.png">""" }
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
                                  const pingText = String(state.exchangePingMs || '--').trim();
                                  const pingValue = parseInt(pingText.replace(/[^0-9]/g, ''), 10);
                                  let css = 'pill-neutral';
                                  if (!Number.isNaN(pingValue)) {
                                    if (pingValue <= 90) css = 'pill-live';
                                    else if (pingValue <= 220) css = 'pill-warm';
                                    else css = 'pill-lag';
                                  }
                                  badge.className = 'pill ' + css;
                                  label.textContent = 'Ping ' + pingText;
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
                                  entries.slice(0, 12).forEach(entry => {
                                    const row = document.createElement('div');
                                    row.className = 'timeline-row';
                                    const timestamp = entry.timestampEpochMs ? new Date(entry.timestampEpochMs).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Jakarta' }) : '--:--';
                                    const category = String(entry.category || 'LOG').toUpperCase();
                                    row.innerHTML =
                                      '<div class="timeline-head">' +
                                        '<div class="timeline-badge timeline-badge-' + category.toLowerCase() + '">' + category + '</div>' +
                                        '<div class="timeline-time">' + timestamp + '</div>' +
                                      '</div>' +
                                      '<div class="timeline-copy">' + (entry.message || '-') + '</div>';
                                    container.appendChild(row);
                                  });
                                }

                                function radarPillClass(pair) {
                                  const token = String(pair || '').toLowerCase();
                                  if (token.includes('xrp') || token.includes('btc') || token.includes('eth')) return 'radar-pill-blue';
                                  if (token.includes('doge') || token.includes('trx')) return 'radar-pill-warm';
                                  if (token.includes('pepe') || token.includes('fart') || token.includes('shib')) return 'radar-pill-mint';
                                  if (token.includes('jelly') || token.includes('plpa') || token.includes('arb')) return 'radar-pill-purple';
                                  return 'radar-pill-slate';
                                }

                                function renderRadarPairs(pairs) {
                                  const container = document.getElementById('radar-grid');
                                  if (!container) return;
                                  container.innerHTML = '';
                                  const items = (pairs || []).filter(Boolean).slice(0, 9);
                                  if (items.length === 0) {
                                    const empty = document.createElement('div');
                                    empty.className = 'radar-pill radar-pill-empty';
                                    empty.textContent = 'scan pending';
                                    container.appendChild(empty);
                                    return;
                                  }
                                  items.forEach(pair => {
                                    const pill = document.createElement('div');
                                    pill.className = 'radar-pill ' + radarPillClass(pair);
                                    pill.textContent = String(pair).toLowerCase();
                                    container.appendChild(pill);
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
                                    const timestamp = entry.timestampEpochMs ? new Date(entry.timestampEpochMs).toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Jakarta' }) : '--:--';
                                    row.innerHTML =
                                      '<div class="trade-row-shell">' +
                                        '<div class="trade-side trade-side-' + side.toLowerCase() + '">' + side + '</div>' +
                                        '<div class="trade-main">' +
                                          '<div class="trade-pair">' + pair + '</div>' +
                                          '<div class="trade-detail">' + detail + '</div>' +
                                          '<div class="trade-time">' + timestamp + '</div>' +
                                        '</div>' +
                                        '<div class="trade-status">' + status + '</div>' +
                                      '</div>';
                                    container.appendChild(row);
                                  });
                                }

                                function parseIdr(value) {
                                  const clean = String(value || '').replace(/[^0-9,-]/g, '').replace(/\\./g, '').replace(',', '.');
                                  const parsed = parseFloat(clean);
                                  return Number.isNaN(parsed) ? 0 : parsed;
                                }

                                function allocationColor(index) {
                                  const colors = ['#4bb8ff', '#ffc93d', '#29d7b8', '#6ea8ff', '#ff5f6d', '#c88bff', '#6cf08f', '#ff8f4d'];
                                  return colors[index % colors.length];
                                }

                                function renderAssetAllocation(holdings, portfolioValueLabel) {
                                  const chart = document.getElementById('allocation-chart');
                                  const legend = document.getElementById('allocation-legend');
                                  if (!chart || !legend) return;
                                  const portfolioValue = Math.max(parseIdr(portfolioValueLabel), 1);
                                  const items = (holdings || [])
                                    .map(item => ({
                                      code: item.assetCode || '-',
                                      value: parseIdr(item.valueIdrLabel || 'Rp0')
                                    }))
                                    .filter(item => item.value > 0)
                                    .sort((a, b) => b.value - a.value)
                                    .slice(0, 6);
                                  legend.innerHTML = '';
                                  if (items.length === 0) {
                                    chart.style.background = 'rgba(255,255,255,0.05)';
                                    chart.innerHTML = '<div class=\"allocation-center\"><span>Alloc</span><strong>0%</strong></div>';
                                    legend.innerHTML = '<p class=\"muted-copy\">Belum ada aset aktif.</p>';
                                    return;
                                  }
                                  let cursor = 0;
                                  const segments = [];
                                  items.forEach((item, index) => {
                                    const pct = Math.max(4, (item.value / portfolioValue) * 100);
                                    const start = cursor;
                                    const end = Math.min(100, cursor + pct);
                                    const color = allocationColor(index);
                                    item.color = color;
                                    item.pct = Math.round((item.value / portfolioValue) * 100);
                                    segments.push(color + ' ' + start + '% ' + end + '%');
                                    cursor = end;
                                  });
                                  if (cursor < 100) {
                                    segments.push('rgba(255,255,255,0.10) ' + cursor + '% 100%');
                                  }
                                  const lead = items[0];
                                  chart.style.background = 'conic-gradient(' + segments.join(', ') + ')';
                                  chart.innerHTML = '<div class=\"allocation-center\"><span>' + lead.code + '</span><strong>' + lead.pct + '%</strong></div>';
                                  items.forEach(item => {
                                    const row = document.createElement('div');
                                    row.className = 'allocation-row';
                                    row.innerHTML =
                                      '<div class=\"allocation-row-left\">' +
                                        '<span class=\"allocation-dot\" style=\"background:' + item.color + '\"></span>' +
                                        '<span class=\"allocation-code\">' + item.code + '</span>' +
                                      '</div>' +
                                      '<div class=\"allocation-pct\" style=\"color:' + item.color + '\">' + item.pct + '%</div>';
                                    legend.appendChild(row);
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
                                      document.getElementById('ai-provider-summary').textContent = state.aiProviderSummary || 'AI summary belum siap.';
                                      document.getElementById('top-candidate').textContent = state.topCandidate;
                                      document.getElementById('health-summary').textContent = state.healthSummary;
                                      renderRadarPairs(state.radarPairs || []);
                                      document.getElementById('pair-temperature').textContent = 'Ping ' + (state.exchangePingMs || '--');
                                      document.getElementById('pair-temperature').className = 'pill ' + pingPillClass(state.exchangePingMs || '--');
                                      document.getElementById('exchange-ping').textContent = state.exchangePingMs;
                                      document.getElementById('ret-1d').textContent = state.pnlTodayIdr;
                                      document.getElementById('ret-7d').textContent = state.return7dIdr;
                                      document.getElementById('ret-7d-pct').textContent = state.return7dPctLabel;
                                      document.getElementById('ret-30d').textContent = state.return30dIdr;
                                      document.getElementById('ret-30d-pct').textContent = state.return30dPctLabel;
                                      document.getElementById('ret-1d-pct').textContent = state.pnlTodayPctLabel;
                                      renderAssetAllocation(state.holdingsDetailed || [], state.portfolioValueIdr || 'Rp0');
                                      renderTradeHistory(state.recentOrders || []);
                                      renderTimeline(state.liveTimeline || []);
                                    })
                                    .catch(() => {});
                                }

                                refreshState();
                                setInterval(refreshState, ${statePollIntervalMillis});

                                function pingPillClass(pingText) {
                                  const pingValue = parseInt(String(pingText || '--').replace(/[^0-9]/g, ''), 10);
                                  if (Number.isNaN(pingValue)) return 'pill-neutral';
                                  if (pingValue <= 90) return 'pill-live';
                                  if (pingValue <= 220) return 'pill-warm';
                                  return 'pill-lag';
                                }
                                """.trimIndent()
                            }
                        }
                    }
                }
            }

            get("/api/state") {
                applyDashboardSecurityHeaders(call)
                call.respond(repository.state.value)
            }

            get("/favicon.png") {
                applyDashboardSecurityHeaders(call, cacheControl = "public, max-age=3600")
                val icon = locateDashboardIcon()
                if (icon == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("available" to false))
                } else {
                    call.respondFile(icon)
                }
            }

            get("/api/logs") {
                applyDashboardSecurityHeaders(call)
                call.respond(repository.state.value.liveTimeline.map { "${it.category} • ${it.message}" })
            }

            get("/api/lan/ping") {
                applyDashboardSecurityHeaders(call)
                call.respond(LanPingResponse(ok = true, host = host, port = port, lanProbeUrl = lanProbeUrl))
            }

            get("/api/releases/android/latest") {
                applyDashboardSecurityHeaders(call, cacheControl = "no-store, max-age=0")
                val manifestPath = androidReleaseDirectory.resolve("latest.json")
                if (!Files.exists(manifestPath)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("available" to false))
                } else {
                    call.respondText(Files.readString(manifestPath), ContentType.Application.Json)
                }
            }

            get("/releases/android/kibot-android-latest.apk") {
                applyDashboardSecurityHeaders(call, cacheControl = "no-store, max-age=0")
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

private fun applyDashboardSecurityHeaders(
    call: io.ktor.server.application.ApplicationCall,
    cacheControl: String = "no-store, no-cache, must-revalidate, max-age=0",
) {
    call.response.header("Cache-Control", cacheControl)
    call.response.header("Pragma", "no-cache")
    call.response.header("Expires", "0")
    call.response.header("X-Content-Type-Options", "nosniff")
    call.response.header("X-Frame-Options", "DENY")
    call.response.header("Referrer-Policy", "no-referrer")
    call.response.header(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none';",
    )
}

private fun BODY.renderDashboard(state: MacDashboardState) {
    div("page-shell") {
        div("row row-top") {
            div("hero-card") {
                div("hero-topbar") {
                    h1 { +"KiBot" }
                    div("hero-topbar-right") {
                        div("pill pill-neutral") {
                            attributes["id"] = "status-badge"
                            span {
                                attributes["id"] = "status-badge-label"
                                +"Ping ${state.exchangePingMs}"
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
                    span("hero-pnl-chip") { +state.pnlTodayPctLabel }
                }
                div("hero-chip-row") {
                    span("pill pill-amber") {
                        attributes["id"] = "release-label"
                        +"Oracle Active ${state.releaseLabel}"
                    }
                    span("pill pill-blue hero-clock") {
                        attributes["id"] = "last-updated"
                        +state.lastUpdatedLabel
                    }
                }
                p("hero-status") {
                    attributes["id"] = "hero-summary"
                    +state.statusMessage
                }
                p("pair-support-copy") {
                    attributes["id"] = "ai-provider-summary"
                    +state.aiProviderSummary
                }
            }

            div("card live-pair-card") {
                div("card-header-row") {
                    h2 { +"Live Pair" }
                    div("pill ${pingPillClass(state.exchangePingMs)}") {
                        attributes["id"] = "pair-temperature"
                        +"Ping ${state.exchangePingMs}"
                    }
                }
                div("pair-focus-shell") {
                    div("pair-avatar") { +state.topCandidate.take(2).uppercase() }
                    div("pair-focus-copy") {
                        div("pair-hero") {
                            attributes["id"] = "top-candidate"
                            +state.topCandidate
                        }
                        p("pair-support-copy") {
                            attributes["id"] = "health-summary"
                            +state.healthSummary
                        }
                    }
                }
                div("radar-grid") {
                    attributes["id"] = "radar-grid"
                    state.radarPairs.take(9).forEach { pair ->
                        div("radar-pill ${radarPillClass(pair)}") { +pair.lowercase() }
                    }
                }
            }
        }

        div("row row-middle") {
            div("card portfolio-card") {
                div("card-header-row") {
                    h2 { +"Portfolio" }
                    p("portfolio-update") { +"${state.lastUpdatedLabel} WIB" }
                }
                div("returns-grid") {
                    metricCard("Return 1D", state.pnlTodayIdr, state.pnlTodayPctLabel, "ret-1d", "ret-1d-pct")
                    metricCard("Return 7D", state.return7dIdr, state.return7dPctLabel, "ret-7d", "ret-7d-pct")
                    metricCard("Return 30D", state.return30dIdr, state.return30dPctLabel, "ret-30d", "ret-30d-pct")
                }
            }

            div("card") {
                div("card-header-row") {
                    h2 { +"Asset Allocation" }
                    div("pill pill-neutral") {
                        attributes["id"] = "exchange-ping"
                        +state.exchangePingMs
                    }
                }
                div("allocation-shell") {
                    div("allocation-chart-wrap") {
                        div("allocation-chart") {
                            attributes["id"] = "allocation-chart"
                            div("allocation-center") {
                                span { +"Alloc" }
                                strong { +"0%" }
                            }
                        }
                    }
                    div("allocation-legend") {
                        attributes["id"] = "allocation-legend"
                        p("muted-copy") { +"Loading allocation..." }
                    }
                }
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
                    h2 { +"Logs" }
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

private fun FlowContent.metricCard(label: String, value: String, caption: String, valueId: String, captionId: String) {
    div("metric-card ${metricCardClass(value)}") {
        span("metric-label") { +label }
        span("metric-value") {
            attributes["id"] = valueId
            +value
        }
        span("metric-caption") {
            attributes["id"] = captionId
            +caption
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

private fun metricCardClass(value: String): String = if (value.trim().startsWith("-")) "metric-card-loss" else "metric-card-gain"

private fun radarPillClass(pair: String): String {
    val token = pair.lowercase()
    return when {
        token.contains("xrp") || token.contains("btc") || token.contains("eth") -> "radar-pill-blue"
        token.contains("doge") || token.contains("trx") -> "radar-pill-warm"
        token.contains("pepe") || token.contains("fart") || token.contains("shib") -> "radar-pill-mint"
        token.contains("jelly") || token.contains("plpa") || token.contains("arb") -> "radar-pill-purple"
        else -> "radar-pill-slate"
    }
}

private fun pingPillClass(pingText: String): String {
    val digits = pingText.filter { it.isDigit() }
    val ping = digits.toIntOrNull() ?: return "pill-neutral"
    return when {
        ping <= 90 -> "pill-live"
        ping <= 220 -> "pill-warm"
        else -> "pill-lag"
    }
}

private fun dashboardStyles(): String = """
    :root {
      color-scheme: dark;
      --bg-0: #0b1220;
      --bg-1: #10182b;
      --card: rgba(255,255,255,0.06);
      --stroke: rgba(255,255,255,0.09);
      --text: #ecf2ff;
      --muted: #93a4c6;
      --panel: rgba(18, 28, 56, 0.92);
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
      max-width: 1340px;
      margin: 0 auto;
      height: 100vh;
      padding: 16px 18px 18px;
      display: grid;
      grid-template-rows: auto auto 1fr;
      gap: 12px;
    }
    .hero-card,
    .metric-card,
    .card {
      border-radius: 30px;
      border: 1px solid var(--stroke);
      background: linear-gradient(135deg, rgba(26,40,80,0.96), rgba(19,30,60,0.92), rgba(15,23,42,0.92));
      backdrop-filter: blur(16px);
      box-shadow: 0 24px 56px rgba(0,0,0,0.24);
    }
    .hero-card { padding: 22px 24px; min-height: 270px; }
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
      margin-top: 14px;
      font-size: clamp(42px, 5.4vw, 70px);
      font-weight: 900;
      line-height: 0.96;
      letter-spacing: -0.05em;
    }
    .hero-pnl-row {
      margin-top: 12px;
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
    }
    .hero-pnl-chip {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 10px 16px;
      border-radius: 999px;
      background: rgba(45,216,129,0.12);
      color: #2dd881;
      border: 1px solid rgba(45,216,129,0.24);
      font-size: 16px;
      font-weight: 900;
    }
    .hero-chip-row {
      margin-top: 12px;
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
      align-items: center;
    }
    .hero-pnl {
      font-size: clamp(24px, 3.2vw, 34px);
      font-weight: 900;
      line-height: 1;
      color: #2dd881;
    }
    .hero-clock { min-width: 132px; }
    .hero-status {
      margin: 12px 0 0;
      color: #dbe7ff;
      font-size: 18px;
      line-height: 1.45;
    }
    .hero-card .pair-support-copy {
      margin-top: 10px;
      max-width: 95%;
      color: #9fbaea;
      font-size: 14px;
    }
    .row,
    .returns-grid {
      display: grid;
      gap: 14px;
    }
    .row-top {
      grid-template-columns: 1.22fr 1fr;
      align-items: stretch;
      min-height: 0;
    }
    .row-middle {
      grid-template-columns: 1.02fr 0.98fr;
      align-items: stretch;
      min-height: 0;
    }
    .row-bottom {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      min-height: 0;
      align-items: stretch;
      overflow: hidden;
    }
    .portfolio-card { display: grid; gap: 12px; align-content: start; }
    .portfolio-update {
      margin: 0;
      color: #dbe7ff;
      font-size: 15px;
      font-weight: 700;
    }
    .returns-grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .metric-card {
      padding: 14px 14px;
      display: grid;
      gap: 8px;
      background: rgba(255,255,255,0.035);
      border: 1px solid rgba(255,255,255,0.06);
      min-height: 118px;
    }
    .metric-card-gain .metric-value,
    .metric-card-gain .metric-caption { color: #2dd881; }
    .metric-card-loss .metric-value,
    .metric-card-loss .metric-caption { color: #ff6b7a; }
    .metric-label {
      color: #dbe7ff;
      font-size: 15px;
      font-weight: 700;
    }
    .metric-value {
      font-size: 28px;
      font-weight: 800;
    }
    .metric-caption {
      font-size: 18px;
      font-weight: 700;
    }
    .card { padding: 18px; min-height: 0; background: linear-gradient(135deg, rgba(24,34,66,0.96), rgba(17,27,49,0.92)); }
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
      font-size: 22px;
      line-height: 1.2;
    }
    .pair-focus-shell {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 14px;
      align-items: start;
      margin-bottom: 10px;
    }
    .pair-avatar {
      width: 56px;
      height: 56px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: linear-gradient(180deg, rgba(88,146,255,0.32), rgba(88,146,255,0.16));
      color: #84b8ff;
      font-size: 22px;
      font-weight: 900;
      border: 1px solid rgba(132,184,255,0.18);
      flex-shrink: 0;
    }
    .pair-focus-copy { min-width: 0; }
    .pair-hero {
      font-size: clamp(28px, 3vw, 42px);
      font-weight: 900;
      line-height: 1;
      letter-spacing: -0.04em;
      margin-bottom: 6px;
    }
    .pair-support-copy,
    .muted-copy {
      margin: 0;
      color: var(--muted);
      line-height: 1.35;
      font-size: 13px;
    }
    .radar-grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
      margin-top: 14px;
    }
    .radar-pill {
      min-height: 44px;
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
      padding: 10px 12px;
      border-radius: 18px;
      background: rgba(124, 92, 255, 0.16);
      border: 1px solid rgba(167, 139, 250, 0.18);
      color: #b799ff;
      font-size: 14px;
      font-weight: 800;
      letter-spacing: 0.01em;
    }
    .radar-pill-blue { background: rgba(96,165,250,0.16); color: #84b8ff; border-color: rgba(96,165,250,0.2); }
    .radar-pill-warm { background: rgba(250,204,21,0.12); color: #ffd85a; border-color: rgba(250,204,21,0.18); }
    .radar-pill-mint { background: rgba(45,216,129,0.12); color: #63e8aa; border-color: rgba(45,216,129,0.18); }
    .radar-pill-purple { background: rgba(183,153,255,0.16); color: #c3a8ff; border-color: rgba(183,153,255,0.18); }
    .radar-pill-slate { background: rgba(255,255,255,0.06); color: #dbe7ff; border-color: rgba(255,255,255,0.1); }
    .radar-pill-empty {
      color: var(--muted);
      background: rgba(255,255,255,0.04);
      border-color: rgba(255,255,255,0.05);
    }
    .allocation-shell {
      display: grid;
      grid-template-columns: 300px 1fr;
      gap: 20px;
      align-items: center;
      min-height: 0;
    }
    .allocation-chart-wrap {
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .allocation-chart {
      width: 240px;
      height: 240px;
      border-radius: 50%;
      display: grid;
      place-items: center;
      position: relative;
      background: rgba(255,255,255,0.05);
    }
    .allocation-chart::after {
      content: "";
      width: 132px;
      height: 132px;
      border-radius: 50%;
      background: linear-gradient(180deg, #151f40, #10182b);
      border: 1px solid rgba(255,255,255,0.08);
      position: absolute;
    }
    .allocation-center {
      position: relative;
      z-index: 1;
      display: grid;
      gap: 4px;
      text-align: center;
    }
    .allocation-center span {
      color: #dbe7ff;
      font-size: 16px;
      font-weight: 800;
    }
    .allocation-center strong {
      color: #ffffff;
      font-size: 30px;
      line-height: 1;
    }
    .allocation-legend {
      display: grid;
      gap: 12px;
      align-content: start;
      max-height: 260px;
      overflow-y: auto;
      padding-right: 4px;
    }
    .allocation-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      padding: 12px 14px;
      border-radius: 18px;
      background: rgba(255,255,255,0.045);
      border: 1px solid rgba(255,255,255,0.06);
    }
    .allocation-row-left {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;
    }
    .allocation-dot {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .allocation-code {
      font-size: 22px;
      font-weight: 800;
    }
    .allocation-pct {
      font-size: 28px;
      font-weight: 900;
    }
    .log-list {
      display: grid;
      gap: 10px;
    }
    .timeline-row {
      padding: 16px;
      border-radius: 24px;
      background: rgba(255,255,255,0.045);
      border: 1px solid rgba(255,255,255,0.06);
      line-height: 1.45;
      display: grid;
      gap: 10px;
    }
    .timeline-head,
    .trade-row-shell {
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 14px;
      align-items: start;
    }
    .timeline-head { grid-template-columns: auto 1fr; }
    .timeline-badge,
    .trade-side,
    .trade-status {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 94px;
      padding: 10px 14px;
      border-radius: 16px;
      font-size: 14px;
      font-weight: 900;
      letter-spacing: 0.03em;
    }
    .timeline-badge-status { color: #95b6ff; background: rgba(96,165,250,0.14); }
    .timeline-badge-rotasi { color: #ffd85a; background: rgba(250,204,21,0.14); }
    .timeline-badge-target { color: #c3a8ff; background: rgba(183,153,255,0.14); }
    .timeline-badge-hold { color: #84b8ff; background: rgba(96,165,250,0.14); }
    .timeline-badge-health { color: #63e8aa; background: rgba(45,216,129,0.14); }
    .timeline-badge-log { color: #dbe7ff; background: rgba(255,255,255,0.08); }
    .timeline-time,
    .trade-time {
      color: #dbe7ff;
      font-size: 15px;
      font-weight: 700;
      align-self: center;
    }
    .timeline-copy {
      color: var(--text);
      font-size: 18px;
    }
    .trade-side-buy { color: #2dd881; background: rgba(45,216,129,0.14); }
    .trade-side-sell { color: #ff9b7a; background: rgba(255,107,122,0.12); }
    .trade-side-hold { color: #95b6ff; background: rgba(96,165,250,0.12); }
    .trade-main {
      display: grid;
      gap: 6px;
      min-width: 0;
    }
    .trade-pair {
      font-size: 24px;
      font-weight: 900;
      line-height: 1;
    }
    .trade-detail {
      color: #dbe7ff;
      font-size: 18px;
    }
    .trade-status {
      color: #63e8aa;
      background: rgba(45,216,129,0.12);
    }
    .log-list {
      max-height: 100%;
      overflow-y: auto;
      font-family: "SF Pro Text", "Segoe UI", sans-serif;
      color: var(--muted);
      font-size: 12px;
      padding-right: 4px;
    }
    @media (max-width: 920px) {
      .row,
      .returns-grid,
      .allocation-shell {
        grid-template-columns: 1fr;
      }
      .hero-topbar {
        flex-direction: column;
      }
      .hero-topbar-right {
        align-items: flex-start;
      }
      .pair-focus-shell,
      .trade-row-shell {
        grid-template-columns: 1fr;
      }
      .allocation-chart {
        width: 210px;
        height: 210px;
      }
      .page-shell {
        height: auto;
        min-height: 100vh;
        padding: 18px 14px 28px;
        overflow-y: auto;
      }
      body { overflow: auto; }
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

private fun locateDashboardIcon(): File? {
    val cwd = File(System.getProperty("user.dir"))
    val candidates = listOf(
        File(cwd, "kibot-small.png"),
        File(cwd, "kibot.png"),
        File(cwd, "../../kibot-small.png"),
        File(cwd, "../../kibot.png"),
        File(cwd, "../kibot-small.png"),
        File(cwd, "../kibot.png"),
    )
    return candidates.firstOrNull { it.exists() }
}

@Serializable
private data class LanPingResponse(
    val ok: Boolean,
    val host: String,
    val port: Int,
    val lanProbeUrl: String?,
)
