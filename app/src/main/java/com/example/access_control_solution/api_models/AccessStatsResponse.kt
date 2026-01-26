package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class AccessStatsResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: AccessStats
)

data class AccessStats(
    @SerializedName("totalAttempts")
    val totalAttempts: Int,

    @SerializedName("grantedAccess")
    val grantedAccess: Int,

    @SerializedName("deniedAccess")
    val deniedAccess: Int,

    @SerializedName("successRate")
    val successRate: String,

    @SerializedName("topDeniedUsers")
    val topDeniedUsers: List<DeniedUser>
)

data class DeniedUser(
    @SerializedName("lagId")
    val lagId: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("deniedCount")
    val deniedCount: Int
)
