package com.kibot.commandcenter.data.repository

import com.kibot.commandcenter.data.model.CommandCenterUiState
import com.kibot.commandcenter.data.model.DashboardTab
import com.kibot.commandcenter.data.model.ConsoleRole
import com.kibot.commandcenter.data.remote.CommandCenterWebSocketManager
import com.kibot.shared.models.CommandCenterCommandReply
import kotlinx.coroutines.flow.StateFlow

class CommandCenterRepository(
    private val store: CommandCenterStore,
    private val websocketManager: CommandCenterWebSocketManager,
) {
    val uiState: StateFlow<CommandCenterUiState> = store.uiState

    fun start(kidaxWsUrl: String) {
        websocketManager.start(kidaxWsUrl)
    }

    suspend fun sendConsoleCommand(serverWsUrl: String, command: String, argument: String? = null): CommandCenterCommandReply {
        store.appendConsole(ConsoleRole.USER, command)
        val reply = websocketManager.sendCommand(serverWsUrl, command, argument)
        store.appendConsole(
            if (reply.accepted) ConsoleRole.SYSTEM else ConsoleRole.ERROR,
            reply.message,
        )
        return reply
    }

    fun selectTab(tab: DashboardTab) = store.setSelectedTab(tab)
}
