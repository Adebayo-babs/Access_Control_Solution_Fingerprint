package com.example.access_control_solution.viewModel

import android.annotation.SuppressLint
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
import androidx.lifecycle.viewModelScope
import com.example.access_control_solution.CaptureResult
import com.example.access_control_solution.FingerprintCaptureState
import com.example.access_control_solution.FingerprintVerifyState
import com.example.access_control_solution.TelpoFingerprintManager
import com.example.access_control_solution.api_models.AccessLog
import com.example.access_control_solution.api_models.ClockRequest
import com.example.access_control_solution.api_models.ProfileSyncManager
import com.example.access_control_solution.api_models.RetrofitClient
import com.example.access_control_solution.data.AppDatabase
import com.example.access_control_solution.data.ProfileEntity
import com.example.access_control_solution.reader.SAMCardReader
import com.example.neurotecsdklibrary.NeurotecFingerprintHelper
import com.example.neurotecsdklibrary.NeurotecLicenseHelper
import com.neurotec.biometrics.NBiometricCaptureOption
import com.neurotec.biometrics.NBiometricOperation
import com.neurotec.biometrics.NBiometricStatus
import com.neurotec.biometrics.NFace
import com.neurotec.biometrics.NFinger
import com.neurotec.biometrics.NSubject
import com.neurotec.biometrics.client.NBiometricClient
import com.neurotec.devices.NCamera
import com.neurotec.devices.NDeviceType
import com.neurotec.images.NImage
import com.neurotec.images.NImageFormat
import com.neurotec.io.NBuffer
import com.neurotec.lang.NCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

data class FaceDetectionFeedback(
    val lightingStatus: LightingStatus = LightingStatus.UNKNOWN,
    val distanceStatus: DistanceStatus = DistanceStatus.UNKNOWN,
    val positionStatus: PositionStatus = PositionStatus.UNKNOWN,
    val qualityStatus: QualityStatus = QualityStatus.UNKNOWN,
    val overallMessage: String = "Position your face in view"
)

enum class LightingStatus { GOOD, UNKNOWN }

enum class DistanceStatus { GOOD, UNKNOWN }

enum class PositionStatus { CENTERED, UNKNOWN }

enum class QualityStatus { EXCELLENT, UNKNOWN }

class CardReaderViewModel(application: Application) : AndroidViewModel(application) {

    data class DialogState(
        val showDialog: Boolean = false,
        val message: String = "",
        val capturedFace: Bitmap? = null
    )

    private val _dialogState = MutableStateFlow(DialogState())
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

    var onFaceDetectedSound: (() -> Unit)? = null

    // Card data state
    private val _cardData = MutableStateFlow<Pair<String, String>?>(null)
    val cardData: StateFlow<Pair<String, String>?> = _cardData.asStateFlow()

    private val _cardReadError = MutableStateFlow<String?>(null)
    val cardReadError: StateFlow<String?> = _cardReadError.asStateFlow()

    private val _faceImageFromCard = MutableStateFlow<ByteArray?>(null)
    val faceImageFromCard: StateFlow<ByteArray?> = _faceImageFromCard.asStateFlow()

    // Fingerprint states

    private val _fingerprintsFromCard =
        MutableStateFlow<List<SAMCardReader.FingerprintData>>(emptyList())
    val fingerprintFromCard: StateFlow<List<SAMCardReader.FingerprintData>> =
        _fingerprintsFromCard.asStateFlow()

    private val _fingerprintCaptureState =
        MutableStateFlow<FingerprintCaptureState>(
            FingerprintCaptureState.Idle
        )
    val fingerprintCaptureState: StateFlow<FingerprintCaptureState> =
        _fingerprintCaptureState.asStateFlow()

    private val _fingerprintVerifyState =
        MutableStateFlow<FingerprintVerifyState>(
            FingerprintVerifyState.Idle
        )
    val fingerprintVerifyState: StateFlow<FingerprintVerifyState> =
        _fingerprintVerifyState.asStateFlow()

    private var fpManager: TelpoFingerprintManager? = null


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
    private val CACHE_DURATION_MS = 30000L

    private lateinit var syncManager: ProfileSyncManager
    var isServerAvailable by mutableStateOf(false)
    private var isSyncing by mutableStateOf(false)

    private var isSensorOpen = false
    private val sensorLock = Any()

    private var isCameraClientInitialized = false

    // Biometric data from card

    fun setBiometricDataFromCard(faceImage: ByteArray?) {
        _faceImageFromCard.value = faceImage
    }

    fun setFingerprintsFromCard(fingerprints: List<SAMCardReader.FingerprintData>) {
        _fingerprintsFromCard.value = fingerprints
    }


