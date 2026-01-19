package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class SaveProfileResponse (
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("profileId")
    val profileId: String? = null,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("error")
    val error: String? = null
)