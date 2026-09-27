package com.example.myapplication.data.remote

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.example.myapplication.network.enrichment.GoogleApiIdentity
import java.security.MessageDigest

/**
 * Пакет и SHA-1 сертификата подписи этого APK — то, по чему Google пропускает ключ, ограниченный
 * Android-приложением. Считается один раз: подпись установленного APK не меняется.
 */
class AndroidGoogleApiIdentity(context: Context) : GoogleApiIdentity {
    private val headers: Map<String, String> by lazy {
        val app = context.applicationContext
        val sha1 = runCatching { signingSha1(app) }.getOrNull()
        buildMap {
            put("X-Android-Package", app.packageName)
            sha1?.let { put("X-Android-Cert", it) }
        }
    }

    override fun headers(): Map<String, String> = headers

    private fun signingSha1(context: Context): String? {
        val pm = context.packageManager
        val signature: Signature? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            (if (info?.hasMultipleSigners() == true) info.apkContentsSigners else info?.signingCertificateHistory)?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        return signature?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }
        }
    }
}