    // Initialization
    fun initialize() {
        if (isCameraClientInitialized && isInitialized) {
            startAutomaticCapture()
            return
        }
        // If client exists but is fingerprint-only, dispose it cleanly
        if (biometricClient != null && !isCameraClientInitialized) {
            biometricClient?.dispose()
            biometricClient = null
            isInitialized = false
        }
        executor.execute {
            try {
                NeurotecLicenseHelper.obtainFaceLicenses(getApplication())
                initClient()
//                main.post { if (isInitialized) startAutomaticCapture() }
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

    fun initializeForFingerprintOnly() {
        if (biometricClient != null) {
            return
        }
        executor.execute {
            try {
                NeurotecLicenseHelper.obtainFaceLicenses(getApplication())
                // Initialize without device manager to avoid ZKTeco plugin crash
                biometricClient = NBiometricClient().apply {
                    setFacesDetectProperties(true)
                    facesQualityThreshold = 50
                    facesConfidenceThreshold = 1
                    setProperty("Faces.DetectAllFeaturePoints", "false")
                    setProperty("Faces.RecognizeExpression", "false")
                    initialize()
                }
                isInitialized = true
                isCameraClientInitialized = false
            } catch (e: Exception) {
                main.post { status = "License Error: ${e.message}" }
            }
        }
    }

    fun initializeSyncManager() {
        syncManager = ProfileSyncManager(getApplication())
        executor.execute {
            CoroutineScope(Dispatchers.IO).launch {
                isServerAvailable = syncManager.isServerAvailable()
                if (isServerAvailable) loadProfilesFromServer()
                else Log.w("CardReaderViewModel", "Server not available, using local database only")
            }
        }
    }

    private fun loadProfilesFromServer() {
        if (isSyncing) return
        isSyncing = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = syncManager.loadAllProfilesFromServer()
                main.post {
                    isSyncing = false
                    if (result.isSuccess) cachedProfiles = null
                    else Log.e("CardReaderViewModel", "Failed to load profiles: ${result.exceptionOrNull()}")
                }
            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error loading profiles", e)
                main.post { isSyncing = false }
            }
        }
    }

    fun setCardDataForProfile(fullName: String, lagId: String) {
        main.post { _cardData.value = Pair(fullName, lagId); _cardReadError.value = null }
    }

    fun setCardReadError(error: String) { _cardReadError.value = error }
    fun clearCardData() { _cardData.value = null; _cardReadError.value = null }


    fun startAutomaticCapture() {
        if (captureInProgress || !isInitialized) {
            Log.d("CardReaderViewModel", "Capture already in progress or not initialized")
            return
        }

        executor.execute {
            var subject: NSubject? = null
            var task: com.neurotec.biometrics.NBiometricTask? = null
            try {
                captureInProgress = true
                main.post {
                    isCapturing = true
                    detectionFeedback = FaceDetectionFeedback(overallMessage = "Looking for face...")
                }

                val client = biometricClient ?: run {
                    captureInProgress = false
                    return@execute
                }

                subject = NSubject()
                val face = NFace().apply {
                    captureOptions = EnumSet.of(NBiometricCaptureOption.STREAM)
                }
                subject.faces.add(face)

                // Guard: if stopCapture() was called while setting up, abort cleanly
                if (!captureInProgress) {
                    subject.dispose()
                    return@execute
                }

                task = client.createTask(
                    EnumSet.of(NBiometricOperation.CAPTURE, NBiometricOperation.CREATE_TEMPLATE),
                    subject
                )

                // Expose subject ONLY so UI can read it if needed — never mutate it from main thread
                currentSubject = subject

                // This blocks until capture completes or cancel() is called
                client.performTask(task)

                // Clear reference now that performTask has returned
                currentSubject = null

                val taskStatus = task.status
                Log.d("CardReaderViewModel", "Capture task status: $taskStatus")

                when (taskStatus) {
                    NBiometricStatus.OK -> {
                        val bitmap = subject.faces.firstOrNull()?.image
                            ?.let { convertNImageToBitmap(it) }
                        val template = subject.templateBuffer?.toByteArray()
                        if (template != null) {
                            capturedProfileFace = bitmap
                            capturedProfileTemplate = template
                            Log.d("CardReaderViewModel", "Template saved: ${template.size} bytes")
                        } else {
                            Log.e("CardReaderViewModel", "Failed to extract template!")
                        }
                        main.post {
                            onFaceDetectedSound?.invoke()
                            detectionFeedback = FaceDetectionFeedback(
                                LightingStatus.GOOD, DistanceStatus.GOOD,
                                PositionStatus.CENTERED, QualityStatus.EXCELLENT,
                                "Perfect! Face captured!"
                            )
                            showFaceDetectedDialog(bitmap)
                        }
                        // captureInProgress stays true — capture is done, not retrying
                    }

                    NBiometricStatus.TIMEOUT, NBiometricStatus.BAD_OBJECT -> {
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
                        Log.w("CardReaderViewModel", "Capture failed or cancelled: $taskStatus")
                        captureInProgress = false
                        // Only retry if we weren't deliberately stopped
                        if (isInitialized) {
                            main.post {
                                detectionFeedback = FaceDetectionFeedback(
                                    overallMessage = "Detection failed. Retrying..."
                                )
                            }
                            main.postDelayed({ startAutomaticCapture() }, 800)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error during capture", e)
                currentSubject = null
                captureInProgress = false
                main.post {
                    isCapturing = false
                    detectionFeedback = FaceDetectionFeedback(overallMessage = "Error occurred. Retrying...")
                }
                if (isInitialized) {
                    main.postDelayed({ startAutomaticCapture() }, 1000)
                }
            } finally {
                currentSubject = null
                try { task?.dispose() } catch (_: Exception) {}
                try { subject?.dispose() } catch (_: Exception) {}
            }
        }
    }

    private fun convertNImageToBitmap(nImage: NImage): Bitmap? {
        return try { nImage.toBitmap() } catch (e: Exception) { null }
    }

    fun toggleCameraPreview() {
        if (cameras.size < 2) { status = "Only one camera available"; return }

        isCapturing = false
        captureInProgress = false

        try { biometricClient?.cancel() } catch (e: Exception) {
            Log.e("CardReaderViewModel", "Cancel error", e)
        }

        executor.execute {
            activeCameraIndex = (activeCameraIndex + 1) % cameras.size
            biometricClient?.faceCaptureDevice = cameras[activeCameraIndex]
            main.post {
                status = "Camera switched"
                startAutomaticCapture()
            }
        }
    }


    fun stopCapture() {
        captureInProgress = false
        isCapturing = false
        try {
            biometricClient?.cancel()
//            currentSubject?.faces?.clear()
//            currentSubject = null
        }
        catch (e: Exception) {
            Log.e("CardReaderViewModel", "Error clearing subject", e)
        }
    }

    private fun showFaceDetectedDialog(faceBitmap: Bitmap?) {
        _dialogState.value = DialogState(showDialog = true, message = "Face Detected Successfully", capturedFace = faceBitmap)
    }

    fun hideDialog() { _dialogState.value = DialogState() }

    fun reset() {
        stopCapture()
        hideDialog()
        status = ""
        detectionFeedback = FaceDetectionFeedback()
        _faceImageFromCard.value = null
        currentSubject = null
    }

    // Fingerprint sensor

    fun openFingerprintSensor(onResult: (Boolean) -> Unit) {

        synchronized(sensorLock) {
            if (isSensorOpen) {
                main.post { onResult(true)}
                return
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                synchronized(sensorLock) {
                    if (isSensorOpen) {
                        main.post { onResult(true)}
                        return@launch
                    }
                }
                if (fpManager == null) {
                    fpManager = TelpoFingerprintManager(getApplication())
                }
                val ok = fpManager!!.open()

                synchronized(sensorLock) {
                    isSensorOpen = ok
                    if (!ok) {
                        fpManager = null
                    }
                }
                main.post { onResult(ok) }
            } catch (e: Exception) {
                synchronized(sensorLock) {
                    isSensorOpen = false
                    fpManager = null
                }
                main.post { onResult(false) }
            }
        }
    }

    fun closeFingerprintSensor() {
        val wasOpen: Boolean
        synchronized(sensorLock) {
            wasOpen = isSensorOpen
            if (!isSensorOpen) return
            isSensorOpen = false
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                fpManager?.close()
            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "closeFingerprintSensor error", e)
            } finally {
                fpManager = null
            }
        }
    }

    fun extractAndStoreFingerprintFromCard(
        fingerprints: List<SAMCardReader.FingerprintData>,
        onReady: (ByteArray?) -> Unit
    ) {
        val wsqList = fingerprints.mapNotNull { fp ->
            fp.template?.takeIf { fp.format == "WSQ" || fp.format == "RAW" }
        }

        if (wsqList.isEmpty()) {
            Log.w("CardReaderViewModel", "No WSQ fingerprints found on card")
            onReady(null)
            return
        }

        val client = biometricClient ?: run {
            Log.e("CardReaderViewModel", "Biometric client not ready")
            onReady(null)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val subject = NSubject()
                var loadedCount = 0

                wsqList.forEach { wsq ->
                    try {
                        NImage.fromMemory(NBuffer.fromArray(wsq), NImageFormat.getWSQ())?.let { img ->
                            subject.fingers.add(NFinger().also { it.image = img })
                            loadedCount++
                        }
                    } catch (e: Exception) {
                        Log.w("CardReaderViewModel", "WSQ decode error: ${e.message}")
                    }
                }

                if (loadedCount == 0) {
                    Log.e("CardReaderViewModel", "No WSQ images could be decoded")
                    main.post { onReady(null) }
                    subject.dispose()
                    return@launch
                }

                val status = client.createTemplate(subject)
                if (status != NBiometricStatus.OK) {
                    Log.e("CardReaderViewModel", "createTemplate failed: $status")
                    main.post { onReady(null) }
                    subject.dispose()
                    return@launch
                }

                val templateBytes = subject.templateBuffer?.toByteArray()
                Log.d("CardReaderViewModel",
                    "Card fingerprint template created: ${templateBytes?.size} bytes " +
                            "from $loadedCount finger(s)")

                subject.dispose()
                main.post { onReady(templateBytes) }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "extractAndStoreFingerprintFromCard error", e)
                main.post { onReady(null) }
            }
        }
    }


