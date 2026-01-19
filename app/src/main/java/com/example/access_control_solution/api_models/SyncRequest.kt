package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class SyncRequest(
    @SerializedName("lastSync")
    val lastSync: Long? = null,

    @SerializedName("localProfiles")
    val localProfiles: List<String> = emptyList()
)
