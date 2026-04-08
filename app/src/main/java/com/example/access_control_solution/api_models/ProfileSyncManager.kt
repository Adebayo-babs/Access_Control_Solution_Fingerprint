package com.example.access_control_solution.api_models

import android.content.Context
import com.example.access_control_solution.data.AppDatabase
import com.example.access_control_solution.data.ProfileEntity
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Error response data class for parsing server errors
private data class ErrorResponse(
    val success: Boolean,
    val error: String?,
    val duplicateType: String? // LAG_ID or FACE
)

class ProfileSyncManager(private val context: Context) {

    private val apiService = RetrofitClient.apiService
    private val db = AppDatabase.getInstance(context)
    private val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "ProfileSyncManager"
        private const val PREF_LAST_SYNC = "last_sync_timestamp"
        private const val PREF_USE_SERVER = "use_server_as_source"
    }

    // Convert ProfileEntity to ProfileDTO
    private fun entityToDTO(entity: ProfileEntity, includeImages: Boolean = true): ProfileDTO {
        return ProfileDTO(
            name = entity.name,
            lagId = entity.lagId,
            faceTemplate = Base64.encodeToString(entity.faceTemplate, Base64.NO_WRAP),
            faceImage = if (includeImages) Base64.encodeToString(entity.faceImage, Base64.NO_WRAP) else null,
            thumbnail = entity.thumbnail?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            fingerprintTemplate = entity.fingerprintTemplate?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
            timestamp = entity.timestamp
        )
    }

    // Convert ProfileDTO to ProfileEntity
    private fun dtoToEntity(dto: ProfileDTO): ProfileEntity {
        return ProfileEntity(
            name = dto.name,
            lagId = dto.lagId,
            faceTemplate = Base64.decode(dto.faceTemplate, Base64.NO_WRAP),
            faceImage = dto.faceImage?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(0),
            thumbnail = dto.thumbnail?.let { Base64.decode(it, Base64.NO_WRAP) },
            fingerprintTemplate = dto.fingerprintTemplate?.let { Base64.decode(it, Base64.NO_WRAP) },
            timestamp = dto.timestamp
        )
    }

    // Check server connectivity
    suspend fun isServerAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.healthCheck()
                response.isSuccessful
            } catch (e: Exception) {
                Log.e(TAG, "Server not available", e)
                false
            }
        }
    }

    // Save profile to server with duplicate checking
    suspend fun saveProfileToServer(profile: ProfileEntity): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val profileDTO = entityToDTO(profile, includeImages = true)
                val response = apiService.saveProfile(profileDTO)

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.success == true) {
                        val profileId = body.profileId ?: ""
                        Log.d(TAG, "Profile saved to server successfully: $profileId")
                        Result.success(profileId)
                    } else {
                        // Server returned success: false in the body
                        val errorMsg = body?.error ?: "Unknown server error"
                        Log.e(TAG, "Server returned error in body: $errorMsg")
                        Result.failure(Exception(errorMsg))
                    }
                } else {
                    // HTTP error response (like 409 Conflict)
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "HTTP ${response.code()} error. Body: $errorBody")

                    if (errorBody != null) {
                        try {
                            // Parse the error JSON to get the detailed error message
                            val gson = com.google.gson.Gson()
                            val errorResponse = gson.fromJson(errorBody, ErrorResponse::class.java)

                            val errorMessage = errorResponse.error ?: "Server error: ${response.code()}"
                            Log.e(TAG, "Parsed error message: $errorMessage")

                            // Return the detailed error message
                            Result.failure(Exception(errorMessage))
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse error body", e)
                            // If parsing fails, return the raw error body
                            Result.failure(Exception("Server error: ${response.code()} - $errorBody"))
                        }
                    } else {
                        Result.failure(Exception("Server error: ${response.code()}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving profile to server", e)
                Result.failure(e)
            }
        }
    }

    // Load all profiles from server and replace local database
    suspend fun loadAllProfilesFromServer(): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading all profiles from server...")

                val response = apiService.getAllProfiles(
                    includeImages = true,
                    includeThumbnails = true
                )

                if (response.isSuccessful && response.body()?.success == true) {
                    val profiles = response.body()?.profiles ?: emptyList()
                    val serverTimestamp = response.body()?.serverTimestamp ?: System.currentTimeMillis()

                    Log.d(TAG, "Received ${profiles.size} profiles from server")

                    // Clear local database
                    db.profileDao().deleteAll()

                    val entities = profiles.mapNotNull { dto ->
                        try {
                            if (dto.faceImage == null) return@mapNotNull  null
                            dtoToEntity(dto)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting profile: ${dto.name}", e)
                            null
                        }
                    }

                    // Insert all
                    if (entities.isNotEmpty()) {
                        db.profileDao().insertAll(entities)
                    }

                    // Update last sync timestamp
                    prefs.edit().putLong(PREF_LAST_SYNC, serverTimestamp).apply()

                    Log.d(TAG, "Loaded ${entities.size} profiles from server")
                    Result.success(entities.size)
                } else {
                    Result.failure(Exception("Failed to load profiles from server"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading profiles from server", e)
                Result.failure(e)
            }
        }
    }

    // Delete profile from server
    suspend fun deleteProfileFromServer(lagId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.deleteProfileByLagId(lagId)

                if (response.isSuccessful && response.body()?.success == true) {
                    Log.d(TAG, "Profile deleted from server")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Delete failed"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting profile from server", e)
                Result.failure(e)
            }
        }
    }

    // Get profile count from server
    suspend fun getServerProfileCount(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.getProfilesCount()
                if (response.isSuccessful && response.body()?.success == true) {
                    response.body()?.data ?: 0
                } else {
                    0
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting profile count", e)
                0
            }
        }
    }

    // Get last sync timestamp
    fun getLastSyncTimestamp(): Long {
        return prefs.getLong(PREF_LAST_SYNC, 0)
    }

    // Set to use server as primary source
    fun setUseServerAsSource(use: Boolean) {
        prefs.edit().putBoolean(PREF_USE_SERVER, use).apply()
    }

    fun shouldUseServerAsSource(): Boolean {
        return prefs.getBoolean(PREF_USE_SERVER, true)
    }
}