    fun captureFingerprint() {
        _fingerprintCaptureState.value = FingerprintCaptureState.WaitingForFinger
        CoroutineScope(Dispatchers.IO).launch {
            val mgr = fpManager ?: run {
                main.post { _fingerprintCaptureState.value = FingerprintCaptureState.Failure("Sensor not initialised") }
                return@launch
            }
            when (val r = mgr.capture()) {
                is CaptureResult.Success ->
                    main.post { _fingerprintCaptureState.value = FingerprintCaptureState.Success(r.template, r.image) }
                is CaptureResult.Failure ->
                    main.post { _fingerprintCaptureState.value = FingerprintCaptureState.Failure(r.message) }
                CaptureResult.Timeout ->
                    main.post { _fingerprintCaptureState.value = FingerprintCaptureState.Failure("No finger detected. Please try again.") }
            }
        }
    }

    fun captureFingerprintWithNeurotec(onResult: (ByteArray?) -> Unit) {
        _fingerprintCaptureState.value = FingerprintCaptureState.WaitingForFinger
        CoroutineScope(Dispatchers.IO).launch {
            val mgr = fpManager ?: run {
                main.post {
                    _fingerprintCaptureState.value = FingerprintCaptureState.Failure("Sensor not initialised")
                    onResult(null)
                }
                return@launch
            }

            // Capture from BioMini
            when (val r = mgr.capture()) {
                is CaptureResult.Success -> {
                    // Get BMP image bytes from scanner
                    val bmpBytes = mgr.getCapturedImageBmp()
                    if (bmpBytes == null) {
                        main.post {
                            _fingerprintCaptureState.value = FingerprintCaptureState.Failure("Failed to get image from scanner")
                            onResult(null)
                        }
                        return@launch
                    }
                    // Extract Neurotec template from image
                    val neurotecTemplate = NeurotecFingerprintHelper.extractTemplateFromImage(
                        getApplication(), bmpBytes
                    )
                    main.post {
                        if (neurotecTemplate != null) {
                            _fingerprintCaptureState.value = FingerprintCaptureState.Success(neurotecTemplate, r.image)
                            onResult(neurotecTemplate)
                        } else {
                            _fingerprintCaptureState.value = FingerprintCaptureState.Failure("Failed to extract fingerprint template")
                            onResult(null)
                        }
                    }
                }
                is CaptureResult.Failure -> main.post {
                    _fingerprintCaptureState.value = FingerprintCaptureState.Failure(r.message)
                    onResult(null)
                }
                CaptureResult.Timeout -> main.post {
                    _fingerprintCaptureState.value = FingerprintCaptureState.Failure("No finger detected. Please try again.")
                    onResult(null)
                }
            }
        }
    }

