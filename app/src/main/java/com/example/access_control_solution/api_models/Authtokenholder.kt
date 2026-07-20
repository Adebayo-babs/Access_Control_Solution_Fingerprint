package com.example.access_control_solution.api_models

// Holds the current admin auth token in memory so RetrofitClient's interceptor can
// attach it to requests without needing an Android Context inside the network layer.

object AuthTokenHolder {
    @Volatile
    var token: String? = null
}
