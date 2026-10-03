package dev.mseok.clipway

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dev.mseok.clipway.protocol.Wire
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Finds Clipway Macs on the current Wi-Fi through Bonjour, so a changed LAN address
 * still works. The advertisement carries no identifier: every address found is tried,
 * and only the Mac holding the pairing key can complete the handshake.
 */
class MacLocator(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val found = ConcurrentHashMap<String, String>()  // service name -> IPv4 address
    private var listener: NsdManager.DiscoveryListener? = null

    fun hosts(): List<String> = found.values.distinct().take(MAX_HOSTS)

    @Synchronized
    fun start() {
        if (listener != null) return
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
            }
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
        found.clear()
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val addresses = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses else listOf(resolved.host)
                // Anyone on the Wi-Fi can answer mDNS. Only addresses on this phone's own
                // subnets are accepted, so an answer cannot point the phone elsewhere.
                val address = addresses.filterIsInstance<Inet4Address>().firstOrNull(::isOnLink) ?: return
                if (found.size < MAX_HOSTS || found.containsKey(resolved.serviceName)) {
                    found[resolved.serviceName] = address.hostAddress ?: return
                }
            }
        }
        runCatching { nsd.resolveService(info, executor, callback) }
    }

    private fun isOnLink(address: Inet4Address): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filterNot { it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .any { local ->
                val own = local.address as? Inet4Address ?: return@any false
                if (own == address) return@any false  // this phone itself is never a Mac
                val prefix = local.networkPrefixLength.toInt()
                if (prefix !in 1..31) return@any false
                val mask = -1 shl (32 - prefix)
                toInt(own) and mask == toInt(address) and mask
            }
    }.getOrDefault(false)

    private fun toInt(address: Inet4Address) = address.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }

    private companion object {
        const val MAX_HOSTS = 8
    }
}
