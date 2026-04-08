package com.example.neurotecsdklibrary

import android.content.Context
import android.util.Log
import com.neurotec.biometrics.NBiometricOperation
import com.neurotec.biometrics.NBiometricStatus
import com.neurotec.biometrics.NBiometricTask
import com.neurotec.biometrics.NFinger
import com.neurotec.biometrics.NSubject
import com.neurotec.biometrics.client.NBiometricClient
import com.neurotec.images.NImage
import com.neurotec.io.NBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

object NeurotecFingerprintHelper {

    private const val TAG = "NeurotecFP"

    suspend fun extractTemplateFromImage(
        context: Context,
        imageBytes: ByteArray
    ): ByteArray? = withContext(Dispatchers.IO) {
        var client: NBiometricClient? = null
        var subject: NSubject? = null
        var finger: NFinger? = null
        var nImage: NImage? = null

        try {
            // Ensure finger license is active
            if (!NeurotecLicenseHelper.obtainFingerLicenses(context)) {
                Log.e(TAG, "Finger licenses not available")
                return@withContext null
            }

            client = NBiometricClient()
            client.initialize()

            // Load image from raw bytes
            val buffer = NBuffer.fromArray(imageBytes)
            nImage = NImage.fromMemory(buffer)

            finger = NFinger()
            finger.image = nImage

            subject = NSubject()
            subject.fingers.add(finger)

            // Run extraction
            val task: NBiometricTask = client.createTask(
                EnumSet.of(NBiometricOperation.CREATE_TEMPLATE),
                subject
            )
            client.performTask(task)

            val status = task.status
            Log.d(TAG, "Extraction status: $status")

            if (status == NBiometricStatus.OK) {
                val template = subject.templateBuffer?.toByteArray()
                Log.d(TAG, "Template extracted: ${template?.size} bytes")
                template
            } else {
                Log.e(TAG, "Extraction failed: $status — ${task.error?.message}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractTemplateFromImage failed", e)
            null
        } finally {
            try { nImage?.dispose() } catch (_: Exception) {}
            try { finger?.dispose() } catch (_: Exception) {}
            try { subject?.dispose() } catch (_: Exception) {}
            try { client?.dispose() } catch (_: Exception) {}
        }
    }

    suspend fun matchFingerprints(
        context: Context,
        probeImageBytes: ByteArray,
        storedTemplate: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        var client: NBiometricClient? = null
        var probeSubject: NSubject? = null
        var gallerySubject: NSubject? = null
        var probeFinger: NFinger? = null
        var probeImage: NImage? = null

        try {
            if (!NeurotecLicenseHelper.obtainFingerLicenses(context)) {
                Log.e(TAG, "Finger licenses not available")
                return@withContext false
            }

            client = NBiometricClient()
            client.initialize()

            // Build probe subject from live scanner image

            val buffer = NBuffer.fromArray(probeImageBytes)
            probeImage = NImage.fromMemory(buffer)
            probeFinger = NFinger()
            probeFinger.image = probeImage

            probeSubject = NSubject()
            probeSubject.fingers.add(probeFinger)

            // Extract template from live image first
            val extractTask = client.createTask(
                java.util.EnumSet.of(NBiometricOperation.CREATE_TEMPLATE),
                probeSubject
            )
            client.performTask(extractTask)

            if (extractTask.status != NBiometricStatus.OK) {
                Log.e(TAG, "Probe extraction failed: ${extractTask.status}")
                return@withContext false
            }
            // Build gallery subject from stored template
            gallerySubject = NSubject()
            gallerySubject.setTemplateBuffer(
                NBuffer.fromArray(storedTemplate)
            )
            gallerySubject.id = "stored"

            // Enroll gallery subject into client
            val enrollTask = client.createTask(
                java.util.EnumSet.of(NBiometricOperation.ENROLL),
                gallerySubject
            )
            client.performTask(enrollTask)

            // Identify probe against gallery
            val identifyTask = client.createTask(
                EnumSet.of(NBiometricOperation.IDENTIFY),
                probeSubject
            )
            client.performTask(identifyTask)

            val matched = identifyTask.status == NBiometricStatus.OK &&
                    probeSubject.matchingResults?.isNotEmpty() == true
            Log.d(TAG, "Match result: $matched (status=${identifyTask.status})")
            matched
        } catch (e: Exception) {
            Log.e(TAG, "matchFingerprints failed", e)
            false
        } finally {
            try { probeImage?.dispose() } catch (_: Exception) {}
            try { probeFinger?.dispose() } catch (_: Exception) {}
            try { probeSubject?.dispose() } catch (_: Exception) {}
            try { gallerySubject?.dispose() } catch (_: Exception) {}
            try { client?.dispose() } catch (_: Exception) {}
        }
    }

    // 1:N identify — probe image vs list of stored Neurotec templates.
    suspend fun identifyFingerprint(
        context: Context,
        probeImageBytes: ByteArray,
        storedTemplates: List<Pair<String, ByteArray>>
    ): String? = withContext(Dispatchers.IO) {
        var client: NBiometricClient? = null
        var probeSubject: NSubject? = null
        var probeFinger: NFinger? = null
        var probeImage: NImage? = null
        val gallerySubjects = mutableListOf<NSubject>()

        try {
            if (!NeurotecLicenseHelper.obtainFingerLicenses(context)) {
                Log.e(TAG, "Finger licenses not available")
                return@withContext null
            }

            client = NBiometricClient()
            client.initialize()

            // Extract probe template from live scanner image
            val buffer = NBuffer.fromArray(probeImageBytes)
            probeImage = NImage.fromMemory(buffer)
            probeFinger = NFinger()
            probeFinger.image = probeImage

            probeSubject = NSubject()
            probeSubject.fingers.add(probeFinger)

            val extractTask = client.createTask(
                EnumSet.of(NBiometricOperation.CREATE_TEMPLATE),
                probeSubject
            )
            client.performTask(extractTask)

            if (extractTask.status != NBiometricStatus.OK) {
                Log.e(TAG, "Probe extraction failed: ${extractTask.status}")
                return@withContext null
            }

            // Enroll all stored templates into client gallery
            storedTemplates.forEach { (profileId, templateBytes) ->
                try {
                    val gs = NSubject()
                    gs.id = profileId
                    gs.setTemplateBuffer(
                        com.neurotec.io.NBuffer.fromArray(templateBytes)
                    )
                    val enrollTask = client.createTask(
                        java.util.EnumSet.of(NBiometricOperation.ENROLL),
                        gs
                    )
                    client.performTask(enrollTask)
                    gallerySubjects.add(gs)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enroll template for $profileId", e)
                }
            }

            // Identify
            val identifyTask = client.createTask(
                EnumSet.of(NBiometricOperation.IDENTIFY),
                probeSubject
            )
            client.performTask(identifyTask)

            if (identifyTask.status == NBiometricStatus.OK) {
                val matchedId = probeSubject.matchingResults
                    ?.maxByOrNull { it.score }
                    ?.id
                Log.d(TAG, "Identified: $matchedId")
                matchedId
            } else {
                Log.d(TAG, "No match found: ${identifyTask.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "identifyFingerprint failed", e)
            null
        } finally {
            try { probeImage?.dispose() } catch (_: Exception) {}
            try { probeFinger?.dispose() } catch (_: Exception) {}
            try { probeSubject?.dispose() } catch (_: Exception) {}
            gallerySubjects.forEach { try { it.dispose() } catch (_: Exception) {} }
            try { client?.dispose() } catch (_: Exception) {}
        }
    }
}