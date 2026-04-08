package com.example.neurotecsdklibrary

import android.content.Context
import android.util.Log
import com.neurotec.licensing.NLicense
import java.io.File

object NeurotecLicenseHelper {

    private const val TAG = "NeurotecLicense"

    // Face licenses
    const val LICENSE_FACE_DETECTION = "Biometrics.FaceDetection"
    const val LICENSE_FACE_EXTRACTION = "Biometrics.FaceExtraction"
    const val LICENSE_FACE_MATCHING = "Biometrics.FaceMatching"

    // Finger licenses
    const val LICENSE_FINGER_EXTRACTION = "Biometrics.FingerExtraction"
    const val LICENSE_FINGER_MATCHING = "Biometrics.FingerMatching"

    private val faceLicenseComponents = listOf(
        LICENSE_FACE_DETECTION,
        LICENSE_FACE_EXTRACTION,
        LICENSE_FACE_MATCHING
    )

    private val fingerLicenseComponents = listOf(
        LICENSE_FINGER_EXTRACTION,
        LICENSE_FINGER_MATCHING
    )

    private val allComponents = faceLicenseComponents + fingerLicenseComponents

    // Obtain face licenses
    fun obtainFaceLicenses(context: Context): Boolean {
        return try {
            Log.d(TAG, "Starting License Activation")

//            NLicenseManager.setTrialMode(true)

            var faceExtraction = NLicense.isComponentActivated(LICENSE_FACE_EXTRACTION)
            var faceMatching = NLicense.isComponentActivated(LICENSE_FACE_MATCHING)

            if (!faceExtraction || !faceMatching) {

                val licenseDir = File(context.filesDir, "Licenses")

                licenseDir.listFiles()?.forEach { file ->
                    try {
                        val content = file.readText()
                        NLicense.add(content)
                        Log.d(TAG, " Loaded license file: ${file.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, " Failed to load ${file.name}", e)
                    }
                }

                faceLicenseComponents.forEach { component ->
                    try {
                        val obtained = NLicense.obtainComponents("/local", "5000", component)
                        Log.d(TAG, "$component activation = $obtained")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error activating $component", e)
                    }
                }
            }

            NLicense.isComponentActivated(LICENSE_FACE_EXTRACTION) &&
                    NLicense.isComponentActivated(LICENSE_FACE_MATCHING)

        } catch (e: Exception) {
            Log.e(TAG, " License activation failed", e)
            e.printStackTrace()
            false
        }
    }

    // Obtain finger licenses
    fun obtainFingerLicenses(context: Context): Boolean {
        return try {
            Log.d(TAG, "Starting License Activation")

//            NLicenseManager.setTrialMode(true)

            var fingerExtraction = NLicense.isComponentActivated(LICENSE_FINGER_EXTRACTION)
            var fingerMatching = NLicense.isComponentActivated(LICENSE_FINGER_MATCHING)

            if (!fingerExtraction || !fingerMatching) {

                val licenseDir = File(context.filesDir, "Licenses")

                licenseDir.listFiles()?.forEach { file ->
                    try {
                        val content = file.readText()
                        NLicense.add(content)
                        Log.d(TAG, " Loaded license file: ${file.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, " Failed to load ${file.name}", e)
                    }
                }

                fingerLicenseComponents.forEach { component ->
                    try {
                        val obtained = NLicense.obtainComponents("/local", "5000", component)
                        Log.d(TAG, "$component activation = $obtained")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error activating $component", e)
                    }
                }
            }

            NLicense.isComponentActivated(LICENSE_FINGER_EXTRACTION) &&
                    NLicense.isComponentActivated(LICENSE_FINGER_MATCHING)

        } catch (e: Exception) {
            Log.e(TAG, " License activation failed", e)
            e.printStackTrace()
            false
        }
    }

    // Obtain all licenses at once
    fun obtainAllLicenses(context: Context): Boolean {
        val face = obtainFaceLicenses(context)
        val finger = obtainFingerLicenses(context)
        Log.d(TAG, "All licenses - face=$face, finger=$finger")
        return face && finger
    }



    fun release() {
        try {
            val allComponents = (faceLicenseComponents).joinToString (",")
            NLicense.releaseComponents(allComponents)
            Log.d(TAG, "All licenses released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release licenses", e)
        }
    }

}