package com.example.access_control_solution

import android.app.Application
import android.util.Log
import com.example.neurotecsdklibrary.NeurotecLicenseHelper
import com.neurotec.lang.NCore
import com.neurotec.licensing.NLicenseManager
import com.neurotec.plugins.NDataFileManager

class MatchApplication : Application() {

    companion object {
        private const val TAG = "FaceMatchApplication"
        @Volatile
        var areFaceLicensesActivated = false
            private set
    }

    override fun onCreate() {
        super.onCreate()

        try {
            NLicenseManager.setTrialMode(true)
            Log.d("NeurotecLicense", "Trial mode enabled")
        } catch (e: Exception) {
            Log.e("NeurotecLicense", "Failed to set trial mode", e)
        }

        try {
            NCore.setContext(this@MatchApplication)
            NDataFileManager.getInstance().addFromDirectory("data", false)
        }catch (e: Exception){
            Log.e(TAG, "Failed to set NCore context", e)
        }


        // Activate face licenses
        try {
            areFaceLicensesActivated = NeurotecLicenseHelper.obtainFaceLicenses(this@MatchApplication)
            if (areFaceLicensesActivated) {
                Log.d(TAG, " Licenses activated successfully")
            } else {
                Log.e(TAG, " License activation FAILED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error doing background Neurotec initialization", e)
        }


    }

    override fun onTerminate() {
        super.onTerminate()

        NeurotecLicenseHelper.release()
    }


}