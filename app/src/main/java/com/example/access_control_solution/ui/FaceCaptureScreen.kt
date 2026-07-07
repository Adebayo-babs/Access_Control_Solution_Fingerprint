package com.example.access_control_solution.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access_control_solution.R
import com.example.access_control_solution.data.ProfileEntity
import com.example.access_control_solution.viewModel.CardReaderViewModel
import com.neurotec.lang.NCore
import kotlinx.coroutines.delay


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceCaptureScreen(
    onBack: () -> Unit,
    viewModel: CardReaderViewModel,
    onPlayFaceDetectedSound: () -> Unit,
    onVerificationComplete: ((ProfileEntity?, Int) -> Unit)? = null,
    isEnrollment: Boolean = false
) {

    BackHandler { onBack() }

    val context = LocalContext.current
    val status = viewModel.status
    val detectionFeedback = viewModel.detectionFeedback
    val isCapturing = viewModel.isCapturing
    val dialogState by viewModel.dialogState.collectAsState()

    // `status` is only ever set for terminal states (errors, "no face detected",
    // database verification progress). The live per-frame progress ("Looking for
    // face...", "Perfect! Face captured!") lives in detectionFeedback instead, and
    // only becomes meaningful once the capture loop has actually started.
    val displayMessage = when {
        status.isNotEmpty() -> status
        isCapturing -> detectionFeedback.overallMessage
        else -> "Initializing camera..."
    }


    LaunchedEffect(Unit) {
        NCore.setContext(context)
        viewModel.onFaceDetectedSound = {
            onPlayFaceDetectedSound()
        }
        // Flag this capture as enrollment-bound (or not) up front, so the ViewModel state
        // survives navigation even after this screen is disposed.
        viewModel.markPendingEnrollmentCapture(isEnrollment)
        viewModel.initialize()
    }

    // Handle dialog auto-dismiss and trigger verification (or hand back to caller for enrollment)

    LaunchedEffect(dialogState.showDialog) {
        if (dialogState.showDialog && dialogState.message.contains(
                "Face Detected",
                ignoreCase = true
            )
        ) {
            when {
                isEnrollment -> {
                    // Face + template are already stored on the ViewModel by
                    // startAutomaticCapture(). Give the user a beat to see the confirmation,
                    // then hand control straight back to AddProfileScreen.
                    delay(600)
                    viewModel.hideDialog()
                    viewModel.stopCapture()
                    onBack()
                }
                onVerificationComplete != null -> {
                    // Trigger verification against database
                    viewModel.verifyFaceAgainstDatabase { staff, score ->
                        viewModel.hideDialog()
                        viewModel.reset()
                        onVerificationComplete(staff, score)
                    }
                }
                else -> {
                    // If no callback provided, just dismiss and go back
                    delay(3000)
                    viewModel.hideDialog()
                    viewModel.reset()
                    onBack() // Navigate back to main menu
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopCapture()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEnrollment) "Capture Face" else "Face Verification") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1C1C1C),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Camera preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF1C1C1C))
                ) {
                    if (viewModel.useNeurotecCamera) {
                        CameraPreviewColoured(
                            modifier = Modifier.fillMaxSize(),
                            viewModel = viewModel
                        )
                    } else {
                        CameraPreviewGrayScale(
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Camera switch button
                        FloatingActionButton(
                            onClick = {
                                Log.d("FaceCaptureScreen", "CAMERA SWITCH BUTTON CLICKED")
                                viewModel.toggleCameraPreview()

                            },
                            containerColor = Color(0xFF424242),
                            contentColor = Color.White,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.cameraswitch),
                                contentDescription = "Switch camera",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Status message
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            displayMessage.contains("error", ignoreCase = true) -> Color(0xFFD32F2F)
                            displayMessage.contains("success", ignoreCase = true) ||
                                    displayMessage.contains("captured", ignoreCase = true) ||
                                    displayMessage.contains("perfect", ignoreCase = true) -> Color(
                                0xFF4CAF50
                            )

                            displayMessage.contains("matching", ignoreCase = true) ||
                                    displayMessage.contains("processing", ignoreCase = true) ||
                                    displayMessage.contains("detecting", ignoreCase = true) ||
                                    displayMessage.contains("looking", ignoreCase = true) ||
                                    displayMessage.contains("initializing", ignoreCase = true) -> Color(
                                0xFF2196F3
                            )

                            else -> Color(0xFF2C2C2C)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (displayMessage.contains("matching", ignoreCase = true) ||
                            displayMessage.contains("processing", ignoreCase = true) ||
                            displayMessage.contains("detecting", ignoreCase = true) ||
                            displayMessage.contains("looking", ignoreCase = true) ||
                            displayMessage.contains("initializing", ignoreCase = true)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        Text(
                            text = displayMessage,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}