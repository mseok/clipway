package dev.mseok.clipway

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.mseok.clipway.protocol.Wire
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Finds paired Macs on the current Wi-Fi through Bonjour, so a changed LAN address still works. */
class MacLocator(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val hosts = ConcurrentHashMap<String, String>()
    private var listener: NsdManager.DiscoveryListener? = null

    fun hostFor(macId: String): String? = hosts[macId]

    @Synchronized
    fun start() {
        if (listener != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        runCatching {
            nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            listener = discovery
        }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        hosts.clear()
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val id = resolved.attributes["id"]?.let(::String) ?: return
                val addresses = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses else listOf(resolved.host)
                val address = addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
                hosts[id] = address
            }
        }
        runCatching { nsd.resolveService(info, executor, callback) }
    }
}
