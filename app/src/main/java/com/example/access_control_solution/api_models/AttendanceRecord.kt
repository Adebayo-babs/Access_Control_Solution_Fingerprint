package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class AttendanceRecord(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("lagId")
    val lagId: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("date")
    val date: String,

    @SerializedName("sessions")
    val sessions: List<AttendanceSession>? = null,

    @SerializedName("totalDuration")
    val totalDuration: Long? = null,

    @SerializedName("totalDurationFormatted")
    val totalDurationFormatted: String? = null,

    @SerializedName("status")
    val status: String, // "PRESENT" or "COMPLETED"

    // Backward compatibility fields
    @SerializedName("clockIn")
    val clockIn: Long? = null,

    @SerializedName("clockInTime")
    val clockInTime: String? = null,

    @SerializedName("clockOut")
    val clockOut: Long? = null,

    @SerializedName("clockOutTime")
    val clockOutTime: String? = null,
)

data class AttendanceResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("records")
    val records: List<AttendanceRecord>,

    @SerializedName("total")
    val total: Int,

    @SerializedName("page")
    val page: Int,

    @SerializedName("totalPages")
    val totalPages: Int
)

data class AttendanceListResponse(
    val success: Boolean,
    val records: List<AttendanceRecord>,
    val total: Int,
    val page: Int,
    val totalPages: Int
)

data class AttendanceSession(
    @SerializedName("clockIn")
    val clockIn: Long?,
    @SerializedName("clockInTime")
    val clockInTime: String?,
    @SerializedName("clockOut")
    val clockOut: Long?,
    @SerializedName("clockOutTime")
    val clockOutTime: String?,
    @SerializedName("duration")
    val duration: Long?,
    @SerializedName("durationFormatted")
    val durationFormatted: String?
)