    fun resetFingerprintCapture() {
        _fingerprintCaptureState.value = FingerprintCaptureState.Idle
    }

    fun scanAndIdentifyFingerprint() {
        _fingerprintVerifyState.value = FingerprintVerifyState.Scanning
        CoroutineScope(Dispatchers.IO).launch {
            val mgr = fpManager ?: run {
                main.post { _fingerprintVerifyState.value = FingerprintVerifyState.Error("Sensor not initialised") }
                return@launch
            }
            val capture = mgr.capture()
            if (capture !is CaptureResult.Success) {
                val msg = when (capture) {
                    is CaptureResult.Failure -> capture.message
                    CaptureResult.Timeout -> "No finger detected. Try again."
                    else -> "Capture failed"
                }
                main.post { _fingerprintVerifyState.value = FingerprintVerifyState.Error(msg) }
                return@launch
            }
            val probe = capture.template
            val profiles = AppDatabase.getInstance(getApplication()).profileDao().getAllProfile()
                .filter { it.fingerprintTemplate != null }
            if (profiles.isEmpty()) {
                main.post { _fingerprintVerifyState.value = FingerprintVerifyState.Error("No enrolled fingerprints in database") }
                return@launch
            }
            val result = mgr.identify(probe, profiles.map { it.fingerprintTemplate!! })
            main.post {
                if (result != null) {
                    val (idx, _) = result   // verify() returns Boolean — no score to surface
                    val profile = profiles[idx]
                    _fingerprintVerifyState.value = FingerprintVerifyState.Matched(profile)
                    logAccessAttempt(lagId = profile.lagId, name = profile.name, accessGranted = true, accessType = "FINGERPRINT")
                    clockInOut(profile.lagId, profile.name, action = null) { _, msg ->
                        Log.d("CardReaderViewModel", "Auto clock-in: $msg")
                    }
                } else {
                    logAccessAttempt(lagId = "UNKNOWN", name = "Unknown", accessGranted = false, accessType = "FINGERPRINT")
                    _fingerprintVerifyState.value = FingerprintVerifyState.NoMatch
                }
            }
        }
    }

