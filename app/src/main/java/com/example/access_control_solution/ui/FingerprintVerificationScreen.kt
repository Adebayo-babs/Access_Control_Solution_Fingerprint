package com.example.access_control_solution.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access_control_solution.FingerprintVerifyState
import com.example.access_control_solution.data.ProfileEntity
import com.example.access_control_solution.viewModel.CardReaderViewModel
import kotlinx.coroutines.time.delay


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FingerprintVerificationScreen(
    viewModel: CardReaderViewModel,
    onBack: () -> Unit
) {
    val verifyState by viewModel.fingerprintVerifyState.collectAsState()

    // Open sensor once on entry — LaunchedEffect won't re-fire on recomposition
    LaunchedEffect(Unit) {
        viewModel.openFingerprintSensor { opened ->
            if (opened) viewModel.scanAndIdentifyFingerprint()
        }
    }

    // Only runs cleanup when screen is truly removed from composition
    DisposableEffect(Unit) {
        onDispose {
            viewModel.closeFingerprintSensor()
            viewModel.resetFingerprintVerify()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "fp_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "fpPulse"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fingerprint Verification") },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1a1a2e),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1a1a2e), Color(0xFF16213e), Color(0xFF0f3460))
                    )
                )
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (val state = verifyState) {

                    // Idle: show start button
                    is FingerprintVerifyState.Idle -> {
                        FingerprintScannerPlaceholder(
                            pulseScale = pulseScale,
                            message = "Place your finger on the scanner",
                            subMessage = "Tap Start Scan when ready",
                            ringColor = Color(0xFF00FFB9)
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { viewModel.scanAndIdentifyFingerprint() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("Start Scan", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    }

                    // Scanning: animated indicator
                    is FingerprintVerifyState.Scanning -> {
                        FingerprintScannerPlaceholder(
                            pulseScale = pulseScale,
                            message = "Hold your finger still…",
                            subMessage = "Scanning in progress",
                            ringColor = Color(0xFF00FFB9)
                        )
                        Spacer(Modifier.height(24.dp))
                        CircularProgressIndicator(color = Color(0xFF00FFB9), strokeWidth = 3.dp)
                    }

                    // Match found
                    is FingerprintVerifyState.Matched -> {
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(2000)
                            viewModel.resetFingerprintVerify()
                            onBack()
                        }
                        MatchFoundCard(
                            profile = state.profile,
                            onScanAgain = {
                                viewModel.resetFingerprintVerify()
                                viewModel.scanAndIdentifyFingerprint()
                            },
                            onBack = onBack
                        )
                    }

                    // No match
                    is FingerprintVerifyState.NoMatch -> {
                        NoMatchCard(
                            onRetry = {
                                viewModel.resetFingerprintVerify()
                                viewModel.scanAndIdentifyFingerprint()
                            },
                            onBack = onBack
                        )
                    }

                    // Error
                    is FingerprintVerifyState.Error -> {
                        ErrorCard(
                            message = state.message,
                            onRetry = {
                                viewModel.resetFingerprintVerify()
                                viewModel.openFingerprintSensor { opened ->
                                    if (opened) viewModel.scanAndIdentifyFingerprint()
                                }
                            },
                            onBack = onBack
                        )
                    }
                }
            }
        }
    }
}

// Fingerprint scanner visual
@Composable
private fun FingerprintScannerPlaceholder(
    pulseScale: Float,
    message: String,
    subMessage: String,
    ringColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ringColor.copy(alpha = 0.25f),
                            ringColor.copy(alpha = 0.08f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(ringColor.copy(alpha = 0.5f), ringColor.copy(alpha = 0.2f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("👆", fontSize = 32.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            message,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subMessage,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

// Match from card
@Composable
private fun MatchFoundCard(
    profile: ProfileEntity,
    onScanAgain: () -> Unit,
    onBack: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Success icon
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color(0xFF00A86B).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CheckCircle, null,
                tint = Color(0xFF00FFB9), modifier = Modifier.size(80.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("Access Granted", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00FFB9))
        Spacer(Modifier.height(8.dp))
        Text("Fingerprint matched", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))

        Spacer(Modifier.height(24.dp))

        // Profile card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D3561))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Face thumbnail
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3D4571)),
                    contentAlignment = Alignment.Center
                ) {
                    val thumbnailBitmap = profile.thumbnail?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                    if (thumbnailBitmap != null) {
                        Image(
                            bitmap = thumbnailBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("👤", fontSize = 36.sp)
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(profile.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text("LAG ID: ${profile.lagId}", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// No match card
@Composable
private fun NoMatchCard(onRetry: () -> Unit, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color(0xFFEF5350).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) { Text("✋", fontSize = 64.sp) }

        Spacer(Modifier.height(16.dp))
        Text("Access Denied", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFEF5350))
        Spacer(Modifier.height(8.dp))
        Text("No matching fingerprint found in database",
            fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)

        Spacer(Modifier.height(32.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Back") }

            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Try Again") }
        }
    }
}

// Error Card

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color(0xFFFFA000).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) { Text("⚠️", fontSize = 64.sp) }

        Spacer(Modifier.height(16.dp))
        Text("Scanner Error", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFA000))
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) { Text("Back") }
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Retry") }
        }
    }
}


