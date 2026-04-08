package com.example.access_control_solution.reader

import android.annotation.SuppressLint
import android.content.Context
import android.nfc.tech.IsoDep
import android.util.Log
import com.common.apiutil.reader.SmartCardReader
import com.example.isoreader.IsoReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.CoroutineContext

class OptimizedCardDataReader(private val context: Context) : CoroutineScope {

    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    companion object {
        private const val TAG = "OptimizedCardDataReader"
        private const val READ_TIMEOUT_MS = 200L // Short timeout per operation
        private const val MAX_CONCURRENT_READS = 3 // Limit concurrent APDU calls

        // Prioritized AID list - most common first
        private val CARD_AIDS = arrayOf(
            "A000000077AB01", // Your specific AID - try this first
            "315041592E5359532E4444463031" // Generic card AID
        )

        // Common record locations that usually contain data
        private val PRIORITY_RECORDS = listOf(
            Pair(1, 1), Pair(1, 2), Pair(1, 3), // SFI 1 usually has main data
            Pair(2, 1), Pair(2, 2),             // SFI 2 for additional data
            Pair(3, 1)                          // SFI 3 for extra data
        )

        // APDU commands (optimized versions)
        private fun selectApplicationCommand(aid: String): ByteArray {
            val aidBytes = hexStringToByteArray(aid)
            return byteArrayOf(
                0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                aidBytes.size.toByte()
            ) + aidBytes
        }

        private fun readRecordCommand(recordNumber: Int, sfi: Int): ByteArray {
            return byteArrayOf(
                0x00.toByte(), 0xB2.toByte(),
                recordNumber.toByte(),
                ((sfi shl 3) or 0x04).toByte(),
                0x00.toByte()
            )
        }

        private fun getProcessingOptionsCommand(): ByteArray {
            return byteArrayOf(
                0x80.toByte(), 0xA8.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x02.toByte(), 0x83.toByte(), 0x00.toByte(), 0x00.toByte()
            )
        }

        private fun hexStringToByteArray(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) +
                        Character.digit(s[i + 1], 16)).toByte()
            }
            return data
        }

        private fun byteArrayToHex(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02X".format(it) }
        }

    }

    data class CardData(
        val cardId: String?,
        val holderName: String?,
        val expirationDate: String?,
        val applicationLabel: String?,
        val pan: String?,
        val additionalInfo: Map<String, String> = emptyMap(),
        val rawData: List<String> = emptyList(),
        val readTimeMs: Long = 0L,

        // FaceImage and FingerprintData are null when fullRead = false (Fast Mode)
        val faceImage: ByteArray? = null,
        val fingerprintData: List<SAMCardReader.FingerprintData> = emptyList()

    )

    private data class ReadResult(
        val success: Boolean,
        val data: ByteArray?,
        val recordInfo: String,
        val error: String? = null
    )

    suspend fun readCardDataAsync(
        isoDep: IsoDep,
        fullRead: Boolean = false,
        samPassword: String = "",
        samIsoReader: IsoReader? = null
    ): CardData = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var cardId: String? = null
        var holderName: String? = null
        var expirationDate: String? = null
        var applicationLabel: String? = null
        var pan: String? = null
        val additionalInfo = mutableMapOf<String, String>()
        val rawDataList = mutableListOf<String>()

        if (fullRead) {

            // Open TPS360 SAM slot
            val samReader  = SAMCardReader()

            val secureData = samReader.readSecureCardData(
                isoDep = isoDep,
                samReader = samIsoReader,
                samPassword = samPassword,
                fastMode = false // Include Biometrics
            )
            try {
                samIsoReader?.close()
            } catch (e: Exception) {
                /* ignore */
            }
            val elapsed = System.currentTimeMillis() - startTime
            return@withContext CardData(
                cardId         = secureData.cardId,
                holderName     = secureData.surname,
                expirationDate = secureData.additionalInfo["expiryDate"],
                applicationLabel = null,
                pan            = null,
                additionalInfo = secureData.additionalInfo,
                rawData        = emptyList(),
                readTimeMs     = elapsed,
                faceImage      = secureData.faceImage,
                fingerprintData = secureData.fingerprintData
            )
        }

        try {
            // Connect to card
            withTimeout(1000L) { // 1 second total timeout
                isoDep.connect()
                Log.d(TAG, "Connected to card")
            }

            // Select application (try each AID with timeout)
            var applicationSelected = false
            for (aid in CARD_AIDS) {
                try {
                    val selectCommand = selectApplicationCommand(aid)
                    val response = withTimeout(READ_TIMEOUT_MS) {
                        isoDep.transceive(selectCommand)
                    }

                    val responseHex = byteArrayToHex(response)
                    rawDataList.add("SELECT $aid: $responseHex")

                    if (response.size >= 2) {
                        val sw1 = response[response.size - 2].toInt() and 0xFF
                        val sw2 = response[response.size - 1].toInt() and 0xFF

                        if (sw1 == 0x90 && sw2 == 0x00) {
                            Log.d(TAG, "Selected application: $aid")
                            applicationSelected = true
                            applicationLabel = parseApplicationLabel(response)
                            break // Exit loop early on success
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Timeout selecting AID $aid")
                    continue // Try next AID
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to select AID $aid: ${e.message}")
                    continue
                }
            }

            if (!applicationSelected) {
                Log.w(TAG, "No application could be selected")
                return@withContext CardData(null, null, null, null, null,
                    additionalInfo, rawDataList, System.currentTimeMillis() - startTime)
            }

            // Get Processing Options
            try {
                val gpoCommand = getProcessingOptionsCommand()
                val gpoResponse = withTimeout(READ_TIMEOUT_MS) {
                    isoDep.transceive(gpoCommand)
                }
                val gpoHex = byteArrayToHex(gpoResponse)
                rawDataList.add("GPO: $gpoHex")
            } catch (e: Exception) {
                Log.w(TAG, "GPO skipped: ${e.message}")
            }

            // Read priority records concurrently with early termination
            val readJobs = mutableListOf<Deferred<ReadResult>>()
            val semaphore = Semaphore(MAX_CONCURRENT_READS)

            for ((sfi, record) in PRIORITY_RECORDS) {
                val job = async {
                    semaphore.withPermit {
                        try {
                            val readCommand = readRecordCommand(record, sfi)
                            val recordResponse = withTimeout(READ_TIMEOUT_MS) {
                                isoDep.transceive(readCommand)
                            }

                            if (recordResponse.size >= 2) {
                                val sw1 = recordResponse[recordResponse.size - 2].toInt() and 0xFF
                                val sw2 = recordResponse[recordResponse.size - 1].toInt() and 0xFF

                                if (sw1 == 0x90 && sw2 == 0x00) {
                                    val recordHex = byteArrayToHex(recordResponse)
                                    ReadResult(
                                        success = true,
                                        data = recordResponse,
                                        recordInfo = "SFI:$sfi REC:$record: $recordHex"
                                    )
                                } else {
                                    ReadResult(false, null, "SFI:$sfi REC:$record", "SW: $sw1$sw2")
                                }
                            } else {
                                ReadResult(false, null, "SFI:$sfi REC:$record", "Short response")
                            }
                        } catch (e: TimeoutCancellationException) {
                            ReadResult(false, null, "SFI:$sfi REC:$record", "Timeout")
                        } catch (e: Exception) {
                            ReadResult(false, null, "SFI:$sfi REC:$record", e.message)
                        }
                    }
                }
                readJobs.add(job)
            }

            // Process results as they complete (early termination)
            var foundMainData = false

            for (job in readJobs) {
                try {
                    val result = withTimeout(READ_TIMEOUT_MS * 2) { job.await() }

                    if (result.success && result.data != null) {
                        rawDataList.add(result.recordInfo)

                        // Parse the data immediately
                        val parsedData = parseCardRecord(
                            result.data,
                            result.recordInfo.substringAfter("SFI:").substringBefore(" ").toIntOrNull() ?: 1,
                            result.recordInfo.substringAfter("REC:").substringBefore(":").toIntOrNull() ?: 1
                        )

                        // Update fields if not already found
                        if (parsedData.cardId != null && cardId == null) {
                            cardId = parsedData.cardId
                            foundMainData = true
                        }
                        if (parsedData.holderName != null && holderName == null) {
                            holderName = parsedData.holderName
                            Log.d(TAG, "Found cardholder name: $holderName")
                        }
                        if (parsedData.expirationDate != null && expirationDate == null) {
                            expirationDate = parsedData.expirationDate
                        }
                        if (parsedData.pan != null && pan == null) {
                            pan = parsedData.pan
                        }

                        additionalInfo.putAll(parsedData.additionalInfo)

                        // Early termination if we have the essential data
                        if (foundMainData && holderName != null) {
                            Log.d(TAG, "Essential data found, stopping early")
                            // Cancel remaining jobs
                            readJobs.forEach { if (it != job) it.cancel() }
                            break
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    job.cancel()
                    Log.w(TAG, "Job timeout, continuing with next")
                } catch (e: Exception) {
                    Log.w(TAG, "Job failed: ${e.message}")
                }
            }

            // Cancel any remaining jobs
            readJobs.forEach { it.cancel() }

            val totalTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Card read completed in ${totalTime}ms")
            Log.d(TAG, "=== EXTRACTED CARD DATA ===")
            Log.d(TAG, "Card ID: $cardId")
            Log.d(TAG, "Holder Name: $holderName")
            Log.d(TAG, "Application Label: $applicationLabel")

        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Overall timeout reading card")
        } catch (e: IOException) {
            Log.e(TAG, "IO Exception reading card: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}")
        } finally {
            try {
                withTimeout(100L) { isoDep.close() }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing connection: ${e.message}")
            }
        }

        val totalReadTime = System.currentTimeMillis() - startTime
        CardData(cardId, holderName, expirationDate, applicationLabel, pan,
            additionalInfo, rawDataList, totalReadTime)
    }

    // Optimized parsing functions (same logic, but streamlined)
    private fun parseApplicationLabel(response: ByteArray): String? {
        return findTLVData(response, 0x50)?.let {
            String(it, StandardCharsets.UTF_8).trim()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun parseCardRecord(record: ByteArray, sfi: Int, recordNumber: Int): CardData {
        var cardId: String? = null
        var holderName: String? = null
        var expirationDate: String? = null
        var pan: String? = null
        val additionalInfo = mutableMapOf<String, String>()

        // Fast path for SFI 1 Record 1 (your custom format)
        if (sfi == 1 && recordNumber == 1) {
            val parsedData = parseCustomRecord(record)
            holderName = parsedData["holderName"]
            cardId = parsedData["cardId"]
            additionalInfo.putAll(parsedData)

            // Return early if we found what we need
            if (cardId != null || holderName != null) {
                return CardData(cardId, holderName, expirationDate, null, pan, additionalInfo)
            }
        }

        // Standard EMV parsing (optimized)
        findTLVData(record, 0x5A)?.let { panBytes ->
            pan = byteArrayToHex(panBytes)
            if (cardId == null) cardId = pan
        }

        findTLVData(record, 0x5F, 0x20)?.let { nameBytes ->
            val name = String(nameBytes, StandardCharsets.UTF_8).trim()
            if (name.isNotEmpty() && holderName == null) {
                holderName = name
            }
        }

        findTLVData(record, 0x5F, 0x24)?.let { expiryBytes ->
            if (expiryBytes.size >= 3) {
                val year = String.format("%02d", expiryBytes[0].toInt() and 0xFF)
                val month = String.format("%02d", expiryBytes[1].toInt() and 0xFF)
                expirationDate = "$month/$year"
            }
        }

        findTLVData(record, 0x9F, 0x10)?.let { appData ->
            if (cardId == null) {
                cardId = byteArrayToHex(appData)
            }
        }

        return CardData(cardId, holderName, expirationDate, null, pan, additionalInfo)
    }

    // Simplified custom record parsing
    @SuppressLint("DefaultLocale")
    private fun parseCustomRecord(record: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()

        try {
            var i = 0
            while (i < record.size - 2) {
                if (record[i].toInt() and 0xFF == 0xDF && i < record.size - 2) {
                    val tag = ((record[i].toInt() and 0xFF) shl 8) or (record[i + 1].toInt() and 0xFF)
                    i += 2

                    if (i >= record.size) break

                    val length = record[i].toInt() and 0xFF
                    i++

                    if (i + length <= record.size) {
                        val value = ByteArray(length)
                        System.arraycopy(record, i, value, 0, length)

                        when (tag) {
                            0xDF02 -> {
                                tryParseAsString(value)?.let { result["holderName"] = it }
                            }
                            0xDF0A -> {
                                tryParseAsString(value)?.let { result["cardId"] = it }
                            }
                            0xDF01 -> {
                                tryParseAsString(value)?.let { result["cardType"] = it }
                            }
                            // Add other important tags as needed
                        }
                    }
                    i += length
                } else {
                    i++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing custom record: ${e.message}")
        }

        return result
    }

    private fun tryParseAsString(bytes: ByteArray): String? {
        return try {
            val utf8String = String(bytes, StandardCharsets.UTF_8).trim()
            if (utf8String.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".-_" }) {
                utf8String
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun findTLVData(data: ByteArray, vararg tags: Int): ByteArray? {
        var i = 0
        while (i < data.size - 1) {
            var currentTag = data[i].toInt() and 0xFF

            if (tags.size > 1 && i < data.size - 2) {
                val nextByte = data[i + 1].toInt() and 0xFF
                if (currentTag == tags[0] && nextByte == tags[1]) {
                    i += 2
                } else {
                    i++
                    continue
                }
            } else if (currentTag == tags[0]) {
                i++
            } else {
                i++
                continue
            }

            if (i >= data.size) break

            var length = data[i].toInt() and 0xFF
            i++

            if (length and 0x80 != 0) {
                val lengthBytes = length and 0x7F
                if (lengthBytes > 0 && i + lengthBytes <= data.size) {
                    length = 0
                    for (j in 0 until lengthBytes) {
                        length = (length shl 8) or (data[i + j].toInt() and 0xFF)
                    }
                    i += lengthBytes
                }
            }

            if (i + length <= data.size) {
                val value = ByteArray(length)
                System.arraycopy(data, i, value, 0, length)
                return value
            }

            i += length
        }
        return null
    }
}