package com.example.myapplication.audiobooks.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Есть ли у устройства интернет: без него сбой — не вина сайта, и цепочку источников не трогаем. */
internal object NetworkState {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
