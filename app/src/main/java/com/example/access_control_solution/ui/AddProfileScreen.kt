package com.example.access_control_solution.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitel.api.Fingerprint
import com.example.access_control_solution.FingerprintCaptureState
import com.example.access_control_solution.TelpoFingerprintManager
import com.example.access_control_solution.viewModel.CardReaderViewModel
import com.example.neurotecsdklibrary.NeurotecLicenseHelper
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProfileScreen(
    viewModel: CardReaderViewModel,
    onBack: () -> Unit,
    onProfileAdded: () -> Unit
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var lagId by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var capturedFace by remember { mutableStateOf<Bitmap?>(null) }
    var capturedTemplate by remember { mutableStateOf<ByteArray?>(null) }

    // Fingerprint state
    var fingerprintTemplate by remember { mutableStateOf<ByteArray?>(null) }
    var fingerprintBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isFingerprintSensorReady by remember { mutableStateOf(false) }


    var showSuccessDialog by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) } // Prevent double save

    val cardData by viewModel.cardData.collectAsState()
    val cardReadError by viewModel.cardReadError.collectAsState()

    // Get biometrics from card tap
    val faceImageFromCard by viewModel.faceImageFromCard.collectAsState()
    val fingerprintsFromCard by viewModel.fingerprintFromCard.collectAsState()

    val fpCaptureState by viewModel.fingerprintCaptureState.collectAsState()

    // Auto-fill name+lagId from card
    LaunchedEffect(cardData) {
        cardData?.let { (fullName, lagIdFromCard) ->
            name = fullName
            lagId = lagIdFromCard
            // Clear card data after using it
            viewModel.clearCardData()
        }
    }

    // Auto-fill face image from
    LaunchedEffect(faceImageFromCard) {
        faceImageFromCard?.let { bytes ->
            // Detect image format by checking magic bytes
            val isJpeg2000 = bytes.size > 12 &&
                    bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
                    bytes[2] == 0x00.toByte() && bytes[3] == 0x0C.toByte() &&
                    bytes[4] == 0x6A.toByte() && bytes[5] == 0x50.toByte()  // JP2 signature

            val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

            Log.d("AddProfileScreen", "Face image bytes: ${bytes.size}, isJPEG=$isJpeg, isJP2=$isJpeg2000")

            // Try to decode with BitmapFactory first (handles JPEG, PNG, etc.)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            if (bmp != null) {
                // BitmapFactory decoded it successfully — convert to standard JPEG for Neurotec
                capturedFace = bmp
                isProcessing = true

                val jpegBytes = ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.toByteArray()
                }

                viewModel.processFaceFromImage(
                    imageBytes = jpegBytes,
                    previewBitmap = bmp,
                    onSuccess = { _, template ->
                        capturedTemplate = template
                        isProcessing = false
                        errorMessage = ""
                    },
                    onError = { err ->
                        isProcessing = false
                        errorMessage = "Card face loaded but template extraction failed: $err"
                    }
                )
            } else {

                Log.w("AddProfileScreen", "BitmapFactory failed, trying JP2 extraction...")

                val jpegStartIndex = bytes.indices.firstOrNull { i ->
                    i + 1 < bytes.size &&
                            bytes[i] == 0xFF.toByte() &&
                            bytes[i + 1] == 0xD8.toByte()
                }

                if (jpegStartIndex != null) {
                    val jpegBytes = bytes.copyOfRange(jpegStartIndex, bytes.size)
                    val jpegBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

                    if (jpegBmp != null) {
                        capturedFace = jpegBmp
                        isProcessing = true

                        val reEncodedJpeg = ByteArrayOutputStream().use { out ->
                            jpegBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                            out.toByteArray()
                        }

                        viewModel.processFaceFromImage(
                            imageBytes = reEncodedJpeg,
                            previewBitmap = jpegBmp,
                            onSuccess = { _, template ->
                                capturedTemplate = template
                                isProcessing = false
                                errorMessage = ""
                            },
                            onError = { err ->
                                isProcessing = false
                                errorMessage = "Card face loaded but template extraction failed: $err"
                            }
                        )
                    } else {
                        errorMessage = "Card face image format not supported"
                    }
                } else {
                    errorMessage = "Card face image format not supported (not JPEG or JP2)"
                }
            }
        }
    }
    // Auto-fill fingerprint from card
    // Converts WSQ  to NTemplate before storing
    LaunchedEffect(fingerprintsFromCard) {
        if (fingerprintsFromCard.isNotEmpty()) {

            val alreadyConverted = fingerprintsFromCard.firstOrNull { it.format == "NEUROTEC" }
            if (alreadyConverted?.template != null) {
                fingerprintTemplate = alreadyConverted.template
                fingerprintBitmap = null
                return@LaunchedEffect
            }

            // Fallback: raw WSQ still in list — convert now
            // (handles case where extractAndStoreFingerprintFromCard hasn't finished yet)
            val wsqFingers = fingerprintsFromCard.filter {
                it.format == "WSQ" || it.format == "RAW"
            }
            if (wsqFingers.isEmpty()) return@LaunchedEffect

            isProcessing = true
            errorMessage = ""

            viewModel.extractAndStoreFingerprintFromCard(wsqFingers) { template ->
                isProcessing = false
                if (template != null) {
                    fingerprintTemplate = template
                    fingerprintBitmap = null
                } else {
                    errorMessage = "Could not extract fingerprint template from card"
                }
            }
        }
    }

    // Show error if card read fails
    LaunchedEffect(cardReadError) {
        cardReadError?.let { error ->
            errorMessage = error
            viewModel.clearCardData()
        }
    }

    // Handle fingerprint capture state changes
    LaunchedEffect(fpCaptureState) {
        when(val state = fpCaptureState) {
            is FingerprintCaptureState.Success -> {
                fingerprintTemplate = state.template
                fingerprintBitmap = state.image
                errorMessage = ""
            }
            is FingerprintCaptureState.Failure -> {
                errorMessage = state.message
            }
            else -> {}
        }

    }

