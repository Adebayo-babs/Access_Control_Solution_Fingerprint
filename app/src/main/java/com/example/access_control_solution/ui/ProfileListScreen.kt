package com.example.access_control_solution.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access_control_solution.data.ProfileEntity
import com.example.access_control_solution.viewModel.CardReaderViewModel
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    viewModel: CardReaderViewModel,
    onBack: () -> Unit,
    onAddProfile: () -> Unit
) {
    var profileList by remember { mutableStateOf<List<ProfileEntity>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<ProfileEntity?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }
    var showRefreshSnackbar by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val snackBarHostState = remember { SnackbarHostState() }

    // Load profiles when screen opens
    LaunchedEffect(Unit) {
        Log.d("ProfileListScreen", "Loading profiles... Server available: ${viewModel.isServerAvailable}")

        if (viewModel.isServerAvailable) {
            // If server is available, sync first
            viewModel.refreshFromServer { count, error ->
                if (error == null) {
                    Log.d("ProfileListScreen", "Synced $count profiles from server")
                    // Now load from local database
                    viewModel.getAllProfile(
                        callback = { profiles ->
                            Log.d("ProfileListScreen", "Loaded ${profiles.size} profiles from local DB")
                            profileList = profiles
                            isLoading = false
                            loadError = null
                        },
                        forceRefresh = false
                    )
                } else {
                    Log.e("ProfileListScreen", "Sync error: $error")
                    loadError = error
                    // Still try to load from local database
                    viewModel.getAllProfile(
                        callback = { profiles ->
                            Log.d("ProfileListScreen", "Loaded ${profiles.size} profiles from local DB (after sync error)")
                            profileList = profiles
                            isLoading = false
                        },
                        forceRefresh = false
                    )
                }
            }
        } else {
            // Server not available, load from local only
            Log.w("ProfileListScreen", "Server not available, loading from local DB only")
            viewModel.getAllProfile(
                callback = { profiles ->
                    Log.d("ProfileListScreen", "Loaded ${profiles.size} profiles from local DB")
                    profileList = profiles
                    isLoading = false
                    if (profiles.isEmpty()) {
                        loadError = "No profiles found. Server is unavailable."
                    }
                },
                forceRefresh = false
            )
        }
    }

    // Show snackbar for refresh messages
    LaunchedEffect(showRefreshSnackbar, refreshMessage) {
        if (showRefreshSnackbar && refreshMessage != null) {
            snackBarHostState.showSnackbar(refreshMessage!!)
            showRefreshSnackbar = false
            refreshMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackBarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Profiles (${profileList.size})")
                        if (loadError != null) {
                            Text(
                                text = "⚠ ${loadError}",
                                fontSize = 12.sp,
                                color = Color.Yellow
                            )
                        } else if (viewModel.isServerAvailable) {
                            Text(
                                text = "✓ Server connected",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        } else {
                            Text(
                                text = "⚠ Offline mode",
                                fontSize = 12.sp,
                                color = Color.Yellow
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Manual refresh button
                    IconButton(
                        onClick = {
                            isRefreshing = true
                            loadError = null

                            if (viewModel.isServerAvailable) {
                                viewModel.refreshFromServer { count, error ->
                                    isRefreshing = false
                                    if (error == null) {
                                        refreshMessage = "✓ Synced $count profiles from server"
                                        showRefreshSnackbar = true
                                        // Reload the list
                                        viewModel.getAllProfile(
                                            callback = { profiles ->
                                                profileList = profiles
                                            },
                                            forceRefresh = false
                                        )
                                    } else {
                                        loadError = error
                                        refreshMessage = "✗ Sync failed: $error"
                                        showRefreshSnackbar = true
                                        // Still show local profiles
                                        viewModel.getAllProfile(
                                            callback = { profiles ->
                                                profileList = profiles
                                            },
                                            forceRefresh = false
                                        )
                                    }
                                }
                            } else {
                                // Just reload local profiles
                                viewModel.getAllProfile(
                                    callback = { profiles ->
                                        profileList = profiles
                                        isRefreshing = false
                                        refreshMessage = "✓ Loaded ${profiles.size} local profiles"
                                        showRefreshSnackbar = true
                                    },
                                    forceRefresh = true
                                )
                            }
                        },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                "Refresh",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF00A86B),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddProfile,
                containerColor = Color(0xFF00A86B)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Profile",
                    tint = Color.White
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00A86B)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (viewModel.isServerAvailable)
                                "Loading profiles from server..."
                            else
                                "Loading profiles...",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                }
                profileList.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "👥",
                            fontSize = 64.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Profiles Yet",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (loadError != null)
                                loadError!!
                            else
                                "Add your first profile to get started",
                            fontSize = 16.sp,
                            color = if (loadError != null) Color.Red else Color.Gray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onAddProfile,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00A86B)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, "Add", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Profile")
                        }
                    }
                }
                else -> {
                    SwipeRefresh(
                        state = rememberSwipeRefreshState(isRefreshing),
                        onRefresh = {
                            isRefreshing = true
                            if (viewModel.isServerAvailable) {
                                viewModel.refreshFromServer { count, error ->
                                    isRefreshing = false
                                    if (error == null) {
                                        refreshMessage = "✓ Synced $count profiles"
                                        showRefreshSnackbar = true
                                        viewModel.getAllProfile(
                                            callback = { profiles ->
                                                profileList = profiles
                                            },
                                            forceRefresh = false
                                        )
                                    } else {
                                        refreshMessage = "✗ Failed: $error"
                                        showRefreshSnackbar = true
                                    }
                                }
                            } else {
                                viewModel.getAllProfile(
                                    callback = { profiles ->
                                        profileList = profiles
                                        isRefreshing = false
                                    },
                                    forceRefresh = true
                                )
                            }
                        }
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(profileList) { profile ->
                                ProfileCard(
                                    profile = profile,
                                    onDelete = {
                                        profileToDelete = profile
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog (same as before)
    if (showDeleteDialog && profileToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Profile") },
            text = {
                Column {
                    Text("Are you sure you want to delete ${profileToDelete?.name}'s profile?")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This will delete the profile from all devices.",
                        fontSize = 14.sp,
                        color = Color.Red.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profileToDelete?.let { profile ->
                            isLoading = true
                            viewModel.deleteProfile(profile.id) {
                                // Refresh list after delete
                                viewModel.getAllProfile(
                                    callback = { list ->
                                        profileList = list
                                        isLoading = false
                                        refreshMessage = "✓ Profile deleted"
                                        showRefreshSnackbar = true
                                    },
                                    forceRefresh = true
                                )
                            }
                        }
                        showDeleteDialog = false
                        profileToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.Red
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileCard(
    profile: ProfileEntity,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Profile Image
            var bitmap by remember { mutableStateOf<Bitmap?>(null) }

            LaunchedEffect(profile.id) {
                withContext(Dispatchers.IO) {
                    try {
                        if (profile.thumbnail != null) {
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 1
                            }
                            bitmap = BitmapFactory.decodeByteArray(
                                profile.thumbnail,
                                0,
                                profile.thumbnail.size,
                                options
                            )
                        }
                    } catch (e: Exception) {
                        bitmap = null
                    }
                }
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Profile photo",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.LightGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", fontSize = 32.sp)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Profile Details
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = profile.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "LAG ID: ${profile.lagId}",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Added: ${formatTimestamp(profile.timestamp)}",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
            }

            // Delete Button
            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Red
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete"
                )
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
    return format.format(date)
}
