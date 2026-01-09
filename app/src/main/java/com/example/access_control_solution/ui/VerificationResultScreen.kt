package com.example.access_control_solution.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access_control_solution.data.ProfileEntity
import com.example.access_control_solution.viewModel.CardReaderViewModel


@Composable
fun VerificationResultScreen(
    viewModel: CardReaderViewModel,
    matchedProfile: ProfileEntity?,
    matchScore: Int,
    onBack: () -> Unit
) {

    val isMatch = matchScore >= 70
    val backgroundColor = if (isMatch) Color(0xFF00A86B) else Color(0xFFFF5722)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Result Icon
        Icon(
            imageVector = if (isMatch) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = if (isMatch) "Match" else "No Match",
            tint = Color.White,
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Result Text
        Text(
            text = if (isMatch) "Access Granted" else "Access Denied",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isMatch && matchedProfile != null) {
            // Show matched staff details
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Profile Image
                    val bitmap = remember(matchedProfile.faceImage) {
                        BitmapFactory.decodeByteArray(
                            matchedProfile.faceImage,
                            0,
                            matchedProfile.faceImage.size
                        )
                    }

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Profile photo",
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = matchedProfile.name,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "LAG ID: ${matchedProfile.lagId}",
                        fontSize = 16.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Match Score
//                    Card(
//                        colors = CardDefaults.cardColors(
//                            containerColor = Color(0xFF00A86B).copy(alpha = 0.1f)
//                        ),
//                        shape = RoundedCornerShape(8.dp)
//                    ) {
//                        Text(
//                            text = "Match Score: $matchScore%",
//                            fontSize = 18.sp,
//                            fontWeight = FontWeight.SemiBold,
//                            color = Color(0xFF00A86B),
//                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
//                        )
//                    }
                }
            }
        } else {
            // No match found
            Text(
                text = "No matching profile found",
                fontSize = 18.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (matchScore > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Best match score: $matchScore%",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Back Button
        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Back to Home",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = backgroundColor
            )
        }
    }

}