    fun resetFingerprintVerify() {
        _fingerprintVerifyState.value = FingerprintVerifyState.Idle
    }

    // Save profile

    fun saveProfile(
        name: String,
        lagId: String,
        faceTemplate: ByteArray,
        faceImage: ByteArray,
        fingerprintTemplate: ByteArray? = null,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        dbExecutor.execute {
            try {
                Log.d("CardReaderViewModel", "saveProfile called - fingerprintTemplate: ${fingerprintTemplate?.size ?: "NULL"}")
                val compressedImage = compressFaceImage(faceImage)
                val thumbnail = createThumbnail(faceImage)
                val profileEntity = ProfileEntity(
                    name = name, lagId = lagId,
                    faceTemplate = faceTemplate, faceImage = compressedImage,
                    thumbnail = thumbnail, fingerprintTemplate = fingerprintTemplate
                )
                checkForDuplicatesLocally(lagId, faceTemplate) { isDuplicate, duplicateType, existingProfile ->
                    if (isDuplicate) {
                        val errorMsg = when (duplicateType) {
                            "LAG ID" -> "LAG ID '$lagId' is already registered to ${existingProfile?.name}"
                            "Face"   -> "This face is already registered as ${existingProfile?.name}"
                            else     -> "Profile already exists"
                        }
                        main.post { onError(errorMsg) }
                        return@checkForDuplicatesLocally
                    }
                    if (isServerAvailable) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val serverResult = syncManager.saveProfileToServer(profileEntity)
                            if (serverResult.isSuccess) {
                                try {
                                    val profileId = AppDatabase.getInstance(getApplication()).profileDao().insert(profileEntity)
                                    cachedProfiles = null; cacheTimestamp = 0
                                    main.post { onSuccess(profileId) }
                                } catch (e: Exception) {
                                    main.post { onError("Saved to server but failed locally: ${e.message}") }
                                }
                            } else {
                                main.post { onError(serverResult.exceptionOrNull()?.message ?: "Failed to save profile") }
                            }
                        }
                    } else {
                        try {
                            val profileId = AppDatabase.getInstance(getApplication()).profileDao().insert(profileEntity)
                            cachedProfiles = null; cacheTimestamp = 0
                            main.post { onSuccess(profileId) }
                        } catch (e: Exception) {
                            main.post { onError(e.message ?: "Unknown error") }
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun checkForDuplicatesLocally(
        lagId: String, faceTemplate: ByteArray,
        onResult: (Boolean, String?, ProfileEntity?) -> Unit
    ) {
        dbExecutor.execute {
            try {
                val existingByLagId = AppDatabase.getInstance(getApplication()).profileDao().getProfileByLagId(lagId)
                if (existingByLagId != null) { main.post { onResult(true, "LAG ID", existingByLagId) }; return@execute }

                val allProfiles = AppDatabase.getInstance(getApplication()).profileDao().getAllProfile()
                if (allProfiles.isEmpty()) { main.post { onResult(false, null, null) }; return@execute }

                val newFaceSubject = NSubject()
                newFaceSubject.setTemplateBuffer(NBuffer(faceTemplate))
                var bestMatchScore = 0; var bestMatchProfile: ProfileEntity? = null

                allProfiles.forEach { profile ->
                    try {
                        val existingSubject = NSubject()
                        existingSubject.setTemplateBuffer(NBuffer(profile.faceTemplate))
                        val matchStatus = biometricClient?.verify(newFaceSubject, existingSubject)
                        if (matchStatus == NBiometricStatus.OK) {
                            val score = newFaceSubject.matchingResults?.getOrNull(0)?.score ?: 0
                            if (score > bestMatchScore) { bestMatchScore = score; bestMatchProfile = profile }
                        }
                    } catch (e: Exception) { Log.e("CardReaderViewModel", "Error comparing with ${profile.name}", e) }
                }

                if (bestMatchScore >= 90 && bestMatchProfile != null) main.post { onResult(true, "Face", bestMatchProfile) }
                else main.post { onResult(false, null, null) }

            } catch (e: Exception) {
                Log.e("CardReaderViewModel", "Error checking duplicates", e)
                main.post { onResult(false, null, null) }
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
            stream.close(); thumbnail.recycle(); bitmap.recycle(); result
        } catch (e: Exception) { imageBytes }
    }

    private fun compressFaceImage(imageBytes: ByteArray): ByteArray {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val resized = if (bitmap.width > 300 || bitmap.height > 300) {
                val ratio = minOf(300f / bitmap.width, 300f / bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
            } else bitmap
            val stream = java.io.ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val compressed = stream.toByteArray(); stream.close()
            if (bitmap != resized) resized.recycle(); bitmap.recycle(); compressed
        } catch (e: Exception) { imageBytes }
    }

    // Profile queries

    fun getAllProfile(callback: (List<ProfileEntity>) -> Unit, forceRefresh: Boolean = false) {
        if (forceRefresh) { cachedProfiles = null; cacheTimestamp = 0 }
        if (forceRefresh && isServerAvailable && !isSyncing) {
            isSyncing = true
            CoroutineScope(Dispatchers.IO).launch {
                try { syncManager.loadAllProfilesFromServer() } catch (e: Exception) { }
                main.post { isSyncing = false; getLocalProfiles(callback) }
            }
            return
        }
        if (!forceRefresh && cachedProfiles != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_DURATION_MS) {
            main.post { callback(cachedProfiles!!) }; return
        }
        getLocalProfiles(callback)
    }

    private fun getLocalProfiles(callback: (List<ProfileEntity>) -> Unit) {
        dbExecutor.execute {
            try {
                val profileList = AppDatabase.getInstance(getApplication()).profileDao().getAllProfile()
                cachedProfiles = profileList; cacheTimestamp = System.currentTimeMillis()
                main.post { callback(profileList) }
            } catch (e: Exception) { main.post { callback(emptyList()) } }
        }
    }

    fun deleteProfile(profileId: Long, onComplete: () -> Unit) {
        dbExecutor.execute {
            try {
                val profile = AppDatabase.getInstance(getApplication()).profileDao().getProfileById(profileId)
                profile?.let { profileToDelete ->
                    AppDatabase.getInstance(getApplication()).profileDao().delete(profileToDelete)
                    cachedProfiles = null
                    if (isServerAvailable) {
                        CoroutineScope(Dispatchers.IO).launch { syncManager.deleteProfileFromServer(profileToDelete.lagId) }
                    }
                    main.post { onComplete() }
                } ?: main.post { status = "Profile not found"; onComplete() }
            } catch (e: Exception) { main.post { onComplete() } }
        }
    }

    fun refreshFromServer(onComplete: (Int, String?) -> Unit) {
        if (!isServerAvailable) { main.post { onComplete(0, "Server not available") }; return }
        if (isSyncing) { main.post { onComplete(0, "Sync already in progress") }; return }
        isSyncing = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = syncManager.loadAllProfilesFromServer()
                main.post {
                    isSyncing = false; cachedProfiles = null
                    if (result.isSuccess) onComplete(result.getOrDefault(0), null)
                    else onComplete(0, result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) { main.post { isSyncing = false; onComplete(0, e.message) } }
        }
    }

    //  Face verification

    fun verifyFaceAgainstDatabase(onResult: (ProfileEntity?, Int) -> Unit) {
        executor.execute {
            try {
                main.post { status = "Searching database..." }
                val capturedTemplate = capturedProfileTemplate ?: run {
                    main.post { status = "No captured face found"; onResult(null, 0) }; return@execute
                }
                val allProfile = AppDatabase.getInstance(getApplication()).profileDao().getAllProfile()
                if (allProfile.isEmpty()) { main.post { status = "No profile in database"; onResult(null, 0) }; return@execute }

                main.post { status = "Comparing with ${allProfile.size} profiles..." }
                val capturedSubject = NSubject(); capturedSubject.setTemplateBuffer(NBuffer(capturedTemplate))
                var bestMatch: ProfileEntity? = null; var bestScore = 0

                allProfile.forEach { profile ->
                    try {
                        val profileSubject = NSubject(); profileSubject.setTemplateBuffer(NBuffer(profile.faceTemplate))
                        val matchStatus = biometricClient?.verify(capturedSubject, profileSubject)
                        if (matchStatus == NBiometricStatus.OK) {
                            val score = capturedSubject.matchingResults?.getOrNull(0)?.score ?: 0
                            if (score > bestScore) { bestScore = score; bestMatch = profile }
                        }
                    } catch (e: Exception) { Log.e("CardReaderViewModel", "Error matching with ${profile.name}", e) }
                }

                val finalMatch = if (bestScore >= 48) bestMatch else null
                if (finalMatch != null) {
                    logAccessAttempt(lagId = finalMatch.lagId, name = finalMatch.name, accessGranted = true, accessType = "FACE")
                    clockInOut(finalMatch.lagId, finalMatch.name, action = null) { _, msg -> Log.d("CardReaderViewModel", "Auto clock-in: $msg") }
                } else {
                    logAccessAttempt(lagId = "UNKNOWN", name = "Unknown", accessGranted = false, accessType = "FACE")
                }
                main.post {
                    status = if (finalMatch != null) "Match found: ${finalMatch.name} (Score: $bestScore)"
                    else if (bestScore > 0) "No match found (Best score: $bestScore)" else "No match found"
                    onResult(finalMatch, bestScore)
                }
            } catch (e: Exception) {
                main.post { status = "Verification error: ${e.message}"; onResult(null, 0) }
            }
        }
    }

    // Image helpers

    private fun fixImageOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.let { ExifInterface(it) }; inputStream?.close()
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.preScale(1f, -1f)
            }
            if (!matrix.isIdentity) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) else bitmap
        } catch (e: Exception) { bitmap }
    }

    fun processFaceFromUri(context: Context, uri: Uri, onSuccess: (Bitmap, ByteArray) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            try {
                stopCapture()
                val inputStream = context.contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = false; inSampleSize = calculateInSampleSize(this, 512, 512) }
                val originalBitmap = BitmapFactory.decodeStream(inputStream, null, options); inputStream?.close()
                if (originalBitmap == null) { main.post { status = "Failed to read image"; onError("Failed to read image from gallery") }; return@execute }
                val rotatedBitmap = fixImageOrientation(context, uri, originalBitmap)
                main.post { capturedProfileFace = rotatedBitmap }
                if (biometricClient == null) {
                    try { NeurotecLicenseHelper.obtainFaceLicenses(context); main.post { initClient() } } catch (e: Exception) { }
                }
                if (biometricClient == null) { main.post { status = "System not ready"; onError("Face detection system not initialized.") }; return@execute }
                val stream = java.io.ByteArrayOutputStream()
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val imageBytes = stream.toByteArray(); stream.close()
                processFaceFromImage(imageBytes, rotatedBitmap, onSuccess, onError)
            } catch (e: Exception) {
                main.post { status = "Error: ${e.message}"; onError("Error reading image: ${e.message}") }
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight; val width = options.outWidth; var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2; val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    fun processFaceFromImage(imageBytes: ByteArray, previewBitmap: Bitmap, onSuccess: (Bitmap, ByteArray) -> Unit, onError: (String) -> Unit) {
        try {
            val nImage = NImage.fromMemory(NBuffer(imageBytes))
            val subject = NSubject(); val face = NFace(); face.image = nImage; subject.faces.add(face)
            val task = biometricClient?.createTask(EnumSet.of(NBiometricOperation.DETECT_SEGMENTS, NBiometricOperation.CREATE_TEMPLATE), subject)
            task?.let { it.timeout = 15000; biometricClient?.performTask(it) }
            when (task!!.status) {
                NBiometricStatus.OK -> {
                    val template = subject.templateBuffer?.toByteArray()
                    if (template != null && template.isNotEmpty()) main.post { onSuccess(previewBitmap, template) }
                    else main.post { status = "Failed to create template"; onError("Failed to extract face template") }
                }
                NBiometricStatus.BAD_OBJECT -> main.post { status = "No face detected"; onError("No face detected. Use a clear photo.") }
                NBiometricStatus.TIMEOUT    -> main.post { status = "Processing timeout"; onError("Processing took too long. Try a different photo.") }
                else -> main.post { status = "Detection failed"; onError("Could not detect face. Try another photo.") }
            }
            task?.dispose(); nImage.dispose()
        } catch (e: Exception) {
            main.post { status = "Error: ${e.message}"; onError("Error processing face: ${e.message}") }
        }
    }



    // Card / access

    fun checkLagIdInDatabase(lagId: String, onResult: (ProfileEntity?, Boolean) -> Unit) {
        executor.execute {
            try {
                main.post { status = "Verifying card..." }
                val profile = AppDatabase.getInstance(getApplication()).profileDao().getProfileByLagId(lagId)
                val exists = profile != null
                if (exists && profile != null) {
                    logAccessAttempt(lagId = lagId, name = profile.name, accessGranted = true, accessType = "CARD")
                    clockInOut(lagId, profile.name, action = null) { _, msg -> Log.d("CardReaderViewModel", "Auto clock-in: $msg") }
                } else {
                    logAccessAttempt(lagId = lagId, name = "Unknown", accessGranted = false, accessType = "CARD")
                }
                main.post {
                    status = if (exists) "Access Granted: ${profile?.name}" else "Access Denied: LAG ID not registered"
                    onResult(profile, exists)
                }
            } catch (e: Exception) {
                main.post { status = "Error checking card"; onResult(null, false) }
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun logAccessAttempt(lagId: String, name: String, accessGranted: Boolean, accessType: String = "CARD") {
        if (!isServerAvailable) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    getApplication<Application>().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                val localTimeZone = Calendar.getInstance()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = localTimeZone.timeZone }
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = localTimeZone.timeZone }
                val accessLog = AccessLog(lagId = lagId, name = name, accessGranted = accessGranted, accessType = accessType,
                    deviceId = deviceId, timestamp = System.currentTimeMillis(),
                    date = dateFormat.format(Date()), time = timeFormat.format(Date()))
                val response = RetrofitClient.apiService.logAccess(accessLog)
                if (response.isSuccessful) Log.d("CardReaderViewModel", "Access logged: $name")
            } catch (e: Exception) { Log.d("CardReaderViewModel", "Failed to log access", e) }
        }
    }

    fun clockInOut(lagId: String, name: String, action: String? = null, onResult: (Boolean, String) -> Unit) {
        if (!isServerAvailable) { main.post { onResult(false, "") }; return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val clockAction = if (action != null) {
                    action
                } else {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    try {
                        val statusResponse = RetrofitClient.apiService.getAttendance(lagId = lagId, startDate = today, endDate = today, limit = 1)
                        if (statusResponse.isSuccessful) {
                            val records = statusResponse.body()?.takeIf { it.success == true }?.records ?: emptyList()
                            if (records.isEmpty()) "IN"
                            else if (records.first().status == "ACTIVE") "OUT" else "IN"
                        } else "IN"
                    } catch (e: Exception) { "IN" }
                }
                val response = RetrofitClient.apiService.clockInOut(ClockRequest(lagId, name, clockAction))
                main.post {
                    if (response.isSuccessful && response.body()?.success == true) {
                        val attendance = response.body()?.attendance
                        val sessions = attendance?.sessions
                        if (!sessions.isNullOrEmpty()) {
                            val latestSession = sessions.last()
                            val serverTimestamp = if (clockAction == "IN") latestSession.clockIn else latestSession.clockOut
                            serverTimestamp?.let { timestamp ->
                                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }.format(Date(timestamp))
                                val message = if (clockAction == "IN") "Clocked in at $timeStr"
                                else "Clocked out at $timeStr\nSession: ${latestSession.durationFormatted ?: "0h 0m"}\nTotal today: ${attendance.totalDurationFormatted ?: "0h 0m"}"
                                onResult(true, message); return@post
                            }
                        }
                        onResult(true, " ")
                    } else { onResult(false, response.body()?.message ?: "") }
                }
            } catch (e: Exception) { main.post { onResult(false, e.message ?: "Unknown error") } }
        }
    }

    fun resetFingerprintSensorState() {
        synchronized(sensorLock) {
            isSensorOpen = false
            fpManager = null
        }
    }

    // Misc

    fun invalidateCache() { cachedProfiles = null; cacheTimestamp = 0 }
    fun clearCapturedStaffFace() { capturedProfileFace = null; capturedProfileTemplate = null }

    override fun onCleared() {
        super.onCleared()
        stopCapture()
        closeFingerprintSensor()
        executor.shutdown()
        biometricClient?.dispose()
    }

    init { initializeSyncManager() }
}