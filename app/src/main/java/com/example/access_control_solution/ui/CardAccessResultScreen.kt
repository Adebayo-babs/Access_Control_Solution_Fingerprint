package com.example.access_control_solution.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access_control_solution.data.ProfileEntity


@Composable
fun CardAccessResultScreen(
    profile: ProfileEntity?,
    accessGranted: Boolean,
    message: String,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (accessGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status Icon
            Icon(
                imageVector = if (accessGranted) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = if (accessGranted) "Access Granted" else "Access Denied",
                modifier = Modifier.size(120.dp),
                tint = if (accessGranted) Color(0xFF4CAF50) else Color(0xFFE53935)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status Text
            Text(
                text = if (accessGranted) "ACCESS GRANTED" else "ACCESS DENIED",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (accessGranted) Color(0xFF2E7D32) else Color(0xFFC62828),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Profile info if access granted
            if (accessGranted && profile != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Profile Image
                        if (profile.faceImage.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(
                                profile.faceImage,
                                0,
                                profile.faceImage.size
                            )
                            bitmap?.let {
                                Card(
                                    modifier = Modifier.size(120.dp),
                                    shape = CircleShape,
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                ) {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Profile Photo",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Text(
                            text = profile.name,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "LAG ID: ${profile.lagId}",
                            fontSize = 16.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Message for denied access
                Text(
                    text = message,
                    fontSize = 18.sp,
                    color = Color(0xFF666666),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Back Button
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (accessGranted) Color(0xFF4CAF50) else Color(0xFFE53935)
                )
            ) {
                Text(
                    text = "Back to Main Menu",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}