package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class AccessLog(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("lagId")
    val lagId: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("accessGranted")
    val accessGranted: Boolean,

    @SerializedName("accessType")
    val accessType: String, // "CARD" or "FACE"

    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("timestamp")
    val timestamp: Long,

    @SerializedName("date")
    val date: String,

    @SerializedName("time")
    val time: String

)

data class AccessLogResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("logs")
    val logs: List<AccessLog>,

    @SerializedName("total")
    val total: Int,

    @SerializedName("page")
    val page: Int,

    @SerializedName("totalPages")
    val totalPages: Int
)
