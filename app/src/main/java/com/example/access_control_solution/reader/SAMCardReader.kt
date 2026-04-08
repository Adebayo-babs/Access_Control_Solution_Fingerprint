package com.example.access_control_solution.reader


import android.nfc.tech.IsoDep
import android.util.Log
import com.common.apiutil.nfc.Nfc
import com.example.isoreader.IsoReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.IOException

class SAMCardReader {

    private val telpoNfcLock = Mutex()

    private enum class SAMPasswordStatus { OK, LOW_RETRIES, LOCKED, UNKNOWN }

    companion object {
        private const val TAG = "SAMCardReader"

        // Card application AID
        private const val CARD_AID = "A000000077AB01"

        // SAM chip AID — different from card AID
        private const val SAM_AID = "A000000077ABCA"

        // Response codes
        private const val SW_SUCCESS = 0x9000
        private const val SW_FILE_NOT_FOUND  = 0x6A82
        private const val SW_RECORD_NOT_FOUND = 0x6A83

        // Biometric format markers
        private val CBEFF_TAG  = byteArrayOf(0x7F, 0x61)
        private val BDB_TAG    = byteArrayOf(0x7F, 0x2E)
        private val DF_FP_TAGS = listOf(0x09, 0x0A, 0x0B, 0x0C, 0x78)
    }

