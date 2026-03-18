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
import kotlinx.html.form
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
import java.nio.file.Files
import java.nio.file.Path

class LocalDashboardServer(
    private val repository: MacStateRepository,
    private val dispatchCommand: suspend (MacCommand) -> Unit,
    private val port: Int = 8787,
    private val androidReleaseDirectory: Path,
) {
    private val server = embeddedServer(CIO, port = port) {
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
                        renderDashboard(repository.state.value)
                        script {
                            unsafe {
                                +"""
                                setInterval(() => {
                                  fetch('/api/state')
                                    .then(r => r.json())
                                    .then(state => {
                                      document.getElementById('status-message').textContent = state.statusMessage;
                                      document.getElementById('active-engine').textContent = state.activeEngine;
                                      document.getElementById('standby-engine').textContent = state.standbyEngine;
                                      document.getElementById('sync-health').textContent = state.syncHealth;
                                      document.getElementById('operating-mode').textContent = state.operatingMode;
                                      document.getElementById('edge-confidence').textContent = state.edgeConfidence;
                                      document.getElementById('market-regime').textContent = state.marketRegime;
                                      document.getElementById('top-candidate').textContent = state.topCandidate;
                                      document.getElementById('live-execution').textContent = state.liveExecutionEnabled ? 'ENABLED' : 'SHADOW';
                                      document.getElementById('lease-term').textContent = '#' + state.leaseTerm;
                                      document.getElementById('heartbeat').textContent = state.lastHeartbeatLabel;
                                      document.getElementById('health-summary').textContent = state.healthSummary;
                                      document.getElementById('weekly-learning').textContent = state.weeklyLearningSummary;
                                      document.getElementById('weekly-adaptation').textContent = state.weeklyAdaptationSummary;
                                      document.getElementById('bot-running').textContent = state.isBotRunning ? 'RUNNING' : 'STOPPED';
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

    fun start() = server.start(wait = true)

    fun stop() {
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
    else -> error("Unknown action: $this")
}

private fun kotlinx.html.BODY.renderDashboard(state: MacDashboardState) {
    div("page-shell") {
        div("hero") {
            div("hero-copy") {
                h1 { +"KiBot Mac Engine" }
                p {
                    attributes["id"] = "status-message"
                    +state.statusMessage
                }
            }
            div("hero-actions") {
                commandButton("Start Bot", "START_BOT")
                commandButton("Stop Bot", "STOP_BOT")
                commandButton("Request Takeover", "REQUEST_TAKEOVER")
                commandButton("Force Safe Takeover", "FORCE_SAFE_TAKEOVER")
                commandButton("Release Control", "RELEASE_CONTROL")
                commandButton("Sync Status Now", "SYNC_NOW")
            }
        }

        div("grid") {
            div("card accent") {
                h2 { +"Control Plane" }
                statusLine("Bot", state.isBotRunningLabel(), "bot-running")
                statusLine("Active Engine", state.activeEngine, "active-engine")
                statusLine("Standby Engine", state.standbyEngine, "standby-engine")
                statusLine("Sync", state.syncHealth, "sync-health")
            }
            div("card") {
                h2 { +"Strategy Brain" }
                statusLine("Mode", state.operatingMode, "operating-mode")
                statusLine("Edge", state.edgeConfidence, "edge-confidence")
                statusLine("Regime", state.marketRegime, "market-regime")
                statusLine("Top Candidate", state.topCandidate, "top-candidate")
                statusLine("Execution", if (state.liveExecutionEnabled) "ENABLED" else "SHADOW", "live-execution")
            }
            div("card") {
                h2 { +"Lease & Health" }
                statusLine("Lease Term", "#${state.leaseTerm}", "lease-term")
                statusLine("Last Heartbeat", state.lastHeartbeatLabel, "heartbeat")
                statusLine("Health", state.healthSummary, "health-summary")
            }
            div("card") {
                h2 { +"Weekly Learning" }
                statusLine("Summary", state.weeklyLearningSummary, "weekly-learning")
                statusLine("Adaptation", state.weeklyAdaptationSummary, "weekly-adaptation")
            }
            div("card") {
                h2 { +"Safety Notes" }
                p { +"Mac engine only takes control after lease expiry, term validation, and reconciliation." }
                p { +"If conflict or ambiguous order state is detected, the design requires safe mode and blocks new entries." }
            }
            div("card") {
                h2 { +"Android Update Feed" }
                p { +"Latest signed APK is served locally from the laptop when available." }
                p { +"Manifest: /api/releases/android/latest" }
                p { +"APK: /releases/android/kibot-android-latest.apk" }
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

private fun FlowContent.commandButton(label: String, action: String) {
    form(action = "/command", method = kotlinx.html.FormMethod.post, classes = "command-form") {
        button(type = kotlinx.html.ButtonType.submit, classes = "command-button") {
            attributes["name"] = "action"
            attributes["value"] = action
            +label
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
    .hero-actions {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
      align-content: start;
    }
    .command-form { margin: 0; }
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
    .grid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
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
    @media (max-width: 900px) {
      .hero, .grid {
        grid-template-columns: 1fr;
      }
    }
""".trimIndent()
