package com.example.access_control_solution.reader

import com.example.isoreader.IsoReader
import com.telpo.tps550.api.reader.SmartCardReader

class ContactSmartCardReader(private val reader: SmartCardReader) : IsoReader {
    override fun transceive(arr: ByteArray): ByteArray? {
        return reader.transmit(arr)
    }

    override fun open(): Boolean {
        val opened =  reader.open()
        if (!opened) return false
        return powerOn()
    }

    override fun close(): Boolean {
        try {
            reader.iccPowerOff()
        } catch (e: Exception) { /**/}
        return try {
            reader.close()
            true
        }catch (e: Exception) { false }
    }

    override fun powerOn(): Boolean {
        return reader.iccPowerOn()
    }

    override fun powerOff(): Boolean {
        return reader.iccPowerOff()
    }
}