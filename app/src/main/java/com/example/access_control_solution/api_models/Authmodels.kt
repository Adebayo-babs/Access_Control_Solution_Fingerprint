package com.example.access_control_solution.api_models


data class LoginRequest(
    val username: String,
    val password: String
)

data class AdminDto(
    val id: String,
    val username: String,
    val email: String
)

data class LoginResponse(
    val success: Boolean,
    val message: String? = null,
    val token: String? = null,
    val admin: AdminDto? = null,
    val error: String? = null
)

data class VerifyResponse(
    val success: Boolean,
    val admin: AdminDto? = null,
    val error: String? = null
)