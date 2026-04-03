package com.kibot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kibot.android.data.ServerConfig

@Composable
fun SettingsScreen(
    currentConfig: ServerConfig,
    onSave: (ServerConfig) -> Unit,
    onBack: () -> Unit
) {
    var host by remember { mutableStateOf(currentConfig.host) }
    var port by remember { mutableStateOf(currentConfig.port.toString()) }
    var isSaving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurfaceVariant)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = PrimaryBlue,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                "Settings",
                fontSize = 20.sp,
                color = LightText,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                "Server Configuration",
                fontSize = 16.sp,
                color = LightText,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Server Host
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Server Host/IP") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LightText,
                    unfocusedTextColor = LightText,
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = SecondaryText,
                    focusedLabelColor = PrimaryBlue,
                    unfocusedLabelColor = SecondaryText,
                    cursorColor = PrimaryBlue
                ),
                singleLine = true
            )

            // Server Port
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Server Port") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LightText,
                    unfocusedTextColor = LightText,
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = SecondaryText,
                    focusedLabelColor = PrimaryBlue,
                    unfocusedLabelColor = SecondaryText,
                    cursorColor = PrimaryBlue
                ),
                singleLine = true
            )

            // Message
            if (message.isNotEmpty()) {
                Text(
                    message,
                    fontSize = 12.sp,
                    color = if (message.contains("saved", ignoreCase = true)) ProfitGreen else LossRed,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // Save Button
            Button(
                onClick = {
                    try {
                        val portNum = port.toIntOrNull()
                        if (host.isNotBlank() && portNum != null && portNum > 0) {
                            isSaving = true
                            val newConfig = ServerConfig(host.trim(), portNum)
                            onSave(newConfig)
                            message = "Settings saved!"
                            isSaving = false
                        } else {
                            message = "Invalid host or port"
                        }
                    } catch (e: Exception) {
                        message = "Error: ${e.message}"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save Settings", color = Color.White)
                }
            }

            // Default Notice
            Text(
                "Default: localhost:8787",
                fontSize = 12.sp,
                color = SecondaryText,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
