package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class ClockRequest(
    @SerializedName("lagId")
    val lagId: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("action")
    val action: String // "IN" or "OUT"
)

data class ClockInOutResponse(
    val success: Boolean,
    val message: String,
    val attendance: AttendanceRecord?
)

data class ClockResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
    val attendance: AttendanceRecord? = null
)

