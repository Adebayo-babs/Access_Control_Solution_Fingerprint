package com.example.access_control_solution.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.access_control_solution.viewModel.CardReaderViewModel
import com.neurotec.devices.NCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun CameraPreviewColoured(
    modifier: Modifier = Modifier,
    viewModel: CardReaderViewModel = viewModel()
) {
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastFrameTime by remember { mutableStateOf(0L) }

    // Continuously capture frames from the active camera
    LaunchedEffect(viewModel.biometricClient) {
        while (isActive) {
            try {
                withContext(Dispatchers.IO) {
                    // Get the current active camera from biometric client
                    val camera = viewModel.biometricClient?.faceCaptureDevice as? NCamera

                    if (camera != null && camera.isCapturing) {
                        // Try to get current frame from the face being captured
                        val face = viewModel.currentSubject?.faces?.firstOrNull()
                        val imageBytes = face?.image?.save()?.toByteArray()

                        if (imageBytes != null && imageBytes.isNotEmpty()) {
                            val newBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                            // Only update if we got a new frame
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastFrameTime > 30) { // Throttle to ~30 FPS
                                withContext(Dispatchers.Main) {
                                    currentFrame = newBitmap
                                    lastFrameTime = currentTime
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NeurotecPreview", "Error capturing frame", e)
            }
            delay(33) // Poll at ~30 FPS
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        currentFrame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Camera preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}