    data class FingerprintData(
        val template: ByteArray?,
        val fingerIndex: Int = 0,
        val format: String = "UNKNOWN"
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FingerprintData
            if (template != null) {
                if (other.template == null) return false
                if (!template.contentEquals(other.template)) return false
            } else if (other.template != null) return false
            return fingerIndex == other.fingerIndex && format == other.format
        }
        override fun hashCode(): Int {
            var result = template?.contentHashCode() ?: 0
            result = 31 * result + fingerIndex
            result = 31 * result + format.hashCode()
            return result
        }
    }

    data class SecureCardData(
        val cardId: String?,
        val surname: String?,
        val firstName: String?,
        val faceImage: ByteArray?,
        val fingerprintData: List<FingerprintData> = emptyList(),
        val additionalInfo: Map<String, String> = emptyMap(),
        val isAuthenticated: Boolean = false
    )

    data class EnumeratedData(
        val sfi: Int,
        val record: Int,
        val data: ByteArray
    )

    suspend fun readSecureCardData(
        isoDep: IsoDep? = null,
        nfcDevice: Nfc? = null,
        samReader: IsoReader? = null,   // TPS360 SAM slot reader
        samPassword: String,
        samKeyIndex: Int = 0x01,
        fastMode: Boolean = false
    ): SecureCardData = withContext(Dispatchers.IO) {
        when {
            isoDep != null -> readFromIsoDep(
                isoDep, samReader, samPassword, samKeyIndex, fastMode
            )
            nfcDevice != null -> readFromTelpoNfc(
                nfcDevice, samReader, samPassword, samKeyIndex, fastMode
            )
            else -> SecureCardData(
                cardId = null, surname = null, firstName = null, faceImage = null,
                fingerprintData = emptyList(),
                additionalInfo = mapOf("error" to "No NFC device provided"),
                isAuthenticated = false
            )
        }
    }

    // IsoDep path (Android NFC)

    private suspend fun readFromIsoDep(
        isoDep: IsoDep,
        samIsoReader: IsoReader?,
        samPassword: String,
        samKeyIndex: Int,
        fastMode: Boolean
    ): SecureCardData {
        var cardId: String? = null
        var holderName: String? = null
        var firstName: String? = null
        var faceImage: ByteArray? = null
        val fingerprintData = mutableListOf<FingerprintData>()
        var isAuthenticated = false
        val additionalInfo = mutableMapOf<String, String>()
        val enumeratedData = mutableListOf<EnumeratedData>()

        try {
            if (!isoDep.isConnected) isoDep.connect()
            isoDep.timeout = 10000

            val cardTransceive: (ByteArray) -> ByteArray = { cmd -> isoDep.transceive(cmd) }

            // SAM transceive — physical slot preferred, fallback logs a warning
            val samTransceive: (ByteArray) -> ByteArray = if (samIsoReader != null) {
                { cmd ->
                    samIsoReader.transceive(cmd)
                    ?: throw IOException("SAM slot returned null response.")}
            } else {
                Log.w(TAG, "No SAM reader provided — protected SFIs will not open")
                cardTransceive
            }

            isAuthenticated = performFullAuthentication(
                cardTransceive, samTransceive,
                samPassword, samKeyIndex,
                enumeratedData, fastMode
            )

            if (enumeratedData.isNotEmpty()) {
                val parsed = parseEnumeratedData(enumeratedData)
                cardId     = parsed["cardId"] ?: parsed["documentNumber"]
                holderName = parsed["name"]
                firstName  = parsed["firstName"]
                additionalInfo.putAll(parsed)

                if (!fastMode && isAuthenticated) {
                    faceImage = extractFaceImage(enumeratedData)
                    fingerprintData.addAll(
                        extractFingerprints(
                            enumeratedData,
                            faceImageExtractedFromSfi3 = faceImage != null &&
                                    enumeratedData.none { it.sfi == 2 }
                        )
                    )
                    Log.d(TAG, "Fingerprints extracted: ${fingerprintData.size}")
                }

                if (!isAuthenticated) {
                    additionalInfo["warning"] = "SAM authentication unavailable — biometrics not read"
                    Log.w(TAG, "Partial read: SAM auth failed but card data retrieved")
                }
            } else {
                additionalInfo["error"] = "No data could be read from card"
            }

        } catch (e: Exception) {
            additionalInfo["error"] = e.message ?: "Unknown error"
            Log.e(TAG, "readFromIsoDep error", e)
        } finally {
            try { if (isoDep.isConnected) isoDep.close() } catch (e: Exception) { /* ignore */ }
        }

        return SecureCardData(
            cardId, holderName, firstName, faceImage,
            fingerprintData, additionalInfo, isAuthenticated
        )
    }

    // Telpo NFC path

    private suspend fun readFromTelpoNfc(
        nfcDevice: Nfc,
        samIsoReader: IsoReader?,
        samPassword: String,
        samKeyIndex: Int,
        fastMode: Boolean
    ): SecureCardData = withContext(Dispatchers.IO) {
        telpoNfcLock.withLock {
            var cardId: String? = null
            var holderName: String? = null
            var firstName: String? = null
            var faceImage: ByteArray? = null
            val fingerprintData = mutableListOf<FingerprintData>()
            var isAuthenticated = false
            val additionalInfo = mutableMapOf<String, String>()
            val enumeratedData = mutableListOf<EnumeratedData>()

            try {
                val cardTransceive: (ByteArray) -> ByteArray = { cmd ->
                    nfcDevice.transmit(cmd, cmd.size)
                }

                val samTransceive: (ByteArray) -> ByteArray = if (samIsoReader != null) {
                    { cmd -> samIsoReader.transceive(cmd)
                        ?: throw IOException("SAM slot returned null response, check SAM is seated correctly")}
                } else {
                    Log.w(TAG, "No SAM reader provided — protected SFIs will not open")
                    cardTransceive
                }

                isAuthenticated = performFullAuthentication(
                    cardTransceive, samTransceive,
                    samPassword, samKeyIndex,
                    enumeratedData, fastMode
                )

                if (enumeratedData.isNotEmpty()) {
                    val parsed = parseEnumeratedData(enumeratedData)
                    cardId     = parsed["cardId"] ?: parsed["documentNumber"]
                    holderName = parsed["name"]
                    firstName  = parsed["firstName"]
                    additionalInfo.putAll(parsed)

                    if (!fastMode && isAuthenticated) {
                        faceImage = extractFaceImage(enumeratedData)
                        fingerprintData.addAll(
                            extractFingerprints(
                                enumeratedData,
                                faceImageExtractedFromSfi3 = faceImage != null &&
                                        enumeratedData.none { it.sfi == 2 }
                            )
                        )
                        Log.d(TAG, "Fingerprints extracted: ${fingerprintData.size}")
                    }

                    if (!isAuthenticated) {
                        additionalInfo["warning"] = "SAM authentication unavailable — biometrics not read"
                        Log.w(TAG, "Partial read: SAM auth failed but card data retrieved")
                    }
                } else {
                    additionalInfo["error"] = "No data could be read from card"
                }

            } catch (e: Exception) {
                additionalInfo["error"] = e.message ?: "Unknown error"
                Log.e(TAG, "readFromTelpoNfc error", e)
            }

            SecureCardData(
                cardId, holderName, firstName, faceImage,
                fingerprintData, additionalInfo, isAuthenticated
            )
        }
    }

    // Authentication — shared by both paths

    private fun performFullAuthentication(
        cardTransceive: (ByteArray) -> ByteArray,
        samTransceive: (ByteArray) -> ByteArray,
        samPassword: String,
        keyIndex: Int,
        enumeratedData: MutableList<EnumeratedData>,
        fastMode: Boolean
    ): Boolean {
        try {
            // ── Step 1: Select SAM application
            val samAidBytes = hexStringToByteArray(SAM_AID)
            val selectSamCmd = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00,
                samAidBytes.size.toByte()
            ) + samAidBytes
            val selectSamResp = samTransceive(selectSamCmd)
            Log.d(TAG, "SAM SELECT response: ${selectSamResp.toHexString()}")
            if (getStatusWord(selectSamResp) != SW_SUCCESS) {
                Log.e(TAG, "SAM AID select failed: ${"%04X".format(getStatusWord(selectSamResp))}")
                return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
            }
            Log.d(TAG, " SAM application selected")

            // ── Step 2: Check retry counter before attempting password
            val samPasswordStatus = checkSAMPasswordStatus(samTransceive)
            when (samPasswordStatus) {
                SAMPasswordStatus.LOCKED -> {
                    Log.e(TAG, " SAM is locked (retry counter = 0). Contact SAM issuer to unblock.")
                    return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
                }
                SAMPasswordStatus.LOW_RETRIES -> {
                    Log.w(TAG, " SAM retry counter is low — aborting to prevent lockout.")
                    return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
                }
                SAMPasswordStatus.UNKNOWN -> {
                    Log.w(TAG, "Could not read SAM retry counter — proceeding with caution")
                }
                SAMPasswordStatus.OK -> {
                    Log.d(TAG, " SAM retry counter OK")
                }
            }

            // ── Step 3: Verify SAM password
            val pwdBytes = hexStringToByteArray(samPassword)
            val pwdCmd = byteArrayOf(
                0x80.toByte(), 0x18, 0x00, 0x00,
                pwdBytes.size.toByte()
            ) + pwdBytes
            val pwdResp = samTransceive(pwdCmd)
            val pwdSw = getStatusWord(pwdResp)
            if (pwdSw != SW_SUCCESS) {
                when (pwdSw) {
                    0x6983 -> Log.e(TAG, "SAM locked — too many wrong password attempts. Contact SAM issuer.")
                    0x6982 -> Log.e(TAG, "SAM password incorre ct — do NOT retry to avoid lockout.")
                    0x63C0 -> Log.e(TAG, "SAM password incorrect — 0 retries left, SAM will lock on next attempt.")
                    0x63C1 -> Log.e(TAG, "SAM password incorrect — 1 retry remaining.")
                    0x63C2 -> Log.e(TAG, "SAM password incorrect — 2 retries remaining.")
                    else   -> Log.e(TAG, "SAM password failed: ${"%04X".format(pwdSw)}")
                }
                return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
            }
            Log.d(TAG, " SAM password verified")

            // ── Step 4: Select card application + extract diversification data
            val cardAidBytes = hexStringToByteArray(CARD_AID)
            val selectCardCmd = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00,
                cardAidBytes.size.toByte()
            ) + cardAidBytes
            val selectCardResp = cardTransceive(selectCardCmd)
            if (getStatusWord(selectCardResp) != SW_SUCCESS) {
                Log.e(TAG, "Card AID select failed: ${"%04X".format(getStatusWord(selectCardResp))}")
                return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
            }
            val selectCardData = selectCardResp.copyOfRange(0, selectCardResp.size - 2)
            val diversification = if (selectCardData.size >= 36)
                selectCardData.copyOfRange(20, 36)
            else
                ByteArray(16) { 0x00 }
            Log.d(TAG, " Card selected, diversification: ${diversification.toHexString()}")

            // ── Step 5: GET CHALLENGE from card
            val challengeCmd = byteArrayOf(0x00, 0x84.toByte(), 0x00, 0x00, 0x16)
            val challengeResp = cardTransceive(challengeCmd)
            if (getStatusWord(challengeResp) != SW_SUCCESS) {
                Log.e(TAG, "GET CHALLENGE failed: ${"%04X".format(getStatusWord(challengeResp))}")
                return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
            }
            val challenge = challengeResp.copyOfRange(0, challengeResp.size - 2)
            Log.d(TAG, " Challenge: ${challenge.toHexString()}")

            // ── Step 6: SAM generates mutual-auth token
            val keyRef = byteArrayOf(
                0x01, 0x00, (keyIndex and 0xFF).toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                keyIndex.toByte()
            )
            val samAuthData = keyRef +
                    challenge +
                    byteArrayOf(0x90.toByte(), 0x00) +
                    diversification
            val samAuthCmd = byteArrayOf(
                0x80.toByte(), 0xA8.toByte(), 0x00, 0x00,
                samAuthData.size.toByte()
            ) + samAuthData + byteArrayOf(0x00)
            val samAuthResp = samTransceive(samAuthCmd)
            if (getStatusWord(samAuthResp) != SW_SUCCESS) {
                Log.e(TAG, "SAM token gen failed: ${"%04X".format(getStatusWord(samAuthResp))}")
                return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
            }
            val samToken = samAuthResp.copyOfRange(0, samAuthResp.size - 2)
            Log.d(TAG, " SAM token: ${samToken.toHexString()}")

            // ── Step 7: MUTUAL AUTHENTICATE with card
            val mutualCmd = byteArrayOf(
                0x00, 0x82.toByte(), 0x00, keyIndex.toByte(),
                samToken.size.toByte()
            ) + samToken + byteArrayOf(0x10)
            val mutualResp = cardTransceive(mutualCmd)
            if (getStatusWord(mutualResp) != SW_SUCCESS) {
                Log.e(TAG, "Mutual auth failed: ${"%04X".format(getStatusWord(mutualResp))}")
                return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
            }
            val cardToken = mutualResp.copyOfRange(0, mutualResp.size - 2)
            Log.d(TAG, "✓ Card token: ${cardToken.toHexString()}")

            // ── Step 8: SAM verifies card token
            val verifyData = cardToken + byteArrayOf(0x90.toByte(), 0x00)
            val verifyCmd = byteArrayOf(
                0x80.toByte(), 0xA6.toByte(), 0x00, 0x00,
                verifyData.size.toByte()
            ) + verifyData
            val verifyResp = samTransceive(verifyCmd)
            if (getStatusWord(verifyResp) != SW_SUCCESS) {
                Log.e(TAG, "SAM verify failed: ${"%04X".format(getStatusWord(verifyResp))}")
                return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
            }
            Log.d(TAG, " Mutual authentication complete — card unlocked")

            // ── Step 9: Read all SFIs
            val found = enumerateAndReadFiles(cardTransceive, enumeratedData, fastMode)
            Log.d(TAG, "Records read: ${enumeratedData.size}")
            return found && enumeratedData.isNotEmpty()

        } catch (e: Exception) {
            Log.e(TAG, "performFullAuthentication error: ${e.message}", e)
            return fallbackUnauthenticatedRead(cardTransceive, enumeratedData, fastMode)
        }
    }

    private fun checkSAMPasswordStatus(samTransceive: (ByteArray) -> ByteArray): SAMPasswordStatus {
        return try {
            // GET DATA for retry counter — P1=00 P2=C0 is common for remaining tries
            val getDataCmd = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x00, 0xC0.toByte(), 0x00)
            val resp = samTransceive(getDataCmd)
            val sw = getStatusWord(resp)
            Log.d(TAG, "SAM retry counter GET DATA SW: ${"%04X".format(sw)}")

            when {
                sw == 0x9000 && resp.size > 2 -> {
                    val retries = resp[resp.size - 3].toInt() and 0xFF
                    Log.d(TAG, "SAM retries remaining: $retries")
                    when {
                        retries == 0    -> SAMPasswordStatus.LOCKED
                        retries <= 1    -> SAMPasswordStatus.LOW_RETRIES
                        else            -> SAMPasswordStatus.OK
                    }
                }
                // Some SAMs encode retries directly in SW 63 Cx
                sw and 0xFFF0 == 0x63C0 -> {
                    val retries = sw and 0x000F
                    Log.d(TAG, "SAM retries remaining (from SW): $retries")
                    when {
                        retries == 0 -> SAMPasswordStatus.LOCKED
                        retries <= 1 -> SAMPasswordStatus.LOW_RETRIES
                        else         -> SAMPasswordStatus.OK
                    }
                }
                sw == 0x6983 -> SAMPasswordStatus.LOCKED
                else -> SAMPasswordStatus.UNKNOWN
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check SAM retry counter: ${e.message}")
            SAMPasswordStatus.UNKNOWN
        }
    }

    private fun fallbackUnauthenticatedRead(
        cardTransceive: (ByteArray) -> ByteArray,
        enumeratedData: MutableList<EnumeratedData>,
        fastMode: Boolean
    ): Boolean {
        return try {
            Log.w(TAG, "Falling back to unauthenticated card read — biometrics unavailable")

            val cardAidBytes = hexStringToByteArray(CARD_AID)
            val selectCmd = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00,
                cardAidBytes.size.toByte()
            ) + cardAidBytes
            val selectResp = cardTransceive(selectCmd)
            if (getStatusWord(selectResp) != SW_SUCCESS) {
                Log.e(TAG, "Unauthenticated card select failed: ${"%04X".format(getStatusWord(selectResp))}")
                return false
            }
            Log.d(TAG, " Card selected (unauthenticated)")

            val found = enumerateAndReadFiles(cardTransceive, enumeratedData, fastMode)
            Log.d(TAG, "Unauthenticated records returned: ${enumeratedData.size}")
            found
        } catch (e: Exception) {
            Log.e(TAG, "Unauthenticated read error: ${e.message}", e)
            false
        }
    }

    private fun enumerateAndReadFiles(
        transceive: (ByteArray) -> ByteArray,
        enumeratedData: MutableList<EnumeratedData>,
        fastMode: Boolean = false
    ): Boolean {
        var foundAny = false
        val targetSFIs = if (fastMode) listOf(1) else listOf(1, 2, 3, 4, 5, 6, 7, 8)

        for (sfi in targetSFIs) {
            var consecutiveFailures = 0
            val maxRecords = if (fastMode && sfi == 1) 5 else 20

            for (record in 1..maxRecords) {
                try {
                    val command = byteArrayOf(
                        0x00, 0xB2.toByte(),
                        record.toByte(),
                        ((sfi shl 3) or 0x04).toByte(),
                        0x00
                    )
                    val response = transceive(command)
                    val sw = getStatusWord(response)

                    when {
                        sw == SW_SUCCESS -> {
                            val data = response.copyOfRange(0, response.size - 2)
                            Log.d(TAG, "SFI $sfi Rec $record [${data.size}B]: " +
                                    data.take(16).joinToString("") { "%02X".format(it) })
                            enumeratedData.add(EnumeratedData(sfi, record, data))
                            foundAny = true
                            consecutiveFailures = 0
                        }
                        sw == SW_RECORD_NOT_FOUND || sw == SW_FILE_NOT_FOUND -> {
                            consecutiveFailures++
                            if (consecutiveFailures >= 3) break
                        }
                        else -> {
                            Log.w(TAG, "SFI $sfi Rec $record SW: ${"%04X".format(sw)}")
                            consecutiveFailures++
                            if (consecutiveFailures >= 3) break
                        }
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) break
                }
            }
        }
        return foundAny
    }

    private fun parseEnumeratedData(enumeratedData: List<EnumeratedData>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        enumeratedData.groupBy { it.sfi }.forEach { (sfi, records) ->
            if (sfi == 1) {
                records.firstOrNull { it.record == 1 }?.let {
                    result.putAll(parseCardHolderData(it.data))
                }
            }
        }
        return result
    }

    private fun parseCardHolderData(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < data.size - 2) {
            if (data[i] == 0xDF.toByte() && i + 1 < data.size) {
                val tag = data[i + 1].toInt() and 0xFF
                i += 2
                if (i >= data.size) break
                val length = data[i].toInt() and 0xFF
                i++
                if (i + length > data.size) break
                val value = data.copyOfRange(i, i + length)
                i += length
                val strVal = String(value, Charsets.UTF_8).trim().replace("\u0000", "")
                val fieldName = when (tag) {
                    0x01 -> "firstName";  0x02 -> "surname";    0x03 -> "middleName"
                    0x04 -> "nationality";0x05 -> "dob";         0x06 -> "gender"
                    0x07 -> "height";     0x08 -> "address";     0x09 -> "documentNumber"
                    0x0A -> "cardId";     0x0B -> "issueDate";   0x0C -> "expiryDate"
                    0x0D -> "documentType"
                    else -> "unknownTag_DF%02X".format(tag)
                }
                result[fieldName] = strVal
            } else i++
        }
        val fullName = listOfNotNull(result["surname"], result["firstName"], result["middleName"])
            .joinToString(" ").trim()
        if (fullName.isNotEmpty()) result["name"] = fullName
        return result
    }

    private fun extractFingerprints(
        enumeratedData: List<EnumeratedData>,
        faceImageExtractedFromSfi3: Boolean = false
    ): List<FingerprintData> {
        val fingerprints = mutableListOf<FingerprintData>()
        val sfisToCheck = if (faceImageExtractedFromSfi3) listOf(4, 5, 6)
        else listOf(3, 4, 5, 6)

        sfisToCheck.forEach { sfi ->
            val records = enumeratedData.filter { it.sfi == sfi }.sortedBy { it.record }
            if (records.isEmpty()) return@forEach

            val sfiData = records.flatMap { it.data.toList() }.toByteArray()
            Log.d(TAG, "SFI $sfi: ${sfiData.size} bytes — " +
                    sfiData.take(16).joinToString("") { "%02X".format(it) })

            val extracted = extractFingerprintTemplate(sfiData, sfi)
            if (extracted != null) {
                val format = detectFingerprintFormat(extracted)
                fingerprints.add(
                    FingerprintData(
                        template    = extracted,
                        fingerIndex = if (sfi >= 4) sfi - 3 else sfi,
                        format      = format
                    )
                )
                Log.d(TAG, "✓ SFI $sfi: ${extracted.size}B format=$format")
            } else {
                Log.w(TAG, "✗ SFI $sfi: no template found")
            }
        }
        return fingerprints
    }

    private fun extractFingerprintTemplate(data: ByteArray, sfi: Int = 0): ByteArray? {
        if (data.size < 20) return null
        extractCBEFF(data)?.let          { return it }
        extractISOFingerprint(data)?.let { return it }
        extractWSQFingerprint(data)?.let { return it }
        extractDFTaggedPayload(data)?.let { return it }
        extractGIF(data)?.let            { return it }
        if (data.size in 100..50_000 && !looksLikeImageOrTLV(data)) return data.copyOf()
        return null
    }

    private fun extractFaceImage(enumeratedData: List<EnumeratedData>): ByteArray? {
        // Try SFI 2 first
        val sfi2 = enumeratedData.filter { it.sfi == 2 }.sortedBy { it.record }
            .flatMap { it.data.toList() }.toByteArray()
        if (sfi2.isNotEmpty()) extractJPEG(sfi2)?.let { return it }

        // Fall back to SFI 3
        val sfi3 = enumeratedData.filter { it.sfi == 3 }.sortedBy { it.record }
            .flatMap { it.data.toList() }.toByteArray()
        if (sfi3.isNotEmpty()) {
            extractJPEG(sfi3)?.let { return it }
            extractGIF(sfi3)?.let  { return it }
        }
        return null
    }

    // Format extractors

    private fun extractCBEFF(data: ByteArray): ByteArray? {
        val outerStart = data.indexOfSequence(CBEFF_TAG)
        if (outerStart == -1) return null
        val (outerLen, outerLenBytes) = parseTLVLength(data, outerStart + 2) ?: return null
        val containerStart = outerStart + 2 + outerLenBytes
        val bdbOffset = data.indexOfSequence(BDB_TAG, containerStart,
            minOf(containerStart + outerLen, data.size))
        if (bdbOffset == -1) return null
        val (bdbLen, bdbLenBytes) = parseTLVLength(data, bdbOffset + 2) ?: return null
        val bdbStart = bdbOffset + 2 + bdbLenBytes
        val bdbEnd   = minOf(bdbStart + bdbLen, data.size)
        return if (bdbEnd > bdbStart) data.copyOfRange(bdbStart, bdbEnd) else null
    }

    private fun extractISOFingerprint(data: ByteArray): ByteArray? {
        val header = byteArrayOf(0x46, 0x4D, 0x52, 0x00)
        val start = data.indexOfSequence(header)
        if (start != -1) return data.copyOfRange(start, minOf(start + 2000, data.size))
        if (data.size >= 3 && data[0] == 0x46.toByte() &&
            data[1] == 0x4D.toByte() && data[2] == 0x52.toByte()) return data.copyOf()
        return null
    }

    private fun extractWSQFingerprint(data: ByteArray): ByteArray? {
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xA0.toByte())
            return data.copyOf()
        return null
    }

    private fun extractDFTaggedPayload(data: ByteArray): ByteArray? {
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0xDF.toByte()) {
                val subTag = data[i + 1].toInt() and 0xFF
                if (subTag in DF_FP_TAGS) {
                    val length = data[i + 2].toInt() and 0xFF
                    val start = i + 3
                    val end   = start + length
                    if (end <= data.size && length >= 100) return data.copyOfRange(start, end)
                }
            }
            i++
        }
        return null
    }

    private fun extractJPEG(data: ByteArray): ByteArray? {
        val start = data.indexOfSequence(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val end   = data.indexOfSequence(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return if (start != -1 && end != -1 && end > start) data.copyOfRange(start, end + 2) else null
    }

    private fun extractGIF(data: ByteArray): ByteArray? {
        val start = data.indexOfSequence("GIF89a".toByteArray())
        val end   = data.indexOfSequence(byteArrayOf(0x00, 0x3B))
        return if (start != -1 && end != -1 && end > start) data.copyOfRange(start, end + 2) else null
    }

    private fun detectFingerprintFormat(template: ByteArray): String {
        if (template.size < 4) return "UNKNOWN"
        return when {
            template[0] == 0x46.toByte() && template[1] == 0x4D.toByte()
                    && template[2] == 0x52.toByte() -> "ISO_19794_2"
            template[0] == 0xFF.toByte() && template[1] == 0xA0.toByte() -> "WSQ"
            template[0] == 0x47.toByte() && template[1] == 0x49.toByte()
                    && template[2] == 0x46.toByte() -> "GIF"
            template.size in 100..5000 &&
                    (template[0] == 0x00.toByte() || template[0] == 0x01.toByte()) -> "ANSI_378"
            else -> "RAW"
        }
    }

    private fun looksLikeImageOrTLV(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        return (b0 == 0xFF && b1 == 0xD8) || (b0 == 0xFF && b1 == 0xA0) ||
                (b0 == 0x89 && b1 == 0x50) || (b0 == 0x47 && b1 == 0x49) ||
                (b0 == 0x7F && b1 == 0x61) || (b0 == 0x30)
    }

    private fun parseTLVLength(data: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= data.size) return null
        val first = data[offset].toInt() and 0xFF
        return when {
            first < 0x80  -> Pair(first, 1)
            first == 0x81 -> if (offset + 1 < data.size)
                Pair(data[offset + 1].toInt() and 0xFF, 2) else null
            first == 0x82 -> if (offset + 2 < data.size)
                Pair(((data[offset + 1].toInt() and 0xFF) shl 8) or
                        (data[offset + 2].toInt() and 0xFF), 3) else null
            else -> null
        }
    }

    private fun getStatusWord(response: ByteArray): Int =
        if (response.size < 2) 0
        else ((response[response.size - 2].toInt() and 0xFF) shl 8) or
                (response[response.size - 1].toInt() and 0xFF)

    private fun hexStringToByteArray(s: String): ByteArray {
        val cleaned = s.replace(" ", "").replace(":", "")
        val data = ByteArray(cleaned.length / 2)
        for (i in cleaned.indices step 2)
            data[i / 2] = ((Character.digit(cleaned[i], 16) shl 4) +
                    Character.digit(cleaned[i + 1], 16)).toByte()
        return data
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02X".format(it) }

    private fun ByteArray.indexOfSequence(
        seq: ByteArray, from: Int = 0, to: Int = this.size
    ): Int {
        outer@ for (i in from until to) {
            if (i + seq.size > size) break
            for (j in seq.indices) if (this[i + j] != seq[j]) continue@outer
            return i
        }
        return -1
    }
}