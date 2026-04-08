package com.example.access_control_solution

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.access_control_solution.data.ProfileEntity
import com.example.access_control_solution.reader.ContactSmartCardReader
import com.example.access_control_solution.reader.OptimizedCardDataReader
import com.example.access_control_solution.reader.SAMCardReader
import com.example.access_control_solution.ui.AddProfileScreen
import com.example.access_control_solution.ui.CardAccessResultScreen
import com.example.access_control_solution.ui.FaceCaptureScreen
import com.example.access_control_solution.ui.FingerprintVerificationScreen
import com.example.access_control_solution.ui.MainMenuScreen
import com.example.access_control_solution.ui.ProfileListScreen
import com.example.access_control_solution.ui.SplashScreen
import com.example.access_control_solution.ui.VerificationResultScreen
import com.example.access_control_solution.ui.theme.Access_Control_SolutionTheme
import com.example.access_control_solution.viewModel.CardReaderViewModel
import com.example.isoreader.IsoReader
import com.telpo.tps550.api.reader.SmartCardReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SAM_PASSWORD = "2EC93602960F9B09D858BB00B2C8E486"
        private const val PREF_FILE = "CardAppPrefs"
        private const val PREF_SAM_PASSWORD = "SAM_PASSWORD"
    }

    // SAM Password - Persisted in SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences

    private fun getSAMPassword(): String = sharedPreferences.getString(
        PREF_SAM_PASSWORD, SAM_PASSWORD
    ) ?: SAM_PASSWORD

    fun saveSAMPassword(password: String) {
        sharedPreferences.edit().putString(PREF_SAM_PASSWORD, password).apply()
        Log.d(TAG, "SAM Password saved: ${password.take(8)}...")
    }

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFiltersArray: Array<IntentFilter>? = null
    private var techListsArray: Array<Array<String>>? = null

    private var lastNfcProcessedTime = 0L
    private val NFC_DEBOUNCE_MS = 2000L
    private var samIsoReader: IsoReader? = null

    private val viewModel: CardReaderViewModel by viewModels()

    // Navigation States
    private enum class Screen {
        SPLASH,
        MAIN_MENU,
        FACE_CAPTURE,
        ADD_PROFILE,
        PROFILE_LIST,
        VERIFICATION_RESULT,
        ACCESS_RESULT,
        FINGERPRINT_VERIFY
    }

    private var toneGenerator: ToneGenerator? = null
    private var currentScreen by mutableStateOf(Screen.SPLASH)

    // Verification result data
    private var verificationMatchedProfile by mutableStateOf<ProfileEntity?>(null)
    private var verificationScore by mutableStateOf(0)

    // Card access result data
    private var cardAccessProfile by mutableStateOf<ProfileEntity?>(null)
    private var cardAccessGranted by mutableStateOf(false)
    private var cardAccessMessage by mutableStateOf("")

    // Flag to track which screen is active for NFC handling
    private var isAddProfileScreenActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestUsbPermissionIfNeeded()

        initSamSlot()

        sharedPreferences = getSharedPreferences(PREF_FILE, MODE_PRIVATE)

        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 1000)

        Log.d(TAG, "Checking licenses...")
        Log.d(TAG, "Face Licenses activated: ${MatchApplication.areFaceLicensesActivated}")

        setupNFC()

        enableEdgeToEdge()
        setContent {
            Access_Control_SolutionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when(currentScreen) {
                        Screen.SPLASH -> {
                            SplashScreen (
                                onNavigateToMain = { currentScreen = Screen.MAIN_MENU }
                            )
                        }

                        Screen.MAIN_MENU -> {
                            MainMenuScreen(
                                viewModel = viewModel,
                                onNavigateToFaceCapture = {
                                    viewModel.reset()
                                    currentScreen = Screen.FACE_CAPTURE
                                },
                                onNavigateToAddProfile = {
                                    isAddProfileScreenActive = true
                                    currentScreen = Screen.ADD_PROFILE
                                },
                                onNavigateToProfileList = { currentScreen = Screen.PROFILE_LIST },
                                onNavigateToFingerprintVerify = {
                                    currentScreen = Screen.FINGERPRINT_VERIFY
                                },
                                onChangeSAMPassword = { saveSAMPassword(it) },

                            )

                        }

                        Screen.FINGERPRINT_VERIFY ->
                            FingerprintVerificationScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = Screen.MAIN_MENU}
                            )

                        Screen.FACE_CAPTURE -> {
                            FaceCaptureScreen(
                                viewModel = viewModel,
                                onBack = {
                                    viewModel.reset()
                                    currentScreen = Screen.MAIN_MENU
                                },
                                onPlayFaceDetectedSound = {
                                    val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 1000)
                                    toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)
                                },
                                onVerificationComplete = { profile, score ->
                                    viewModel.reset()
                                    verificationMatchedProfile = profile
                                    verificationScore = score
                                    currentScreen = Screen.VERIFICATION_RESULT

                                }
                            )
                        }

                        Screen.ADD_PROFILE -> {
                            AddProfileScreen(
                                viewModel = viewModel,
                                onBack = {
                                    isAddProfileScreenActive = false
                                    currentScreen = Screen.MAIN_MENU
                                         },
                                onProfileAdded = {
                                    isAddProfileScreenActive = false
                                    // Invalidate cache before navigating
                                    viewModel.invalidateCache()
                                    currentScreen = Screen.PROFILE_LIST
                                }
                            )
                        }

                        Screen.PROFILE_LIST -> {
                            ProfileListScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = Screen.MAIN_MENU },
                                onAddProfile = {
                                    isAddProfileScreenActive = true
                                    currentScreen = Screen.ADD_PROFILE
                                }
                            )
                        }

                        Screen.VERIFICATION_RESULT -> {
                            VerificationResultScreen(
                                viewModel = viewModel,
                                matchedProfile = verificationMatchedProfile,
                                matchScore = verificationScore,
                                onBack = {
                                    verificationMatchedProfile = null
                                    verificationScore = 0
                                    currentScreen = Screen.MAIN_MENU
                                }
                            )
                        }

                        Screen.ACCESS_RESULT -> {
                            CardAccessResultScreen(
                                profile = cardAccessProfile,
                                accessGranted = cardAccessGranted,
                                message = cardAccessMessage,
                                onBack = {
                                    cardAccessProfile = null
                                    cardAccessGranted = false
                                    cardAccessMessage = ""
                                    currentScreen = Screen.MAIN_MENU
                                }
                            )
                        }
                    }
                }
            }
        }
        // Handle NFC Intent
        handleIntent(intent)
    }

    private fun setupNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // PendingIntent object so the Android system can populate it with the details of the tag when it is scanned
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        // Setup intent filters for NFC
        val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        val tech = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val tag = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        intentFiltersArray = arrayOf(ndef, tech, tag)
        techListsArray = arrayOf(arrayOf(IsoDep::class.java.name))
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techListsArray)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            Log.d("MainActivity", "USB device attached via onNewIntent")
            return
        }
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_TAG_DISCOVERED == action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == action
        ) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let { processNFCTag(it) }
        }
    }

    private fun requestUsbPermissionIfNeeded() {
        val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
        usbManager.deviceList.values
            .filter { it.vendorId == 0x16D1 }
            .forEach { device ->
                if (!usbManager.hasPermission(device)) {
                    val permissionIntent = PendingIntent.getBroadcast(
                        this, 0,
                        Intent("com.example.access_control_solution.USB_PERMISSION"),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    usbManager.requestPermission(device, permissionIntent)
                    Log.d(TAG, "Requesting USB permission for Suprema scanner")
                } else {
                    Log.d(TAG, "Suprema scanner permission already granted")
                }
            }
    }

    private fun initSamSlot() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== SAM SLOT DIAGNOSTICS ===")

                for (slotIndex in 0..2) {
                    try {
                        Log.d(TAG, "Trying SAM slot $slotIndex...")
                        val smartCardReader = SmartCardReader(this@MainActivity, slotIndex)
                        val slot = ContactSmartCardReader(smartCardReader)

                        val opened = slot.open()
                        Log.d(TAG, "Slot $slotIndex open(): $opened")

                        if (opened) {
                            // Probe SAM with SELECT — any response means hardware is present
                            try {
                                val aidBytes = byteArrayOf(
                                    0xA0.toByte(), 0x00, 0x00, 0x00, 0x77,
                                    0xAB.toByte(), 0xCA.toByte()
                                )
                                val selectCmd = byteArrayOf(
                                    0x00, 0xA4.toByte(), 0x04, 0x00,
                                    aidBytes.size.toByte()
                                ) + aidBytes

                                val response = slot.transceive(selectCmd)
                                if (response != null && response.size >= 2) {
                                    val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or
                                            (response[response.size - 1].toInt() and 0xFF)
                                    Log.d(TAG, "Slot $slotIndex probe SW: ${"%04X".format(sw)}")
                                    Log.d(TAG, "Slot $slotIndex response: ${response.joinToString("") { byte -> "%02X".format(byte) }}")

                                    when (sw) {
                                        0x9000 -> Log.d(TAG, "✓ SAM AID selected successfully on slot $slotIndex")
                                        0x6A82 -> Log.w(TAG, "Slot $slotIndex: SAM present but AID not found — wrong AID")
                                        else   -> Log.w(TAG, "Slot $slotIndex: unexpected SW ${"%04X".format(sw)}")
                                    }

                                    // Any response means SAM hardware is responding
                                    samIsoReader = slot
                                    Log.d(TAG, "✓ SAM found in slot $slotIndex")
                                    break
                                } else {
                                    Log.w(TAG, "Slot $slotIndex: null/empty response — no SAM seated")
                                    slot.close()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Slot $slotIndex probe failed: ${e.message}")
                                slot.close()
                            }
                        } else {
                            Log.w(TAG, "Slot $slotIndex failed to open")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Slot $slotIndex exception: ${e.message}")
                    }
                }

                if (samIsoReader == null) {
                    Log.e(TAG, "✗ No SAM found in any slot — check SAM is seated correctly")
                }

            } catch (e: Exception) {
                Log.e(TAG, "SAM init failed entirely: ${e.message}", e)
                samIsoReader = null
            }
        }
    }

    private fun processNFCTag(tag: Tag) {
        val now = System.currentTimeMillis()
        if (now - lastNfcProcessedTime < NFC_DEBOUNCE_MS) {
            Log.d(TAG, "Duplicate NFC intent ignored")
            return
        }
        lastNfcProcessedTime = now
        Log.d(TAG, "Card tapped!")
        playSuccessSound()

        // Read LAG ID from the card
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            // Launch coroutine to read card data
            lifecycleScope.launch {
                try {
                    val cardReader = OptimizedCardDataReader(this@MainActivity)

                    val needBiometrics = currentScreen == Screen.ADD_PROFILE

                    val cardData = withContext(Dispatchers.IO) {
                        cardReader.readCardDataAsync(
                            isoDep = isoDep,
                            fullRead = needBiometrics,
                            samPassword = getSAMPassword(),
                            samIsoReader = samIsoReader
                        )
                    }

                    // Check which screen is active
                    if (isAddProfileScreenActive) {
                        // Send card data to AddProfileScreen via ViewModel
                        handleCardDataForAddProfile(cardData)
                    } else {
                        // Handle card data for access control
                        handleCardDataForAccess(cardData)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading card", e)
                    if (isAddProfileScreenActive) {
                        viewModel.setCardReadError("Error reading card: ${e.message}")
                    } else {
                        cardAccessProfile = null
                        cardAccessGranted = false
                        cardAccessMessage = "Error reading card: ${e.message}"
                        currentScreen = Screen.ACCESS_RESULT
                    }
                }
            }
        } else {
            Log.e(TAG, "Card is not ISO-DEP compatible")
            if (isAddProfileScreenActive) {
                viewModel.setCardReadError("Card not supported")
            } else {
                cardAccessProfile = null
                cardAccessGranted = false
                cardAccessMessage = "Card not supported"
                currentScreen = Screen.ACCESS_RESULT
            }
        }
    }

    private fun handleCardDataForAddProfile(cardData: OptimizedCardDataReader.CardData) {
        val fullName = cardData.holderName ?: ""
        val lagId = cardData.cardId ?: ""

        if (fullName.isNotEmpty() && lagId.isNotEmpty()) {
            viewModel.setCardDataForProfile(fullName, lagId)
        } else {
            viewModel.setCardReadError("Could not read complete card data")
        }

        if (cardData.faceImage != null) {
            viewModel.setBiometricDataFromCard(
                faceImage = cardData.faceImage
            )
        }

        // Convert card WSQ fingerprints to Neurotec template and store in viewModel
        if (cardData.fingerprintData.isNotEmpty()) {
            viewModel.extractAndStoreFingerprintFromCard(cardData.fingerprintData) { template ->
                if (template != null) {
                    // Store as a single-finger FingerprintData carrying NTemplates bytes
                    viewModel.setFingerprintsFromCard(
                        listOf(
                            SAMCardReader.FingerprintData(
                                template = template,
                                fingerIndex = 0,
                                format = "NEUROTEC"
                            )
                        )
                    )
                    Log.d(TAG, "Card fingerprint template ready for enrollment")
                }else {
                    Log.w(TAG, "Card fingerprint conversion failed, profile will save without fingerprint")
                }

            }
        }
    }

    private fun handleCardDataForAccess(cardData: OptimizedCardDataReader.CardData) {
        val lagId = cardData.cardId ?: cardData.holderName

        if (lagId != null) {
            Log.d(TAG, "LAG ID read: $lagId")

            // Push biometric to viewmodel when a full read is performed
            if (cardData.faceImage != null || cardData.fingerprintData.isNotEmpty()) {
                viewModel.setBiometricDataFromCard(
                    faceImage = cardData.faceImage
                )
            }

            // Check if LAG ID exists in database
            viewModel.checkLagIdInDatabase(lagId) { profile, exists ->
                if (exists && profile != null) {
                    // Access granted
                    cardAccessProfile = profile
                    cardAccessGranted = true
                    cardAccessMessage = "Welcome, ${profile.name}"
                    currentScreen = Screen.ACCESS_RESULT
                } else {
                    // Access denied
                    cardAccessProfile = null
                    cardAccessGranted = false
                    cardAccessMessage = "LAG ID not registered: $lagId"
                    currentScreen = Screen.ACCESS_RESULT
                }
            }
        } else {
            Log.e(TAG, "Failed to read LAG ID from card")
            cardAccessProfile = null
            cardAccessGranted = false
            cardAccessMessage = "Could not read card data"
            currentScreen = Screen.ACCESS_RESULT
        }
    }

    private fun playSuccessSound() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the tone generator when activity is destroyed
        try {
            samIsoReader?.close()
        } catch (e: Exception) { /* */}
        toneGenerator?.release()
        toneGenerator = null
    }

}
