package com.example.access_control_solution.api_models

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ProfileApiService {

    @POST("api/profiles")
    suspend fun saveProfile(@Body profile: ProfileDTO): Response<SaveProfileResponse>

    @GET("api/profiles")
    suspend fun getAllProfiles(
        @Query("includeImages") includeImages: Boolean = false,
        @Query("includeThumbnails") includeThumbnails: Boolean = true
    ): Response<ProfilesResponse>

    @GET("api/profiles/paginated")
    suspend fun getProfilesPaginated(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("includeImages") includeImages: Boolean = false
    ): Response<ProfilesResponse>

    @GET("api/profiles/lagid/{lagId}")
    suspend fun getProfileByLagId(@Path("lagId") lagId: String): Response<ApiResponse<ProfileDTO>>

    @GET("api/profiles/{id}")
    suspend fun getProfileById(@Path("id") id: String): Response<ApiResponse<ProfileDTO>>

    @DELETE("api/profiles/{id}")
    suspend fun deleteProfile(@Path("id") id: String): Response<ApiResponse<Unit>>

    @DELETE("api/profiles/lagid/{lagId}")
    suspend fun deleteProfileByLagId(@Path("lagId") lagId: String): Response<ApiResponse<Unit>>

    @GET("api/profiles/stats/count")
    suspend fun getProfilesCount(): Response<ApiResponse<Int>>

    @POST("api/access/logs")
    suspend fun logAccess(
        @Body accessLog: AccessLog
    ): Response<AccessLogResponse>

    @GET("api/access/logs")
    suspend fun getAccessLogs(
        @Query("lagId") lagId: String? = null,
        @Query("accessGranted") accessGranted: Boolean? = null,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 1
    ): Response<AccessLogResponse>

    @GET("api/access/stats")
    suspend fun getAccessStats(
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null
    ): Response<AccessStatsResponse>

    @POST("api/attendance/clock")
    suspend fun clockInOut(
        @Body request: ClockRequest
    ): Response<ClockInOutResponse>

    @GET("api/attendance")
    suspend fun getAttendance(
        @Query("lagId") lagId: String? = null,
        @Query("startDate") startDate: String? = null,
        @Query("endDate") endDate: String? = null,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("page") page: Int = 1
    ): Response<AttendanceResponse>

    @GET("api/attendance/today")
    suspend fun getTodayAttendance(): Response<TodayAttendanceResponse>

    @GET("api/health")
    suspend fun healthCheck(): Response<ApiResponse<String>>


}