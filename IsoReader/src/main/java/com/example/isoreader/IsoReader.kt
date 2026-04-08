package com.example.isoreader

import android.content.Context
import android.nfc.tech.IsoDep
import android.util.Log


interface IsoReader {
    fun transceive(arr: ByteArray) : ByteArray?
    fun open() : Boolean
    fun close() : Boolean
    fun powerOn() : Boolean
    fun powerOff() : Boolean

}

class ContactlessReader(private val dep: IsoDep) : IsoReader {
    override fun transceive(arr: ByteArray): ByteArray? {
        return dep.transceive(arr)
    }

    override fun open(): Boolean {
        return try {
            dep.connect()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun close(): Boolean {
        return try {
            dep.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun powerOn(): Boolean {
        return true
    }

    override fun powerOff(): Boolean {
        return true
    }


}