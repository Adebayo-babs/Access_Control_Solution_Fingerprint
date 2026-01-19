package com.example.access_control_solution.viewModel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.access_control_solution.api_models.ProfileSyncManager
import com.example.access_control_solution.data.AppDatabase
import com.example.access_control_solution.data.ProfileEntity
import com.example.neurotecsdklibrary.NeurotecLicenseHelper
import com.neurotec.biometrics.NBiometricCaptureOption
import com.neurotec.biometrics.NBiometricOperation
import com.neurotec.biometrics.NBiometricStatus
import com.neurotec.biometrics.NFace
import com.neurotec.biometrics.NSubject
import com.neurotec.biometrics.client.NBiometricClient
import com.neurotec.devices.NCamera
import com.neurotec.devices.NDeviceType
import com.neurotec.images.NImage
import com.neurotec.io.NBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.Error
import java.util.EnumSet
import java.util.concurrent.Executors

data class FaceDetectionFeedback(
    val lightingStatus: LightingStatus = LightingStatus.UNKNOWN,
    val distanceStatus: DistanceStatus = DistanceStatus.UNKNOWN,
    val positionStatus: PositionStatus = PositionStatus.UNKNOWN,
    val qualityStatus: QualityStatus = QualityStatus.UNKNOWN,
    val overallMessage: String = "Position your face in view"
)

enum class LightingStatus {
    GOOD, UNKNOWN
}

enum class DistanceStatus {
    GOOD, UNKNOWN
}

enum class PositionStatus {
    CENTERED, UNKNOWN
}

enum class QualityStatus {
    EXCELLENT, UNKNOWN
}

class CardReaderViewModel(application: Application) : AndroidViewModel(application) {
    data class DialogState(
        val showDialog: Boolean = false,
        val message: String = "",
        val capturedFace: Bitmap? = null
    )

    // Private mutable state - only this viewModel can change it
    private val _dialogState = MutableStateFlow(DialogState())
    // Public read-only state - UI can observe but not modify
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val main = android.os.Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val dbExecutor = Executors.newSingleThreadExecutor()

    var status by mutableStateOf("")
        private set

    var biometricClient: NBiometricClient? = null
        private set

    var isCapturing by mutableStateOf(false)
        private set

    var currentSubject: NSubject? = null
        private set

    var detectionFeedback by mutableStateOf(FaceDetectionFeedback())
        private set

    var useNeurotecCamera by mutableStateOf(true)
        private set

    // Callback property for sound
    var onFaceDetectedSound: (() -> Unit)? = null

    // Card data state for AddProfileScreen
    private val _cardData = MutableStateFlow<Pair<String, String>?>(null)
    val cardData: StateFlow<Pair<String, String>?> = _cardData.asStateFlow()

    private val _cardReadError = MutableStateFlow<String?>(null)
    val cardReadError: StateFlow<String?> = _cardReadError.asStateFlow()

    private val cameras = mutableListOf<NCamera>()
    private var activeCameraIndex = 0

    private var isInitialized = false

    private var captureInProgress = false


    var capturedProfileFace: Bitmap? by mutableStateOf(null)
        private set

    var capturedProfileTemplate: ByteArray? = null
        private set

    private var cachedProfiles: List<ProfileEntity>? = null
    private var cacheTimestamp: Long = 0
    private val CACHE_DURATION_MS = 30000L  // 30 seconds cache

    private lateinit var syncManager: ProfileSyncManager
    var isServerAvailable by mutableStateOf(false)
    private var isSyncing by mutableStateOf(false)

    fun initialize() {
        if (isInitialized) {
            startAutomaticCapture()
            return
        }

        executor.execute {
            try {
                NeurotecLicenseHelper.obtainFaceLicenses(getApplication())
                initClient()
                main.post {
                    if (isInitialized) {
                        startAutomaticCapture()
                    }
                }
            } catch (e: Exception) {
                main.post { status = "License Error: ${e.message}" }
            }
        }
    }

