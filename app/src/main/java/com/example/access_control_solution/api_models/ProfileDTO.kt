package com.example.access_control_solution.api_models

import com.google.gson.annotations.SerializedName

data class ProfileDTO(
    @SerializedName("id")
    val id: String? = null,

    @SerializedName("name")
    val name: String,

    @SerializedName("lagId")
    val lagId: String,

    @SerializedName("faceTemplate")
    val faceTemplate: String, // Base64 encoded

    @SerializedName("faceImage")
    val faceImage: String?, // Base64 encoded

    @SerializedName("thumbnail")
    val thumbnail: String? = null, // Base64 encoded

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()

)