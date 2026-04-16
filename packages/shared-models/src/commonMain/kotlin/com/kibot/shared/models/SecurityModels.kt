package com.kibot.shared.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class EncryptedCredentialBundle(
    val botId: BotId,
    val cipherVersion: String,
    val kdfAlgorithm: String,
    val kdfParamsJson: String,
    val secretBundleCiphertext: String,
    val secretBundleNonce: String,
    val secretBundleSalt: String,
    val updatedAt: Instant? = null,
)
