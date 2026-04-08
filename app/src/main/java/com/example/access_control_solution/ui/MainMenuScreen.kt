package com.example.access_control_solution.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access_control_solution.viewModel.CardReaderViewModel


@Composable
fun MainMenuScreen(
    viewModel: CardReaderViewModel,
    onNavigateToFaceCapture: () -> Unit,
    onNavigateToAddProfile: () -> Unit,
    onNavigateToProfileList: () -> Unit,
    onNavigateToFingerprintVerify: () -> Unit,
    onChangeSAMPassword: (String) -> Unit = {}
) {

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )

    // SAM password dialog state
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1a1a2e), Color(0xFF16213e), Color(0xFF0f3460))
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "ACCESS VALIDATOR",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Secure Entry System",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // SAM password settings button
//                    Card(
//                        onClick = { showPasswordDialog = true },
//                        shape = CircleShape,
//                        colors = CardDefaults.cardColors(containerColor = Color(0xFF607D8B)),
//                        modifier = Modifier.size(48.dp)
//                    ) {
//                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
//                            Text("⚙️", fontSize = 22.sp)
//                        }
//                    }

                    // Profile list button
                    Card(
                        onClick = onNavigateToProfileList,
                        shape = CircleShape,
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF00D9FF)),
                        elevation = CardDefaults.cardElevation(8.dp),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("👤", fontSize = 30.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))

            // Main verification card
            Card(
                modifier = Modifier.fillMaxWidth().scale(pulseScale),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF2D3561), Color(0xFF1F2544))
                            )
                        )
                        .padding(32.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // NFC Section
                        Text("Tap your card on the device", fontSize = 22.sp,
                            fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)

                        Spacer(Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(Color(0xFF00FFB9), Color(0xFF00D9A8), Color(0xFF00A86B))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("))))", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Spacer(Modifier.height(4.dp))
                                Text("NFC", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.95f), letterSpacing = 3.sp)
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // OR divider
                        OrDivider()

                        Spacer(Modifier.height(24.dp))

                        // Verification method label
                        Text("Or verify with biometrics", fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)

                        Spacer(Modifier.height(20.dp))

                        // Face + Fingerprint buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Face button
                            BiometricButton(
                                emoji = null,
                                icon = { modifier ->
                                    Icon(Icons.Default.Face, null, modifier = modifier, tint = Color.White)
                                },
                                label = "FACE",
                                gradientColors = listOf(Color(0xFF9D84FF), Color(0xFF7B68EE), Color(0xFF5A4FCF)),
                                ringColor = Color(0xFF9D84FF),
                                onClick = onNavigateToFaceCapture
                            )

                            // Fingerprint button
                            BiometricButton(
                                emoji = "👆",
                                icon = null,
                                label = "FINGER",
                                gradientColors = listOf(Color(0xFFFF8A65), Color(0xFFFF7043), Color(0xFFE64A19)),
                                ringColor = Color(0xFFFF8A65),
                                onClick = onNavigateToFingerprintVerify
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    // ── SAM password dialog ───────────────────────────────────────────────────
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false; passwordInput = "" },
            title = { Text("Change SAM Password", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter the new SAM password for your card reader:",
                        fontSize = 14.sp, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("SAM Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (passwordInput.isNotBlank()) {
                        onChangeSAMPassword(passwordInput)
                        showPasswordDialog = false
                        passwordInput = ""
                    }
                }) { Text("Save", color = Color(0xFF00A86B)) }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false; passwordInput = "" }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}


// Reusable biometric icon button
@Composable
private fun BiometricButton(
    emoji: String?,
    icon: (@Composable (Modifier) -> Unit)?,
    label: String,
    gradientColors: List<Color>,
    ringColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(70.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(colors = gradientColors))
            .clickable(
                indication = rememberRipple(bounded = true),
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        // Glow rings
        Box(Modifier.size(160.dp).clip(CircleShape).background(ringColor.copy(alpha = 0.1f)))
        Box(Modifier.size(180.dp).clip(CircleShape).background(ringColor.copy(alpha = 0.05f)))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (emoji != null) {
                Text(emoji, fontSize = 56.sp)
            } else if (icon != null) {
                icon(Modifier.size(64.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.95f), letterSpacing = 2.sp)
        }
    }
}

// OR divider
@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(0.8f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f).height(2.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF00D9FF).copy(alpha = 0.5f))))
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp).size(44.dp)
                .clip(CircleShape).background(Color(0xFF00D9FF).copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text("OR", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF00D9FF), letterSpacing = 2.sp)
        }
        Box(
            modifier = Modifier.weight(1f).height(2.dp)
                .background(Brush.horizontalGradient(listOf(Color(0xFF00D9FF).copy(alpha = 0.5f), Color.Transparent)))
        )
    }
}