//    val status by remember { derivedStateOf { viewModel.status } }
//
//
    // Clean up when screen is first loaded
    LaunchedEffect(Unit) {
        viewModel.clearCapturedStaffFace()
        viewModel.stopCapture()
        viewModel.hideDialog()
        viewModel.initializeForFingerprintOnly()
        viewModel.clearCardData() // Clear any previous card data
        viewModel.resetFingerprintCapture()
        // Open fingerprint sensor
        viewModel.openFingerprintSensor { success ->
            isFingerprintSensorReady = success
            if (!success) errorMessage = "Fingerprint sensor unavailable"
        }
    }

    // Clean up when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopCapture()
            viewModel.clearCapturedStaffFace()
            viewModel.clearCardData()
            viewModel.resetFingerprintCapture()
            viewModel.closeFingerprintSensor()
        }
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            isProcessing = true
            capturedFace = null
            capturedTemplate = null

            // Process the selected image to extract face template
            viewModel.processFaceFromUri(
                context = context,
                uri = it,
                onSuccess = { bitmap, template ->
                    capturedFace = bitmap
                    capturedTemplate = template
                    isProcessing = false
                    errorMessage = ""
                },
                onError = { error ->
                    errorMessage = error
                    isProcessing = false
                    selectedImageUri = null
                    capturedFace = null
                    capturedTemplate = null
                }
            )
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF00A86B),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // NFC hint card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "\uD83E\uDEAA",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Tap your card to auto-fill your name, photo & fingerprint\nor fill in manually below",
                        fontSize = 17.sp, color = Color(0xFF1976D2)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Face Photo
            Text("Face Photo",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Profile Picture Section
            Card(
                modifier = Modifier
                    .size(200.dp)
                    .clickable(enabled = !isProcessing && !isSaving) {
                        imagePickerLauncher.launch("image/*")
                    },
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF5F5F5)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isProcessing -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(Modifier.size(48.dp), color = Color(0xFF00A86B))
                                Spacer(Modifier.height(8.dp))
                                Text("Processing...", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                        capturedFace != null -> {
                            Image(
                                bitmap = capturedFace!!.asImageBitmap(),
                                contentDescription = "Selected Face",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        else -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Select Photo",
                                    modifier = Modifier.size(64.dp),
                                    tint = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Tap to select\nprofile photo",
                                    fontSize = 14.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            if (capturedFace != null && !isProcessing) {
                TextButton(onClick = {
                    capturedFace = null; capturedTemplate = null; selectedImageUri = null; errorMessage = ""
                    imagePickerLauncher.launch("image/*")
                }, enabled = !isSaving) {
                    Text("Change Photo", color = Color(0xFF2196F3), fontSize = 14.sp)
                }
            }

            // Badge when face was auto-filled from card
            if (faceImageFromCard != null && capturedFace != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        " Face loaded from card",
                        fontSize = 12.sp, color = Color(0xFF2E7D32),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            // Fingerprint Section
            Text(
                "Fingerprint",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        fingerprintTemplate != null -> Color(0xFFE8F5E9)
                        fpCaptureState is FingerprintCaptureState.WaitingForFinger -> Color(0xFFFFF8E1)
                        fpCaptureState is FingerprintCaptureState.Failure -> Color(0xFFFFEBEE)
                        else -> Color(0xFFF5F5F5)
                    }
                ),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        fingerprintTemplate != null -> {
                            // Enrolled — show success state
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("\uD83E\uDEC6", fontSize = 40.sp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (fingerprintsFromCard.isNotEmpty()) " Fingerprint from card"
                                        else " Fingerprint enrolled",
                                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
//                                    Text(
//                                        "${fingerprintTemplate!!.size} bytes",
//                                        fontSize = 12.sp, color = Color.Gray
//                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                fingerprintTemplate = null; fingerprintBitmap = null
                                viewModel.resetFingerprintCapture()
                            }) {
                                Text("Clear & Re-capture", color = Color(0xFFE53935), fontSize = 13.sp)
                            }
                        }

                        fpCaptureState is FingerprintCaptureState.WaitingForFinger -> {
                            // Scanning in progress
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(32.dp), color = Color(0xFFF57F17), strokeWidth = 3.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Place finger on scanner…", fontSize = 15.sp, color = Color(0xFFF57F17))
                            }
                        }
                        fpCaptureState is FingerprintCaptureState.Failure -> {
                            Text("❌", fontSize = 32.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                (fpCaptureState as FingerprintCaptureState.Failure).message,
                                fontSize = 13.sp,
                                color = Color(0xFFD32F2F),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    viewModel.resetFingerprintCapture()
                                    viewModel.captureFingerprintWithNeurotec { template ->
                                        if (template != null) fingerprintTemplate = template
                                    }
                                },
                                enabled = isFingerprintSensorReady && !isSaving,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Retry", fontSize = 14.sp)
                            }
                        }
                        else -> {
                            // Not yet enrolled
//                            Text("👆", fontSize = 48.sp)
//                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No fingerprint enrolled\n(optional — tap card or scan manually)",
                                fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (isFingerprintSensorReady) viewModel.captureFingerprint()
                                    else errorMessage = "Fingerprint sensor not available"
                                },
                                enabled = isFingerprintSensorReady && !isSaving,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Scan Fingerprint", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))


            // Form fields
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Full Name") }, placeholder = { Text("Enter full name") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00A86B), focusedLabelColor = Color(0xFF00A86B)),
                enabled = !isProcessing && !isSaving
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = lagId, onValueChange = { lagId = it },
                label = { Text("LAG ID") }, placeholder = { Text("Enter LAG ID") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00A86B), focusedLabelColor = Color(0xFF00A86B)),
                enabled = !isProcessing && !isSaving
            )

            // Error message
            if (errorMessage.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(errorMessage, color = Color(0xFFD32F2F), fontSize = 14.sp,
                        modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    if (isSaving) return@Button
                    errorMessage = ""
                    when {
                        name.isBlank()  -> errorMessage = "Please enter name"
                        lagId.isBlank() -> errorMessage = "Please enter LAG ID"
                        capturedFace == null || capturedTemplate == null ->
                            errorMessage = "Please select or capture a face photo"
                        else -> {
                            isSaving = true
                            Log.d("AddProfileScreen", "Saving profile - fingerprintTemplate: ${fingerprintTemplate?.size ?: "NULL"}")
                            val imageBytes = run {
                                val s = java.io.ByteArrayOutputStream()
                                capturedFace!!.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, s)
                                s.toByteArray()
                            }
                            viewModel.saveProfile(
                                name = name.trim(),
                                lagId = lagId.trim(),
                                faceTemplate = capturedTemplate!!,
                                faceImage = imageBytes,
                                fingerprintTemplate = fingerprintTemplate,
                                onSuccess = { isSaving = false; showSuccessDialog = true },
                                onError = { err ->
                                    isSaving = false
                                    if (err.contains("already registered", ignoreCase = true)) {
                                        duplicateMessage = err; showDuplicateDialog = true
                                    } else errorMessage = "Error: $err"
                                }
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00A86B),
                    disabledContainerColor = Color.Gray
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = name.isNotBlank() && lagId.isNotBlank() &&
                        capturedFace != null && capturedTemplate != null &&
                        !isProcessing && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text("Saving…")
                } else {
                    Text("Save Profile", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Duplicate dialog
            if (showDuplicateDialog) {
                AlertDialog(
                    onDismissRequest = { showDuplicateDialog = false },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text("⚠️", fontSize = 48.sp); Spacer(Modifier.height(8.dp))
                            Text("Duplicate Profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                        }
                    },
                    text = { Text(duplicateMessage, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    confirmButton = {
                        TextButton(onClick = { showDuplicateDialog = false; duplicateMessage = "" }) {
                            Text("OK", color = Color(0xFF00A86B))
                        }
                    }
                )
            }

            // Success dialog
            if (showSuccessDialog) {
                AlertDialog(
                    onDismissRequest = { },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00A86B), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Success!", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text("Profile for $name has been added.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    },
                    confirmButton = {
                        TextButton(onClick = { showSuccessDialog = false; onProfileAdded() }) {
                            Text("View All Profiles", color = Color(0xFF00A86B))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showSuccessDialog = false
                            name = ""; lagId = ""; capturedFace = null; capturedTemplate = null
                            selectedImageUri = null; fingerprintTemplate = null; fingerprintBitmap = null
                            errorMessage = ""; isSaving = false
//                            viewModel.resetFingerprintCapture()
                        }) {
                            Text("Add Another", color = Color(0xFF2196F3))
                        }
                    }
                )
            }
        }
    }
}