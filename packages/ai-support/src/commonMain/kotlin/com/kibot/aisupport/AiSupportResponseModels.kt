package com.kibot.aisupport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class AiSupportResponse(
    val pairs: List<AiSupportResponseItem> = emptyList(),
)

@Serializable
internal data class AiSupportResponseItem(
    @SerialName("pair_id") val pairId: String,
    @SerialName("support_bias") val supportBias: Double = 0.0,
    @SerialName("caution_bias") val cautionBias: Double = 0.0,
    @SerialName("cheap_nominal_watch") val cheapNominalWatch: Boolean = false,
    val rationale: String = "",
)
