package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName


data class TodayAttendanceResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: TodayAttendance
)

data class TodayAttendance(
    @SerializedName("date")
    val date: String,

    @SerializedName("totalPresent")
    val totalPresent: Int,

    @SerializedName("clockedIn")
    val clockedIn: Int,

    @SerializedName("clockedOut")
    val clockedOut: Int,

    @SerializedName("records")
    val records: List<AttendanceRecord>
)