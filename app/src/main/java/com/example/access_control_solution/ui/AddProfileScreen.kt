package com.example.access_control_solution.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access_control_solution.viewModel.CardReaderViewModel


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
    var showSuccessDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) } // NEW: Prevent double save

    val status by remember { derivedStateOf { viewModel.status } }

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

    // Initialize Neurotec when screen loads
    LaunchedEffect(Unit) {
        viewModel.initialize()
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
                        capturedFace != null -> {
                            Image(
                                bitmap = capturedFace!!.asImageBitmap(),
                                contentDescription = "Selected Face",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )

                            if (isProcessing) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(48.dp),
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        isProcessing -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Color(0xFF00A86B)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Processing...",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
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

            Spacer(modifier = Modifier.height(16.dp))

            // Status Text
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    fontSize = 14.sp,
                    color = when {
                        status.contains("success", ignoreCase = true) -> Color(0xFF00A86B)
                        status.contains("error", ignoreCase = true) ||
                                status.contains("failed", ignoreCase = true) -> Color.Red
                        else -> Color.Gray
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Change Photo Button
            if (capturedFace != null && !isProcessing) {
                TextButton(
                    onClick = {
                        capturedFace = null
                        capturedTemplate = null
                        selectedImageUri = null
                        errorMessage = ""
                        imagePickerLauncher.launch("image/*")
                    },
                    enabled = !isSaving
                ) {
                    Text(
                        text = "Change Photo",
                        color = Color(0xFF2196F3),
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Staff Details Form
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full Name") },
                placeholder = { Text("Enter full name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00A86B),
                    focusedLabelColor = Color(0xFF00A86B)
                ),
                enabled = !isProcessing && !isSaving
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = lagId,
                onValueChange = { lagId = it },
                label = { Text("LAG ID") },
                placeholder = { Text("Enter LAG ID") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00A86B),
                    focusedLabelColor = Color(0xFF00A86B)
                ),
                enabled = !isProcessing && !isSaving
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Error Message
            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFD32F2F),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    // Prevent multiple clicks
                    if (isSaving) return@Button

                    errorMessage = ""

                    when {
                        name.isBlank() -> errorMessage = "Please enter name"
                        lagId.isBlank() -> errorMessage = "Please enter LAG ID"
                        capturedFace == null || capturedTemplate == null ->
                            errorMessage = "Please select a profile photo"
                        else -> {
                            isSaving = true

                            // Convert bitmap to bytes
                            val imageBytes = capturedFace?.let { bitmap ->
                                val stream = java.io.ByteArrayOutputStream()
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                                stream.toByteArray()
                            }

                            if (imageBytes != null && capturedTemplate != null) {
                                viewModel.saveProfile(
                                    name = name.trim(),
                                    lagId = lagId.trim(),
                                    faceTemplate = capturedTemplate!!,
                                    faceImage = imageBytes,
                                    onSuccess = {
                                        isSaving = false
                                        showSuccessDialog = true
                                    },
                                    onError = { error ->
                                        isSaving = false
                                        errorMessage = "Error: $error"
                                    }
                                )
                            } else {
                                isSaving = false
                                errorMessage = "Missing face data"
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
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
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Saving...")
                } else {
                    Text(
                        text = "Save Profile",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Success Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = Color(0xFF00A86B),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Success!",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = "Profile for $name has been added successfully.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSuccessDialog = false
                        onProfileAdded()
                    }
                ) {
                    Text("View All Profiles", color = Color(0xFF00A86B))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSuccessDialog = false
                        // Reset form
                        name = ""
                        lagId = ""
                        capturedFace = null
                        capturedTemplate = null
                        selectedImageUri = null
                        errorMessage = ""
                        isSaving = false
                    }
                ) {
                    Text("Add Another", color = Color(0xFF2196F3))
                }
            }
        )
    }
}