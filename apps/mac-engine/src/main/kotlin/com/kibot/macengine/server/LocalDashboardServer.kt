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
                        unsafe { +"""<link rel="icon" type="image/png" href="/favicon.png?v=4">""" }
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
                                  label.textContent =  pingText;
                                }

                                function renderTimeline(entries) {
                                  const container = document.getElementById('log-lines');
                                  container.innerHTML = '';
                                  const freshEntries = (entries || [])
                                    .filter(entry => !entry.timestampEpochMs || (Date.now() - entry.timestampEpochMs) <= ${WEB_LOG_FRESHNESS_WINDOW_MS})
                                    .sort((a, b) => (b.timestampEpochMs || 0) - (a.timestampEpochMs || 0));
                                  if (!freshEntries || freshEntries.length === 0) {
                                    const empty = document.createElement('div');
                                    empty.className = 'empty-state';
                                    empty.innerHTML = '<div class="empty-title">Log server belum ada</div><div class="empty-copy">Status rotasi, target, dan fokus pair akan tampil otomatis di sini.</div>';
                                    container.appendChild(empty);
                                    return;
                                  }
                                  freshEntries
                                    .slice(0, 12)
                                    .forEach(entry => {
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
                                  const fallback = ['xrp_idr', 'doge_idr', 'trx_idr', 'pepe_idr', 'shib_idr', 'fartcoin_idr', 'jellyjelly_idr', 'sol_idr', 'btc_idr', 'arb_idr', 'plpa_idr'];
                                  const items = (pairs || []).filter(Boolean).map(pair => String(pair).toLowerCase()).slice(0, 9);
                                  const normalized = items.concat(fallback.filter(pair => !items.includes(pair))).slice(0, 9);
                                  normalized.forEach(pair => {
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
                                    const empty = document.createElement('div');
                                    empty.className = 'empty-state';
                                    empty.innerHTML = '<div class="empty-title">Trade history belum ada</div><div class="empty-copy">Eksekusi terbaru akan muncul di sini saat bot sudah isi order.</div>';
                                    container.appendChild(empty);
                                    return;
                                  }
                                  entries
                                    .slice()
                                    .sort((a, b) => (b.timestampEpochMs || 0) - (a.timestampEpochMs || 0))
                                    .slice(0, 10)
                                    .forEach(entry => {
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
                                      document.getElementById('hero-pnl').className = 'hero-pnl ' + (isNegativeTone(state.pnlTodayIdr, state.pnlTodayPctLabel) ? 'hero-pnl-loss' : 'hero-pnl-gain');
                                      document.getElementById('hero-pnl-pct').textContent = state.pnlTodayPctLabel;
                                      document.getElementById('hero-pnl-pct').className = 'hero-pnl-chip ' + (isNegativeTone(state.pnlTodayIdr, state.pnlTodayPctLabel) ? 'hero-pnl-chip-loss' : 'hero-pnl-chip-gain');
                                      document.getElementById('hero-update-time').textContent = state.lastUpdatedLabel;
                                      document.getElementById('last-updated').textContent = state.lastUpdatedLabel;
                                      const heroSummary = document.getElementById('hero-summary');
                                      if (heroSummary) heroSummary.textContent = state.statusMessage;
                                      document.getElementById('release-label').textContent = 'Oracle Active ' + (state.releaseLabel || '#0');
                                      document.getElementById('ai-chip').textContent = compactAiStatusLabel(state.aiProviderSummary || '');
                                      document.getElementById('ai-chip').className = 'pill ' + aiPillClass(state.aiProviderSummary || '');
                                      document.getElementById('target-chip').textContent = state.targetPursuitLabel || 'TRACKING';
                                      document.getElementById('target-chip').className = 'pill ' + targetPillClass(state.targetPursuitLabel || 'TRACKING');
                                      document.getElementById('top-candidate').textContent = state.topCandidate;
                                      document.getElementById('pair-temperature-label').textContent = pairHeatLabel(state.exchangePingMs || '--');
                                      document.getElementById('pair-temperature').className = 'pill ' + pingPillClass(state.exchangePingMs || '--');
                                      renderRadarPairs(state.radarPairs || []);
                                      document.getElementById('exchange-ping').textContent = state.exchangePingMs;
                                      document.getElementById('ret-1d').textContent = state.pnlTodayIdr;
                                      document.getElementById('ret-7d').textContent = state.return7dIdr;
                                      document.getElementById('ret-7d-pct').textContent = state.return7dPctLabel;
                                      document.getElementById('ret-30d').textContent = state.return30dIdr;
                                      document.getElementById('ret-30d-pct').textContent = state.return30dPctLabel;
                                      document.getElementById('ret-1d-pct').textContent = state.pnlTodayPctLabel;
                                      applyMetricTone('ret-1d-card', state.pnlTodayIdr, state.pnlTodayPctLabel);
                                      applyMetricTone('ret-7d-card', state.return7dIdr, state.return7dPctLabel);
                                      applyMetricTone('ret-30d-card', state.return30dIdr, state.return30dPctLabel);
                                      renderAssetAllocation(state.holdingsDetailed || [], state.portfolioValueIdr || 'Rp0');
                                      renderTradeHistory(state.recentOrders || []);
                                      renderTimeline(state.liveTimeline || []);
                                    })
                                    .catch(() => {});
                                }

                                refreshState();
                                setInterval(refreshState, ${minOf(statePollIntervalMillis, 5000L)});

                                function pingPillClass(pingText) {
                                  const pingValue = parseInt(String(pingText || '--').replace(/[^0-9]/g, ''), 10);
                                  if (Number.isNaN(pingValue)) return 'pill-neutral';
                                  if (pingValue <= 90) return 'pill-live';
                                  if (pingValue <= 220) return 'pill-warm';
                                  return 'pill-lag';
                                }

                                function pairHeatLabel(pingText) {
                                  const pingValue = parseInt(String(pingText || '--').replace(/[^0-9]/g, ''), 10);
                                  if (Number.isNaN(pingValue)) return 'LIVE';
                                  if (pingValue <= 90) return 'LIVE';
                                  if (pingValue <= 220) return 'WARM';
                                  return 'LAG';
                                }

                                function compactAiStatusLabel(summary) {
                                  const normalized = String(summary || '').toLowerCase();
                                  const healthy = normalized.includes('sehat:') || normalized.includes('healthy:');
                                  const limited = normalized.includes('limited');
                                  const skipped = normalized.includes('skip:') || normalized.includes('forbidden') || normalized.includes('failure');
                                  if (healthy && !limited && !skipped) return 'AI ONLINE';
                                  if (healthy) return 'AI LIMITED';
                                  if (skipped) return 'AI SKIP';
                                  return 'AI OFFLINE';
                                }

                                function aiPillClass(summary) {
                                  const label = compactAiStatusLabel(summary);
                                  if (label === 'AI ONLINE') return 'pill-live';
                                  if (label === 'AI LIMITED') return 'pill-warm';
                                  if (label === 'AI SKIP') return 'pill-lag';
                                  return 'pill-neutral';
                                }

                                function targetPillClass(label) {
                                  const value = String(label || '').toUpperCase();
                                  if (value === 'OVERDRIVE') return 'pill-safe';
                                  if (value === 'FULL_CHASE') return 'pill-lag';
                                  if (value === 'CHASE') return 'pill-live';
                                  if (value === 'LOCK_PROFIT') return 'pill-blue';
                                  return 'pill-neutral';
                                }

                                function isNegativeTone(value, caption) {
                                  return String(value || '').trim().startsWith('-') || String(caption || '').trim().startsWith('-');
                                }

                                function applyMetricTone(id, value, caption) {
                                  const element = document.getElementById(id);
                                  if (!element) return;
                                  const negative = isNegativeTone(value, caption);
                                  element.className = 'metric-card ' + (negative ? 'metric-card-loss' : 'metric-card-gain');
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
                val freshCutoff = System.currentTimeMillis() - WEB_LOG_FRESHNESS_WINDOW_MS
                call.respond(
                    repository.state.value.liveTimeline
                        .filter { it.timestampEpochMs <= 0L || it.timestampEpochMs >= freshCutoff }
                        .sortedByDescending { it.timestampEpochMs }
                        .map { "${it.category} • ${it.message}" },
                )
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
        div("column column-left") {
            div("hero-card") {
                div("hero-topbar") {
                    h1 { +"KiBot" }
                    div("hero-topbar-right") {
                        div("pill pill-neutral") {
                            attributes["id"] = "status-badge"
                            span("wifi-icon") { }
                            span {
                                attributes["id"] = "status-badge-label"
                                +state.exchangePingMs
                            }
                        }
                        p("hero-update") {
                            +"Update "
                            span {
                                attributes["id"] = "hero-update-time"
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
                    span(if (isNegativeTone(state.pnlTodayIdr, state.pnlTodayPctLabel)) "hero-pnl hero-pnl-loss" else "hero-pnl hero-pnl-gain") {
                        attributes["id"] = "hero-pnl"
                        +state.pnlTodayIdr
                    }
                    span(if (isNegativeTone(state.pnlTodayIdr, state.pnlTodayPctLabel)) "hero-pnl-chip hero-pnl-chip-loss" else "hero-pnl-chip hero-pnl-chip-gain") {
                        attributes["id"] = "hero-pnl-pct"
                        +state.pnlTodayPctLabel
                    }
                }
                div("hero-chip-strip") {
                    span("pill pill-live") {
                        attributes["id"] = "release-label"
                        +"Oracle Active ${state.releaseLabel}"
                    }
                    span("pill ${aiPillClass(state.aiProviderSummary)}") {
                        attributes["id"] = "ai-chip"
                        +compactAiStatusLabel(state.aiProviderSummary)
                    }
                    span("pill ${targetPillClass(state.targetPursuitLabel)}") {
                        attributes["id"] = "target-chip"
                        +state.targetPursuitLabel
                    }
                    span("pill pill-blue hero-clock") {
                        attributes["id"] = "last-updated"
                        +state.lastUpdatedLabel
                    }
                }
            }

            div("card portfolio-card") {
                div("card-header-row") {
                    h2 { +"Portfolio" }
                    p("portfolio-update") { +state.lastUpdatedLabel }
                }
                div("returns-grid") {
                    metricCard("Return 1D", state.pnlTodayIdr, state.pnlTodayPctLabel, "ret-1d", "ret-1d-pct")
                    metricCard("Return 7D", state.return7dIdr, state.return7dPctLabel, "ret-7d", "ret-7d-pct")
                    metricCard("Return 30D", state.return30dIdr, state.return30dPctLabel, "ret-30d", "ret-30d-pct")
                }
            }

            div("card activity-card logs-card") {
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

        div("column column-right") {
            div("card live-pair-card") {
                div("card-header-row") {
                    h2 { +"Live Pair" }
                    div("pill ${pingPillClass(state.exchangePingMs)}") {
                        attributes["id"] = "pair-temperature"
                        span {
                            attributes["id"] = "pair-temperature-label"
                            +pairHeatLabel(state.exchangePingMs)
                        }
                    }
                }
                div("pair-focus-shell") {
                    div("pair-avatar") { +state.topCandidate.take(2).uppercase() }
                    div("pair-focus-copy") {
                        div("pair-hero") {
                            attributes["id"] = "top-candidate"
                            +state.topCandidate
                        }
                    }
                }
                div("radar-grid") {
                    attributes["id"] = "radar-grid"
                    filledRadarPairs(state.radarPairs)
                        .forEach { pair ->
                            div("radar-pill ${radarPillClass(pair)}") { +pair.lowercase() }
                        }
                }
            }

            div("card allocation-card") {
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

            div("card activity-card trade-card") {
                div("card-header-row") {
                    h2 { +"Trade History" }
                    div("pill pill-neutral") { +"Live" }
                }
                div("log-list") {
                    attributes["id"] = "trade-lines"
                    p("muted-copy") { +"Loading trade history..." }
                }
            }
        }
    }
}

private fun FlowContent.metricCard(label: String, value: String, caption: String, valueId: String, captionId: String) {
    div("metric-card ${metricCardClass(value, caption)}") {
        attributes["id"] = "${valueId}-card"
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

private fun metricCardClass(value: String, caption: String): String =
    if (isNegativeTone(value, caption)) "metric-card-loss" else "metric-card-gain"

private fun filledRadarPairs(pairs: List<String>): List<String> {
    val fallback = listOf(
        "xrp_idr",
        "doge_idr",
        "trx_idr",
        "pepe_idr",
        "shib_idr",
        "fartcoin_idr",
        "jellyjelly_idr",
        "sol_idr",
        "btc_idr",
        "arb_idr",
        "plpa_idr",
    )
    return (pairs.map { it.lowercase() } + fallback)
        .filter { it.isNotBlank() && it != "--" }
        .distinct()
        .take(9)
}

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

private fun pairHeatLabel(pingText: String): String {
    val digits = pingText.filter { it.isDigit() }
    val ping = digits.toIntOrNull() ?: return "LIVE"
    return when {
        ping <= 90 -> "LIVE"
        ping <= 220 -> "WARM"
        else -> "LAG"
    }
}

private fun isNegativeTone(value: String, caption: String): Boolean =
    value.trim().startsWith("-") || caption.trim().startsWith("-")

private fun compactAiStatusLabel(summary: String): String {
    val normalized = summary.lowercase()
    val healthy = "sehat:" in normalized || "healthy:" in normalized
    val limited = "limited" in normalized
    val skipped = "skip:" in normalized || "forbidden" in normalized || "failure" in normalized
    return when {
        healthy && !limited && !skipped -> "AI ONLINE"
        healthy -> "AI LIMITED"
        skipped -> "AI SKIP"
        else -> "AI OFFLINE"
    }
}

private fun aiPillClass(summary: String): String = when (compactAiStatusLabel(summary)) {
    "AI ONLINE" -> "pill-live"
    "AI LIMITED" -> "pill-warm"
    "AI SKIP" -> "pill-lag"
    else -> "pill-neutral"
}

private fun targetPillClass(label: String): String = when (label.uppercase()) {
    "OVERDRIVE" -> "pill-safe"
    "FULL_CHASE" -> "pill-lag"
    "CHASE" -> "pill-live"
    "LOCK_PROFIT" -> "pill-blue"
    else -> "pill-neutral"
}

private const val WEB_LOG_FRESHNESS_WINDOW_MS = 2 * 60 * 60 * 1000L

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
      padding: 12px 14px 14px;
      display: grid;
      grid-template-columns: minmax(0, 1.18fr) minmax(360px, 0.92fr);
      gap: 10px;
      align-items: stretch;
    }
    .column {
      min-height: 0;
      height: 100%;
      display: grid;
      gap: 10px;
      align-content: start;
      align-self: stretch;
    }
    .column-left {
      grid-template-rows: auto auto minmax(320px, 1fr);
    }
    .column-right {
      grid-template-rows: auto auto minmax(0, 1fr);
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
    .wifi-icon {
      width: 16px;
      height: 16px;
      margin-right: 6px;
      display: inline-block;
      flex: 0 0 16px;
      background-repeat: no-repeat;
      background-position: center;
      background-size: contain;
      background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23ffffff' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M5 12.55a11 11 0 0 1 14.08 0'/%3E%3Cpath d='M8.53 16.11a6 6 0 0 1 6.95 0'/%3E%3Cpath d='M12 20h.01'/%3E%3Cpath d='M2 8.82a16 16 0 0 1 20 0'/%3E%3C/svg%3E");
    }
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
      font-size: 16px;
      font-weight: 900;
    }
    .hero-pnl-chip-gain {
      background: rgba(45,216,129,0.12);
      color: #2dd881;
      border: 1px solid rgba(45,216,129,0.24);
    }
    .hero-pnl-chip-loss {
      background: rgba(248,113,113,0.12);
      color: #ff6b7a;
      border: 1px solid rgba(248,113,113,0.24);
    }
    .hero-chip-strip {
      margin-top: 12px;
      display: flex;
      gap: 10px;
      justify-content: space-between;
      align-items: center;
      flex-wrap: nowrap;
    }
    .hero-chip-strip .pill {
      flex: 1 1 0;
      min-width: 0;
      padding: 8px 10px;
      font-size: 12px;
    }
    .hero-pnl {
      font-size: clamp(24px, 3.2vw, 34px);
      font-weight: 900;
      line-height: 1;
    }
    .hero-pnl-gain { color: #2dd881; }
    .hero-pnl-loss { color: #ff6b7a; }
    .hero-clock { min-width: 0; }
    .hero-card .pair-support-copy {
      margin-top: 10px;
      max-width: 95%;
      color: #9fbaea;
      font-size: 14px;
    }
    .returns-grid {
      display: grid;
      gap: 12px;
    }
    .portfolio-card { display: grid; gap: 4px; align-content: start; padding: 10px 12px; }
    .portfolio-update {
      margin: 0;
      color: #dbe7ff;
      font-size: 14px;
      font-weight: 700;
    }
    .returns-grid {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .metric-card {
      padding: 8px 10px;
      display: grid;
      gap: 2px;
      background: rgba(255,255,255,0.035);
      border: 1px solid rgba(255,255,255,0.06);
      min-height: 74px;
      border-radius: 22px;
    }
    .metric-card-gain .metric-value,
    .metric-card-gain .metric-caption { color: #2dd881; }
    .metric-card-loss .metric-value,
    .metric-card-loss .metric-caption { color: #ff6b7a; }
    .metric-label {
      color: #dbe7ff;
      font-size: 14px;
      font-weight: 700;
    }
    .metric-value {
      font-size: 20px;
      font-weight: 800;
    }
    .metric-caption {
      font-size: 15px;
      font-weight: 700;
    }
    .card { padding: 16px; min-height: 0; background: linear-gradient(135deg, rgba(24,34,66,0.96), rgba(17,27,49,0.92)); }
    .live-pair-card { min-height: 0; }
    .activity-card { min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr); overflow: hidden; padding: 18px; }
    .logs-card { height: 100%; align-self: stretch; }
    .trade-card { height: 100%; }
    .card-header-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      margin-bottom: 8px;
    }
    .card h2 {
      margin: 0;
      font-size: 20px;
      line-height: 1.2;
    }
    .pair-focus-shell {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 12px;
      align-items: center;
      margin: 4px 0 10px;
      padding: 4px 2px 0;
    }
    .pair-avatar {
      width: 50px;
      height: 50px;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: linear-gradient(180deg, rgba(88,146,255,0.32), rgba(88,146,255,0.16));
      color: #84b8ff;
      font-size: 19px;
      font-weight: 900;
      border: 1px solid rgba(132,184,255,0.18);
      flex-shrink: 0;
    }
    .pair-focus-copy { min-width: 0; }
    .pair-hero {
      font-size: clamp(20px, 2vw, 32px);
      font-weight: 900;
      line-height: 1;
      letter-spacing: -0.04em;
      margin-bottom: 0;
    }
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
      margin-top: 2px;
    }
    .radar-pill {
      min-height: 50px;
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
      padding: 8px 10px;
      border-radius: 16px;
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
      color: transparent;
      background: rgba(255,255,255,0.035);
      border-color: rgba(255,255,255,0.04);
      box-shadow: inset 0 0 0 1px rgba(255,255,255,0.02);
    }
    .allocation-shell {
      display: grid;
      grid-template-columns: 220px 1fr;
      gap: 16px;
      align-items: center;
      min-height: 0;
    }
    .allocation-chart-wrap {
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .allocation-chart {
      width: 180px;
      height: 180px;
      border-radius: 50%;
      display: grid;
      place-items: center;
      position: relative;
      background: rgba(255,255,255,0.05);
    }
    .allocation-chart::after {
      content: "";
      width: 96px;
      height: 96px;
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
      min-height: 0;
      height: 100%;
      gap: 8px;
      align-content: start;
      overflow-y: auto;
      overscroll-behavior: contain;
      -webkit-overflow-scrolling: touch;
      padding-right: 4px;
    }
    .timeline-row {
      padding: 14px;
      border-radius: 20px;
      background: rgba(255,255,255,0.045);
      border: 1px solid rgba(255,255,255,0.06);
      line-height: 1.45;
      display: grid;
      gap: 6px;
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
    .empty-state {
      min-height: 100%;
      display: grid;
      align-content: center;
      gap: 8px;
      padding: 18px;
      border-radius: 20px;
      background: rgba(255,255,255,0.035);
      border: 1px solid rgba(255,255,255,0.05);
      text-align: left;
    }
    .empty-title {
      color: #dbe7ff;
      font-size: 18px;
      font-weight: 800;
    }
    .empty-copy {
      color: var(--muted);
      font-size: 14px;
      line-height: 1.45;
    }
    .log-list {
      font-family: "SF Pro Text", "Segoe UI", sans-serif;
      color: var(--muted);
      font-size: 12px;
    }
    @media (max-width: 920px) {
      .page-shell,
      .column,
      .returns-grid,
      .allocation-shell {
        grid-template-columns: 1fr;
      }
      .column-left,
      .column-right {
        grid-template-rows: auto;
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
      .hero-chip-strip {
        flex-wrap: wrap;
      }
      .radar-pill {
        min-height: 58px;
        font-size: 16px;
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
        File("/home/ubuntu/KiBot/kibot-small.png"),
        File("/home/ubuntu/KiBot/kibot.png"),
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