    private fun initClient() {
        try {
            biometricClient = NBiometricClient().apply {
                setFacesDetectProperties(true)
                isUseDeviceManager = true
                deviceManager.deviceTypes = EnumSet.of(NDeviceType.CAMERA)

                facesQualityThreshold = 50
                facesConfidenceThreshold = 1

                // Disable features that needs the missing models
                setProperty("Faces.DetectAllFeaturePoints", "false")
                setProperty("Faces.RecognizeExpression", "false")

                initialize()
            }

            val cameras = biometricClient?.deviceManager?.devices ?: emptyList()
            this.cameras.clear()

            cameras.forEach { device ->
                if (device is NCamera) {
                    this.cameras.add(device)
                    Log.d("NeurotecCamera", "Detected camera: ${device.displayName}")
                }

            }

            if (this.cameras.isEmpty()) {
                main.post { status = "No camera found"}
                return
            }

            activeCameraIndex = 0
            biometricClient?.faceCaptureDevice = this.cameras[activeCameraIndex]

            isInitialized = true

            main.postDelayed({
                startAutomaticCapture()
            }, 300)
        } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Camera initialization error", e)
            main.post { status = "Camera initialization error: ${e.message}" }
        }
    }

    // Initialize client without starting capture
    fun initializeClientOnly() {
        if (biometricClient != null) {
            return // Already initialized
        }

        executor.execute {
            try {
                NeurotecLicenseHelper.obtainFaceLicenses(getApplication())
                initClient()
                isInitialized = true
            } catch (e: Exception) {
                main.post { status = "License Error: ${e.message}"}
            }
        }
    }

    fun initializeSyncManager() {
        syncManager = ProfileSyncManager(getApplication())
        // Check server availability on startup
        executor.execute {
            CoroutineScope(Dispatchers.IO).launch {
                isServerAvailable = syncManager.isServerAvailable()

                if (isServerAvailable) {
                    // Automatically load profiles from server on startup
                    loadProfilesFromServer()
                } else {
                    Log.w("CardReaderViewModel", "Server not available, using local database only")
                }
            }
        }

    }

    private fun loadProfilesFromServer() {
        if (isSyncing) {
            return
        }

        isSyncing = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = syncManager.loadAllProfilesFromServer()

                main.post {
                    isSyncing = false
                    if (result.isSuccess) {
                        val count = result.getOrDefault(0)
                        Log.d("CardReaderViewModel", "Successfully loaded $count profiles")

                        // Invalidate cache
                        cachedProfiles = null
                    } else {
                        Log.e("CardReaderViewModel", "Failed to load profiles: ${result.exceptionOrNull()}")
                    }
                }
            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error loading profiles", e)
                main.post {
                    isSyncing = false
                }
            }
        }
    }

    fun setCardDataForProfile(fullName: String, lagId: String) {
        main.post {
            _cardData.value = Pair(fullName, lagId)
            _cardReadError.value = null
        }
    }

    fun setCardReadError(error: String) {
        _cardReadError.value = error
    }

    fun clearCardData() {
        _cardData.value = null
        _cardReadError.value = null
    }

    fun startAutomaticCapture() {

        if (captureInProgress || !isInitialized) {
            Log.d("CardReaderViewModel", "Capture already in progress or not initialized")
            return
        }

        executor.execute {
            try {
                captureInProgress = true
                main.post {
                    isCapturing = true
                    detectionFeedback = FaceDetectionFeedback(
                        overallMessage = "Looking for face..."
                    )
                }

                Log.d("CardReaderViewModel", "Starting automatic capture...")

                val subject = NSubject()
                val face = NFace().apply {
                    captureOptions = EnumSet.of(NBiometricCaptureOption.STREAM)
                }

                subject.faces.add(face)
                main.post { currentSubject = subject }

                val task = biometricClient?.createTask(
                    EnumSet.of(NBiometricOperation.CAPTURE, NBiometricOperation.CREATE_TEMPLATE),
                    subject
                )

                task?.let { biometricClient?.performTask(it) }

                val taskStatus = task?.status

                Log.d("CardReaderViewModel", "Capture task status: $taskStatus")

                when (taskStatus) {
                    NBiometricStatus.OK -> {
                        Log.d("CardReaderViewModel", "Face captured successfully!")

                        // Extract face image
                        val faceImage = subject.faces.firstOrNull()?.image
                        val bitmap = faceImage?.let { convertNImageToBitmap(it) }

                        // Extract and store the template for verification
                        val template = subject.templateBuffer?.toByteArray()
                        if (template != null) {
                            capturedProfileFace = bitmap
                            capturedProfileTemplate = template
                            Log.d("CardReaderViewModel", "Template saved for verification, size: ${template.size}")
                        } else {
                            Log.e("CardReaderViewModel", "Failed to extract template!")
                        }

                        main.post {
                            onFaceDetectedSound?.invoke()
//                            status = "Face captured successfully!"
                            detectionFeedback = FaceDetectionFeedback(
                                lightingStatus = LightingStatus.GOOD,
                                distanceStatus = DistanceStatus.GOOD,
                                positionStatus = PositionStatus.CENTERED,
                                qualityStatus = QualityStatus.EXCELLENT,
                                overallMessage = "Perfect! Face captured!"
                            )

                            // Show dialog with captured face
                            showFaceDetectedDialog(bitmap)
                        }

                        captureInProgress
                    }

                    NBiometricStatus.TIMEOUT,
                    NBiometricStatus.BAD_OBJECT -> {
                        Log.w("CardReaderViewModel", "No face detected, retrying...")
                        main.post {
                            status = "No face detected. Please position your face..."
                            detectionFeedback = FaceDetectionFeedback(
                                overallMessage = "No face detected. Please try again..."
                            )
                        }

                        captureInProgress = false
                        main.postDelayed({ startAutomaticCapture() }, 500)
                    }

                    else -> {
                        Log.w("CardReaderViewModel", "Capture failed: $taskStatus")
                        main.post {
                            detectionFeedback = FaceDetectionFeedback(
                                overallMessage = "Detection failed. Retrying..."
                            )
                        }

                        captureInProgress = false
                        main.postDelayed({ startAutomaticCapture() }, 800)
                    }
                }

                task?.dispose()

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error during capture", e)
                captureInProgress = false
                main.post {
                    isCapturing = false
//                    status = "Capture error. Retrying..."
                    detectionFeedback = FaceDetectionFeedback(
                        overallMessage = "Error occurred. Retrying..."
                    )
                    main.postDelayed({ startAutomaticCapture() }, 1000)
                }
            }
        }
    }

    private fun convertNImageToBitmap(nImage: NImage): Bitmap? {
        return try {
            val bitmap = nImage.toBitmap()
            Log.d("CardReaderViewModel", "Converted to bitmap: ${bitmap.width}x${bitmap.height}")
            bitmap
        } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Error converting NImage to Bitmap", e)
            null
        }
    }


    fun toggleCameraPreview() {
        if (cameras.size < 2) {
            status = "Only one camera available"
            return
        }
        stopCapture()
        activeCameraIndex = (activeCameraIndex + 1) % cameras.size
        biometricClient?.faceCaptureDevice = cameras[activeCameraIndex]
        status = "Switched to ${cameras[activeCameraIndex].displayName}"
        startAutomaticCapture()
    }

    fun stopCapture() {
        captureInProgress = false
        isCapturing = false

        // Cancel any pending capture operations
        try {
            currentSubject?.faces?.clear()
            currentSubject = null
        } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Error clearing subject", e)
        }
    }

    private fun showFaceDetectedDialog(faceBitmap: Bitmap?) {
        _dialogState.value = DialogState(
            showDialog = true,
            message = "Face Detected Successfully",
            capturedFace = faceBitmap
        )
    }

    fun hideDialog() {
        _dialogState.value = DialogState(
            showDialog = false,
            message = "",
            capturedFace = null
        )
    }

    fun reset() {
        stopCapture()
        hideDialog()
        status = ""
        detectionFeedback = FaceDetectionFeedback()
        isInitialized = false
    }

    // Save profile function with server sync
    fun saveProfile(
        name: String,
        lagId: String,
        faceTemplate: ByteArray,
        faceImage: ByteArray,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        dbExecutor.execute {
            try {
                val compressedImage = compressFaceImage(faceImage)
                val thumbnail = createThumbnail(faceImage)

                val profileEntity = ProfileEntity(
                    name = name,
                    lagId = lagId,
                    faceTemplate = faceTemplate,
                    faceImage = compressedImage,
                    thumbnail = thumbnail
                )

                // Try to save to server first (includes duplicate checking)
                CoroutineScope(Dispatchers.IO).launch {
                    if (isServerAvailable) {
                        val serverResult = syncManager.saveProfileToServer(profileEntity)

                        if (serverResult.isSuccess) {
                            // Server save successful, now save locally
                            try {
                                val profileId = AppDatabase.getInstance(getApplication())
                                    .profileDao()
                                    .insert(profileEntity)

                                cachedProfiles = null
                                cacheTimestamp = 0

                                Log.d("CardReaderViewModel", "✓ Profile saved: local ID=$profileId, server ID=${serverResult.getOrNull()}")
                                main.post { onSuccess(profileId) }
                            } catch (e: Exception) {
                                Log.e("CardReaderViewModel", "Error saving to local DB", e)
                                main.post { onError("Saved to server but failed to save locally: ${e.message}") }
                            }
                        } else {
                            // Server save failed - get the actual error message
                            val error = serverResult.exceptionOrNull()?.message ?: "Failed to save profile"
                            Log.e("CardReaderViewModel", "Server error: $error")

                            // Show the error to user
                            main.post { onError(error) }
                        }
                    } else {
                        // Server not available, save locally only with local duplicate check
                        Log.w("CardReaderViewModel", "Server unavailable, performing local duplicate check...")

                        checkForDuplicatesLocally(lagId, faceTemplate) { isDuplicate, duplicateType, existingProfile ->
                            if (isDuplicate) {
                                val errorMsg = when (duplicateType) {
                                    "LAG ID" -> "LAG ID '$lagId' is already registered to ${existingProfile?.name}"
                                    "Face" -> "This face is already registered as ${existingProfile?.name}"
                                    else -> "Profile already exists"
                                }
                                Log.w("CardReaderViewModel", "Local duplicate found: $errorMsg")
                                main.post { onError(errorMsg) }
                            } else {
                                try {
                                    val profileId = AppDatabase.getInstance(getApplication())
                                        .profileDao()
                                        .insert(profileEntity)

                                    cachedProfiles = null
                                    cacheTimestamp = 0

                                    Log.d("CardReaderViewModel", "✓ Profile saved locally: ID=$profileId")
                                    main.post { onSuccess(profileId) }
                                } catch (e: Exception) {
                                    Log.e("CardReaderViewModel", "Error saving locally", e)
                                    main.post { onError(e.message ?: "Unknown error") }
                                }
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error in saveProfile", e)
                main.post { onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun checkForDuplicatesLocally(
        lagId: String,
        faceTemplate: ByteArray,
        onResult: (isDuplicate: Boolean, duplicateType: String?, existingProfile: ProfileEntity?) -> Unit
    ) {
        dbExecutor.execute {
            try {
                // Check if LAG ID already exists locally
                val existingByLagId = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getProfileByLagId(lagId)

                if (existingByLagId != null) {
                    Log.d("CardReaderViewModel", "Duplicate LAG ID found: ${existingByLagId.name}")
                    main.post {
                        onResult(true, "LAG ID", existingByLagId)
                    }
                    return@execute
                }

                // Check face template against local profiles
                val allProfiles = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getAllProfile()

                if (allProfiles.isEmpty()) {
                    Log.d("CardReaderViewModel", "No profiles in database, no duplicates")
                    main.post {
                        onResult(false, null, null)
                    }
                    return@execute
                }

                // Create subject for the new face template
                val newFaceSubject = NSubject()
                newFaceSubject.setTemplateBuffer(NBuffer(faceTemplate))

                var bestMatchScore = 0
                var bestMatchProfile: ProfileEntity? = null

                // Check against each existing profile
                for (profile in allProfiles) {
                    try {
                        val existingSubject = NSubject()
                        existingSubject.setTemplateBuffer(NBuffer(profile.faceTemplate))

                        val matchStatus = biometricClient?.verify(newFaceSubject, existingSubject)

                        if (matchStatus == NBiometricStatus.OK) {
                            val score = newFaceSubject.matchingResults?.getOrNull(0)?.score ?: 0

                            Log.d("CardReaderViewModel", "Match score with ${profile.name}: $score")

                            if (score > bestMatchScore) {
                                bestMatchScore = score
                                bestMatchProfile = profile
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CardReaderViewModel", "Error matching with profile ${profile.name}", e)
                    }
                }

                // INCREASED THRESHOLD to reduce false positives
                // Face must match with score >= 90 to be considered duplicate
                val duplicateThreshold = 90

                if (bestMatchScore >= duplicateThreshold && bestMatchProfile != null) {
                    Log.d("CardReaderViewModel", "Duplicate face found: ${bestMatchProfile.name}, Score: $bestMatchScore")
                    main.post {
                        onResult(true, "Face", bestMatchProfile)
                    }
                } else {
                    if (bestMatchScore > 0) {
                        Log.d("CardReaderViewModel", "No duplicate (Best match: ${bestMatchProfile?.name}, Score: $bestMatchScore)")
                    } else {
                        Log.d("CardReaderViewModel", "No matches found")
                    }
                    main.post {
                        onResult(false, null, null)
                    }
                }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error checking duplicates locally", e)
                main.post {
                    onResult(false, null, null)
                }
            }
        }
    }



    private fun createThumbnail(imageBytes: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val thumbnail = Bitmap.createScaledBitmap(bitmap, 80, 80, true)
            val stream = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val result = stream.toByteArray()
            stream.close()
            thumbnail.recycle()
            bitmap.recycle()
            result
        } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Error creating thumbnail", e)
            imageBytes
        }
    }

    // Helper function to compress face image more efficiently
    private fun compressFaceImage(imageBytes: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Resize if too large (max 300x300 for storage)
            val resizedBitmap = if (bitmap.width > 300 || bitmap.height > 300) {
                val ratio = minOf(300f / bitmap.width, 300f / bitmap.height)
                val newWidth = (bitmap.width * ratio).toInt()
                val newHeight = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            // Compress with lower quality for storage
            val stream = java.io.ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val compressed = stream.toByteArray()
            stream.close()

            if (bitmap != resizedBitmap) {
                resizedBitmap.recycle()
            }
            bitmap.recycle()

            Log.d("CardReaderViewModel", "Image compressed from ${imageBytes.size} to ${compressed.size} bytes")
            compressed
        } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Error compressing image", e)
            imageBytes // Return original if compression fails
        }
    }


    fun getAllProfile(callback: (List<ProfileEntity>) -> Unit, forceRefresh: Boolean = false) {
        // If force refresh requested, clear cache first
        if (forceRefresh) {
            cachedProfiles = null
            cacheTimestamp = 0
        }

        // If force refresh and server available, reload from server
        if (forceRefresh && isServerAvailable && !isSyncing) {
            isSyncing = true

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Reload from server
                    val result = syncManager.loadAllProfilesFromServer()

                    main.post {
                        isSyncing = false

                        // After server sync, get local profiles
                        getLocalProfiles(callback)
                    }
                } catch (e: Exception) {
                    Log.e("CardReaderViewModel", "Error syncing", e)
                    main.post {
                        isSyncing = false
                        // Even if sync fails, return local data
                        getLocalProfiles(callback)
                    }
                }
            }
            return
        }

        // Return cached data if still fresh
        if (!forceRefresh && cachedProfiles != null &&
            (System.currentTimeMillis() - cacheTimestamp) < CACHE_DURATION_MS) {
            Log.d("CardReaderViewModel", "Returning cached profiles")
            main.post { callback(cachedProfiles!!) }
            return
        }

        // Otherwise get from local database
        getLocalProfiles(callback)
    }

    private fun getLocalProfiles(callback: (List<ProfileEntity>) -> Unit) {
        dbExecutor.execute {
            try {
                val profileList = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getAllProfile()

                cachedProfiles = profileList
                cacheTimestamp = System.currentTimeMillis()

                main.post {
                    callback(profileList)
                }
            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error getting profile", e)
                main.post { callback(emptyList()) }
            }
        }
    }

    fun deleteProfile(profileId: Long, onComplete: () -> Unit) {
        dbExecutor.execute {
            try {
                val profile = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getProfileById(profileId)

                profile?.let { profileToDelete ->
                    // Delete from local database
                    AppDatabase.getInstance(getApplication())
                        .profileDao()
                        .delete(profileToDelete)

                    cachedProfiles = null

                    // Delete from server
                    if (isServerAvailable) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val result = syncManager.deleteProfileFromServer(profileToDelete.lagId)
                            if (result.isSuccess) {
                                Log.d("CardReaderViewModel", "Profile deleted from server")
                            } else {
                                Log.w("CardReaderViewModel", "Failed to delete from server: ${result.exceptionOrNull()}")
                            }
                        }
                    }

                    main.post { onComplete() }
                } ?: run {
                    main.post {
                        status = "Profile not found"
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error deleting profile", e)
                main.post { onComplete() }
            }
        }
    }

    fun refreshFromServer(onComplete: (Int, String?) -> Unit) {
        if (!isServerAvailable) {
            main.post { onComplete(0, "Server not available") }
            return
        }

        if (isSyncing) {
            main.post { onComplete(0, "Sync already in progress") }
            return
        }

        isSyncing = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = syncManager.loadAllProfilesFromServer()

                main.post {
                    isSyncing = false
                    cachedProfiles = null

                    if (result.isSuccess) {
                        val count = result.getOrDefault(0)
                        onComplete(count, null)
                    } else {
                        onComplete(0, result.exceptionOrNull()?.message ?: "Unknown error")
                    }
                }
            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error refreshing", e)
                main.post {
                    isSyncing = false
                    onComplete(0, e.message)
                }
            }
        }
    }
    init {
        initializeSyncManager()
    }



    private fun checkForDuplicates(
        lagId: String,
        faceTemplate: ByteArray,
        onResult: (isDuplicate: Boolean, duplicateType: String?, existingProfile: ProfileEntity?) -> Unit
    ) {
        dbExecutor.execute {
            try {
                // Check if LAG ID already exists
                val existingByLagId = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getProfileByLagId(lagId)

                if (existingByLagId != null) {
                    main.post {
                        onResult(true, "LAG ID", existingByLagId)
                    }
                    return@execute
                }

                // Check if face template matches any existing profile
                val allProfiles = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getAllProfile()

                if (allProfiles.isEmpty()) {
                    main.post {
                        onResult(false, null, null)
                    }
                    return@execute
                }

                // Create subject for the new face template
                val newFaceSubject = NSubject()
                newFaceSubject.setTemplateBuffer(NBuffer(faceTemplate))

                // Check against each existing profile
                for (profile in allProfiles) {
                    try {
                        val existingSubject = NSubject()
                        existingSubject.setTemplateBuffer(NBuffer(profile.faceTemplate))

                        val matchStatus = biometricClient?.verify(newFaceSubject, existingSubject)

                        if (matchStatus == NBiometricStatus.OK) {
                            val score = newFaceSubject.matchingResults?.getOrNull(0)?.score ?: 0

                            // Use a high threshold for duplicate detection (e.g., 80)
                            val duplicateThreshold = 80

                            if (score >= duplicateThreshold) {
                                Log.d("CardReaderViewModel", "Duplicate face found: ${profile.name}, Score: $score")
                                main.post {
                                    onResult(true, "Face", profile)
                                }
                                return@execute
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CardReaderViewModel", "Error matching with profile ${profile.name}", e)
                    }
                }

                // No duplicates found
                main.post {
                    onResult(false, null, null)
                }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error checking duplicates", e)
                main.post {
                    onResult(false, null, null)
                }
            }
        }
    }

    fun verifyFaceAgainstDatabase(onResult: (ProfileEntity?, Int) -> Unit) {
        executor.execute {
            try {
                main.post { status = "Searching database..." }

                // Get the captured face template
                val capturedTemplate = capturedProfileTemplate
                if (capturedTemplate == null) {
                    main.post {
                        status = "No captured face found"
                        onResult(null, 0)
                    }
                    return@execute
                }

                // Get all staff from database
                val allProfile = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getAllProfile()

                if (allProfile.isEmpty()) {
                    main.post {
                        status = "No profile in database"
                        onResult(null, 0)
                    }
                    return@execute
                }

                main.post { status = "Comparing with ${allProfile.size} profiles..." }

                // Create subject for captured face
                val capturedSubject = NSubject()
                capturedSubject.setTemplateBuffer(NBuffer(capturedTemplate))

                var bestMatch: ProfileEntity? = null
                var bestScore = 0

                // Match against each staff profile
                allProfile.forEachIndexed { index, profile ->
                    try {
                        val profileSubject = NSubject()
                        profileSubject.setTemplateBuffer(NBuffer(profile.faceTemplate))

                        val matchStatus = biometricClient?.verify(capturedSubject, profileSubject)

                        if (matchStatus == NBiometricStatus.OK) {
                            val score = capturedSubject.matchingResults?.getOrNull(0)?.score ?: 0
                            if (score > bestScore) {
                                bestScore = score
                                bestMatch = profile
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CardReaderViewModel", "Error matching with profile ${profile.name}", e)
                    }
                }

                val threshold = 48
                val finalMatch = if (bestScore >= threshold) bestMatch else null

                main.post {
                    status = if (finalMatch != null) {
                        "Match found: ${finalMatch.name} (Score: $bestScore)"
                    } else if (bestScore > 0) {
                        "No match found (Best score: $bestScore)"
                    } else {
                        "No match found"
                    }
                    onResult(finalMatch, bestScore)
                }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error during verification", e)
                main.post {
                    status = "Verification error: ${e.message}"
                    onResult(null, 0)
                }
            }
        }
    }


    private fun fixImageOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.let { ExifInterface(it) }
            inputStream?.close()

            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            }

            if (!matrix.isIdentity) {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Error fixing image orientation", e)
            bitmap
        }
    }

    fun processFaceFromUri(
        context: Context,
        uri: Uri,
        onSuccess: (Bitmap, ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                // Stop any ongoing capture to avoid conflicts
                stopCapture()

//                main.post { status = "Loading image..." }

                // Read image from URI with options for faster decoding
                val inputStream = context.contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = calculateInSampleSize(this, 512, 512)
                }

                val originalBitmap = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                if (originalBitmap == null) {
                    main.post {
                        status = "Failed to read image"
                        onError("Failed to read image from gallery")
                    }
                    return@execute
                }

                // Fix orientation
                val rotatedBitmap = fixImageOrientation(context, uri, originalBitmap)

                // Show the image immediately
                main.post {
                    capturedProfileFace = rotatedBitmap
//                    status = "Detecting face..."
                }

                // Ensure biometric client is initialized
                if (biometricClient == null) {
                    Log.w("CardReaderViewModel", "Biometric client not initialized, initializing now...")
                    try {
                        NeurotecLicenseHelper.obtainFaceLicenses(context)
                        // Initialize on main thread
                        main.post { initClient() }
                        // Wait for initialization
                    } catch (e: Exception) {
                        Log.e("CardReaderViewModel", "License initialization error", e)
                    }
                }

                if (biometricClient == null) {
                    main.post {
                        status = "System not ready"
                        onError("Face detection system not initialized. Please try again.")
                    }
                    return@execute
                }

                // Convert bitmap to byte array
                val stream = java.io.ByteArrayOutputStream()
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val imageBytes = stream.toByteArray()
                stream.close()

                // Process the face
                processFaceFromImage(imageBytes, rotatedBitmap, onSuccess, onError)

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error in processFaceFromUri", e)
                e.printStackTrace()
                main.post {
                    status = "Error: ${e.message}"
                    onError("Error reading image: ${e.message}")
                }
            }
        }
    }

    // Helper function to calculate sample size
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun processFaceFromImage(
        imageBytes: ByteArray,
        previewBitmap: Bitmap,
        onSuccess: (Bitmap, ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.d("CardReaderViewModel", "Starting face detection from image")

            // Convert byte array to NImage
            val nImage = NImage.fromMemory(NBuffer(imageBytes))
            Log.d("CardReaderViewModel", "NImage created: ${nImage.width}x${nImage.height}")

            // Create subject and face
            val subject = NSubject()
            val face = NFace()
            face.image = nImage
            subject.faces.add(face)

            // Create task
            val task = biometricClient?.createTask(
                EnumSet.of(
                    NBiometricOperation.DETECT_SEGMENTS,
                    NBiometricOperation.CREATE_TEMPLATE
                ),
                subject
            )

            task?.let {
                it.timeout = 15000 // 15 seconds timeout
                Log.d("CardReaderViewModel", "Performing face detection...")
                biometricClient?.performTask(it)
                Log.d("CardReaderViewModel", "Task completed with status: ${it.status}")
            }

            val taskStatus = task?.status

            when (taskStatus) {
                NBiometricStatus.OK -> {
                    val template = subject.templateBuffer?.toByteArray()

                    if (template != null && template.isNotEmpty()) {
                        Log.d("CardReaderViewModel", "Template extracted, size: ${template.size}")
                        main.post {
//                            status = "Image uploaded successfully!"
                            onSuccess(previewBitmap, template)
                        }
                    } else {
                        Log.e("CardReaderViewModel", "Template is null or empty")
                        main.post {
                            status = "Failed to create template"
                            onError("Failed to extract face template from image")
                        }
                    }
                }

                NBiometricStatus.BAD_OBJECT -> {
                    Log.w("CardReaderViewModel", "No face detected in image")
                    main.post {
                        status = "No face detected"
                        onError("No face detected. Please select a clear photo with a visible face.")
                    }
                }

                NBiometricStatus.TIMEOUT -> {
                    Log.e("CardReaderViewModel", "Face detection timeout")
                    main.post {
                        status = "Processing timeout"
                        onError("Processing took too long. Please try a different photo.")
                    }
                }

                else -> {
                    Log.e("CardReaderViewModel", "Face detection failed: $taskStatus")
                    main.post {
                        status = "Detection failed"
                        onError("Could not detect face. Please try another photo.")
                    }
                }
            }

            // Clean up
            task?.dispose()
            nImage.dispose()

        } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Exception in processFaceFromImage", e)
            e.printStackTrace()
            main.post {
                status = "Error: ${e.message}"
                onError("Error processing face: ${e.message}")
            }
        }
    }

    fun checkLagIdInDatabase(lagId: String, onResult: (ProfileEntity?, Boolean) -> Unit) {
        executor.execute {
            try {
                main.post { status = "Verifying card..." }

                val profile = AppDatabase.getInstance(getApplication())
                    .profileDao()
                    .getProfileByLagId(lagId)

                val exists = profile != null

                main.post {
                    status = if (exists) {
                        "Access Granted: ${profile?.name}"
                    } else {
                        "Access Denied: LAG ID not registered"
                    }
                    onResult(profile, exists)
                }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error checking LAG ID", e)
                main.post {
                    status = "Error checking card"
                    onResult(null, false)
                }
            }
        }
    }

    fun invalidateCache() {
        cachedProfiles = null
        cacheTimestamp = 0
    }


    fun clearCapturedStaffFace() {
        capturedProfileFace = null
        capturedProfileTemplate = null
    }

    fun dismissDialog() {
        _dialogState.value = DialogState(showDialog = false, message = "")
    }

    override fun onCleared() {
        super.onCleared()
        stopCapture()
        executor.shutdown()
        biometricClient?.dispose()
    }
}