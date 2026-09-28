package com.example.myapplication.media.remote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Интерфейс локальной сети телефона и его IPv4 с маской. */
data class LanInterface(val name: String, val address: Inet4Address, val prefixLength: Int) {
    fun contains(host: InetAddress?): Boolean {
        val target = host as? Inet4Address ?: return false
        val mask = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength)
        return (address.toInt() and mask) == (target.toInt() and mask)
    }
}

internal fun Inet4Address.toInt(): Int = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

/**
 * Локальная сеть — это не только Wi‑Fi: точка доступа телефона (ТВ подключён к нему), USB- и
 * Bluetooth-модем. Мобильная сеть (у неё тоже бывают «частные» адреса 10.x) и VPN — не локальная
 * сеть телевизора, поэтому исключаются по имени интерфейса.
 */
object LanNetwork {
    private val EXCLUDED = listOf("rmnet", "ccmni", "pdp", "wwan", "v4-", "tun", "ppp", "ipsec", "dummy", "clat", "lo")

    fun interfaces(): List<LanInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { nif ->
            if (!nif.isUp || nif.isLoopback || nif.isVirtual || !nif.supportsMulticast()) return@flatMap emptyList()
            val name = nif.name.lowercase()
            if (EXCLUDED.any { name.startsWith(it) }) return@flatMap emptyList()
            nif.interfaceAddresses.mapNotNull { ia ->
                val a = ia.address as? Inet4Address ?: return@mapNotNull null
                if (!a.isSiteLocalAddress) return@mapNotNull null
                LanInterface(nif.name, a, ia.networkPrefixLength.toInt())
            }
        }
    }.getOrDefault(emptyList())

    /** Адрес телефона, видимый устройству [host] (та же подсеть), иначе — первый LAN-адрес. */
    fun addressFor(host: String?): Inet4Address? {
        val lan = interfaces()
        val target = host?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        return (lan.firstOrNull { it.contains(target) } ?: lan.firstOrNull())?.address
    }

    fun hasLan(): Boolean = interfaces().isNotEmpty()

    /** Multicast на Wi‑Fi выключен ради экономии батареи — на время поиска его включают явно. */
    fun multicastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("vetro-remote-discovery")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()
}
