package com.example.access_control_solution

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.suprema.BioMiniFactory
import com.suprema.CaptureResponder
import com.suprema.IBioMiniDevice
import com.suprema.IUsbEventHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume


sealed class CaptureResult {
    data class Success(val template: ByteArray, val image: Bitmap?) : CaptureResult()
    data class Failure(val message: String) : CaptureResult()
    object Timeout : CaptureResult()
}

sealed class MatchResult {
    object Matched : MatchResult()
    object NoMatch : MatchResult()
    data class Error(val message: String) : MatchResult()
}

sealed class FingerprintCaptureState {
    object Idle : FingerprintCaptureState()
    object WaitingForFinger : FingerprintCaptureState()
    data class Success(val template: ByteArray, val image: Bitmap?) : FingerprintCaptureState()
    data class Failure(val message: String) : FingerprintCaptureState()
}

sealed class FingerprintVerifyState {
    object Idle : FingerprintVerifyState()
    object Scanning : FingerprintVerifyState()
    data class Matched(val profile: com.example.access_control_solution.data.ProfileEntity) : FingerprintVerifyState()
    object NoMatch : FingerprintVerifyState()
    data class Error(val message: String) : FingerprintVerifyState()
}

class TelpoFingerprintManager(private val context: Context) {

    companion object {
        private const val TAG = "TelpoFingerprintManager"
        private const val ACTION_USB_PERMISSION =
            "com.example.access_control_solution.USB_PERMISSION"
        private const val SUPREMA_VENDOR_ID = 0x16d1
        private const val CAPTURE_TIMEOUT_MS = 15_000L
        private const val PERMISSION_TIMEOUT_MS = 10_000L
    }

    // Private state

    private var mUsbManager: UsbManager? = null
    private var mUsbDevice: UsbDevice? = null
    private var mBioMiniFactory: BioMiniFactory? = null
    private var mCurrentDevice: IBioMiniDevice? = null
    private var isOpen = false
    private var receiverRegistered = false

    // Continuation for awaiting USB permission grant inside open()
    private var permissionContinuation: ((Boolean) -> Unit)? = null

    // USB broadcast receiver

