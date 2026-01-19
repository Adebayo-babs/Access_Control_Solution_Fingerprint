package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class ProfilesResponse (
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("profiles")
    val profiles: List<ProfileDTO>,

    @SerializedName("total")
    val total: Int,

    @SerializedName("serverTimestamp")
    val serverTimestamp: Long
)