    private val mUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    if (granted && mUsbDevice != null) {
                        Log.d(TAG, "USB permission granted — creating BioMini device")
                        createBioMiniDevice()
                    } else {
                        Log.w(TAG, "USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    mUsbDevice = getSupremaUsbDevice()
                    requestUsbPermission()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB device detached")
                    removeDevice()
                }
            }
        }
    }

    // Lifecycle

    suspend fun open(): Boolean = withContext(Dispatchers.Main) {
        try {
            if (isOpen && mCurrentDevice != null){
                Log.d(TAG, "open(): already open")
                return@withContext true
            }

            // Power on the physical fingerprint scanner hardware.
            fingerPrintPower(1)

            mUsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            mUsbDevice = getSupremaUsbDevice()

            if (!receiverRegistered) {
                // USB_PERMISSION is a private app action — must not be exported
                ContextCompat.registerReceiver(
                    context, mUsbReceiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )

                ContextCompat.registerReceiver(
                    context, mUsbReceiver,
                    IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                ContextCompat.registerReceiver(
                    context, mUsbReceiver,
                    IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            }

            if (mUsbDevice == null) {
                Log.e(TAG, "No Suprema USB device found")
                return@withContext false
            }

            // If permission already granted skip the broadcast round-trip
            if (mUsbManager!!.hasPermission(mUsbDevice)) {
                Log.d(TAG, "open(): USB permission already granted")
                createBioMiniDevice()
            } else {
                Log.d(TAG, "open(): requesting USB permission, suspending...")
                val granted = withTimeout(PERMISSION_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        permissionContinuation = { result -> cont.resume(result) }
                        cont.invokeOnCancellation { permissionContinuation = null }
                        requestUsbPermission()
                    }
                }
                if (!granted) {
                    Log.w(TAG, "open(): USB permission denied")
                    return@withContext false
                }
            }

            isOpen = mCurrentDevice != null
            Log.d(TAG, "open(): result=$isOpen device=$mCurrentDevice")
            isOpen

        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "open(): timed out waiting for USB permission")
            false
        } catch (e: Exception) {
            Log.e(TAG, "open(): error", e)
            false
        }
    }

    fun close() {
        try {
            mCurrentDevice?.let {
                try { if (it.isCapturing) it.abortCapturing() } catch (_: Exception) { }
            }
            removeDevice()
            if (receiverRegistered) {
                try { context.unregisterReceiver(mUsbReceiver) } catch (_: Exception) { }
                receiverRegistered = false
            }
            fingerPrintPower(0)
        } catch (e: Exception) {
            Log.w(TAG, "Error closing fingerprint scanner", e)
        } finally {
            isOpen = false
        }
    }

    //  USB helpers

    private fun getSupremaUsbDevice(): UsbDevice? {
        val deviceList = try {
            mUsbManager?.deviceList ?: hashMapOf()
        } catch (e: Exception) { hashMapOf() }

        Log.d(TAG, "All USB devices: ${deviceList.values.map {
            "${it.deviceName} vendor=0x${it.vendorId.toString(16)}"
        }}")

        for ((_, device) in deviceList) {
            if (device.vendorId == SUPREMA_VENDOR_ID) {
                Log.d(TAG, "Found Suprema device: ${device.deviceName}")
                return device
            }
        }
        Log.w(TAG, "No Suprema USB device found (vendor 0x${SUPREMA_VENDOR_ID.toString(16)})" +
                "Available: ${deviceList.values.map { "0x${it.vendorId.toString(16)}" }}")
        return null
    }

    private fun requestUsbPermission() {
        val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
        )
        mUsbDevice?.let { mUsbManager?.requestPermission(it, permissionIntent) }
    }

    private fun createBioMiniDevice() {
        try {
            mBioMiniFactory?.close()

            mBioMiniFactory = object : BioMiniFactory(context, mUsbManager) {
                override fun onDeviceChange(
                    event: IUsbEventHandler.DeviceChangeEvent,
                    dev: Any
                ) {
                    Log.d(TAG, "onDeviceChange: $event")
                    if (event == IUsbEventHandler.DeviceChangeEvent.DEVICE_DETACHED) {
                        mCurrentDevice = null
                        isOpen = false
                    }
                }
            }

            val added = mBioMiniFactory!!.addDevice(mUsbDevice)
            if (added) {
                mCurrentDevice = mBioMiniFactory!!.getDevice(0)
                if (mCurrentDevice != null) {
                    setDefaultParameters()
                    isOpen = true
                    Log.d(TAG, "BioMini ready: ${mCurrentDevice!!.deviceInfo.deviceName}")
                } else {
                    Log.e(TAG, "getDevice(0) returned null")
                }
            } else {
                Log.e(TAG, "addDevice() returned false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createBioMiniDevice failed", e)
        }
    }

    private fun removeDevice() {
        try {
            mUsbDevice?.let { mBioMiniFactory?.removeDevice(it) }
            mBioMiniFactory?.close()
        } catch (e: Exception) {
            Log.w(TAG, "removeDevice error", e)
        } finally {
            mCurrentDevice = null
            mUsbDevice = null
            isOpen = false
        }
    }

    private fun setDefaultParameters() {
        val dev = mCurrentDevice ?: return
        listOf(
            IBioMiniDevice.ParameterType.SECURITY_LEVEL,
            IBioMiniDevice.ParameterType.SENSITIVITY,
            IBioMiniDevice.ParameterType.TIMEOUT,
            IBioMiniDevice.ParameterType.DETECT_FAKE_SW,
            IBioMiniDevice.ParameterType.FAST_MODE,
            IBioMiniDevice.ParameterType.SCANNING_MODE,
            IBioMiniDevice.ParameterType.EXT_TRIGGER,
            IBioMiniDevice.ParameterType.ENABLE_AUTOSLEEP
        ).forEach { type ->
            try {
                dev.setParameter(IBioMiniDevice.Parameter(type, dev.getParameter(type).value))
            } catch (e: Exception) {
                Log.w(TAG, "setParameter($type) failed: ${e.message}")
            }
        }
    }


    // Capture
    suspend fun capture(): CaptureResult {
        val dev = mCurrentDevice
            ?: return CaptureResult.Failure("Scanner not connected. Ensure USB is plugged in.")

        return try {
            withTimeout(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->

                    val option = IBioMiniDevice.CaptureOption().apply {
                        captureFuntion = IBioMiniDevice.CaptureFuntion.CAPTURE_SINGLE
                        extractParam.captureTemplate = true
                        captureImage = true
                    }

                    val responder = object : CaptureResponder() {
                        override fun onCaptureEx(
                            p0: Any?,
                            captureOption: IBioMiniDevice.CaptureOption?,
                            bmp: Bitmap?,
                            capturedTemplate: IBioMiniDevice.TemplateData?,
                            fingerState: IBioMiniDevice.FingerState?
                        ): Boolean {
                            if (!cont.isActive) return false

                            // TemplateData.data is the correct field (not .templateData)
                            val template = capturedTemplate?.data
                            if (template == null || template.isEmpty()) {
                                cont.resume(
                                    CaptureResult.Failure("No template data in capture result")
                                )
                                return false
                            }

                            Log.d(TAG, "Fingerprint captured — ${template.size} bytes")
                            cont.resume(CaptureResult.Success(template, bmp))
                            return true
                        }

                        override fun onCaptureError(
                            p0: Any?,
                            errorCode: Int,
                            error: String?
                        ) {
                            if (!cont.isActive) return
                            Log.w(TAG, "Capture error $errorCode: $error")
                            val msg = when (errorCode) {
                                IBioMiniDevice.ErrorCode.CTRL_ERR_IS_CAPTURING.value() ->
                                    "Scanner is busy. Please wait."
                                IBioMiniDevice.ErrorCode.CTRL_ERR_CAPTURE_ABORTED.value() ->
                                    "Capture was cancelled."
                                IBioMiniDevice.ErrorCode.CTRL_ERR_FAKE_FINGER.value() ->
                                    "Fake finger detected. Please use a real finger."
                                else -> error ?: "Capture failed (code $errorCode)"
                            }
                            cont.resume(CaptureResult.Failure(msg))
                        }
                    }

                    cont.invokeOnCancellation {
                        try { dev.abortCapturing() } catch (_: Exception) { }
                    }

                    dev.captureSingle(option, responder, true)
                }
            }
        } catch (e: TimeoutCancellationException) {
            try { dev.abortCapturing() } catch (_: Exception) { }
            CaptureResult.Timeout
        } catch (e: Exception) {
            Log.e(TAG, "Capture exception", e)
            CaptureResult.Failure(e.message ?: "Unknown capture error")
        }
    }

    fun getCapturedImageBmp(): ByteArray? {
        return try {
            mCurrentDevice?.captureImageAsBmp
        } catch (e: Exception) {
            Log.e(TAG, "getCapturedImageBmp failed", e)
            null
        }
    }

    // 1:1 Match
    suspend fun match(template1: ByteArray, template2: ByteArray): MatchResult =
        withContext(Dispatchers.IO) {
            val dev = mCurrentDevice
                ?: return@withContext MatchResult.Error("Scanner not connected")
            try {
                val isMatch = dev.verify(template1, template2)
                if (isMatch) MatchResult.Matched else MatchResult.NoMatch
            } catch (e: Exception) {
                Log.e(TAG, "Match error", e)
                MatchResult.Error(e.message ?: "Unknown match error")
            }
        }

    // 1:N Identify
    suspend fun identify(
        probe: ByteArray,
        gallery: List<ByteArray>
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val dev = mCurrentDevice ?: return@withContext null
        gallery.forEachIndexed { index, stored ->
            try {
                if (dev.verify(probe, stored)) {
                    Log.d(TAG, "identify: matched at index $index")
                    return@withContext Pair(index, 1)
                }
            } catch (e: Exception) {
                Log.w(TAG, "identify: error at index $index — ${e.message}")
            }
        }
        Log.d(TAG, "identify: no match found in ${gallery.size} templates")
        null
    }

    private fun fingerPrintPower(on: Int) {
        try {
            val clazz = Class.forName("com.telpo.tps550.api.fingerprint.FingerPrint")
            val method = clazz.getMethod("fingerPrintPower", Int::class.java)
            method.invoke(null, on)
            Log.d(TAG, "FingerPrint.fingerPrintPower($on) ok")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "FingerPrint class not found (not a Telpo device?): ${e.message}")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "FingerPrint native lib inaccessible (system namespace): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "FingerPrint.fingerPrintPower($on) failed: ${e.message}")
        }
    }

}

