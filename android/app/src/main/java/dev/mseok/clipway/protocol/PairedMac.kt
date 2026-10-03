package dev.mseok.clipway.protocol

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** A Mac this phone was paired with by scanning its QR code. */
data class PairedMac(
    val id: String,
    val name: String,
    val psk: ByteArray,
    val port: Int,
    /** Addresses from the QR code: LAN address at pairing time and the Tailscale address. */
    val hosts: List<String>,
    /** The address that worked last; tried first. */
    val lastHost: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("psk", Base64.getEncoder().encodeToString(psk))
        .put("port", port)
        .put("hosts", JSONArray(hosts))
        .put("lastHost", lastHost ?: "")

    override fun equals(other: Any?) = other is PairedMac && other.id == id &&
        other.name == name && other.psk.contentEquals(psk) && other.port == port &&
        other.hosts == hosts && other.lastHost == lastHost

    override fun hashCode() = id.hashCode()

    companion object {
        fun fromJson(json: JSONObject): PairedMac {
            val hosts = json.getJSONArray("hosts")
            return PairedMac(
                id = json.getString("id"),
                name = json.getString("name"),
                psk = Base64.getDecoder().decode(json.getString("psk")),
                port = json.getInt("port"),
                hosts = List(hosts.length(), hosts::getString),
                lastHost = json.optString("lastHost").ifEmpty { null },
            )
        }

        /**
         * Parses clipway://pair?v=1&id=..&name=..&psk=<base64url>&port=..&hosts=a,b
         *
         * A link can come from anywhere (a QR code, another app, a web page), so it is
         * validated strictly and the caller must still ask the user before pairing. Only
         * numeric addresses on private networks are kept: a link cannot point the phone at
         * a server on the internet. Macs on the same Wi-Fi are still found through Bonjour.
         */
        fun fromPairingLink(link: String): PairedMac? = runCatching {
            if (link.length > 2048) return null
            val uri = URI(link.trim())
            if (uri.scheme != "clipway" || uri.host != "pair") return null
            val query = uri.rawQuery.split("&").associate {
                val (key, value) = it.split("=", limit = 2)
                key to URLDecoder.decode(value, "UTF-8")
            }
            if (query["v"] != Wire.VERSION.toString()) return null
            val psk = Base64.getUrlDecoder().decode(query.getValue("psk"))
            if (psk.size != 32) return null
            val id = query.getValue("id")
            if (id.isEmpty() || id.length > 64 || id.any { it.isISOControl() }) return null
            val name = query.getValue("name").filterNot { it.isISOControl() }.trim().take(Wire.MAX_NAME_LENGTH)
            if (name.isEmpty()) return null
            val port = query.getValue("port").toInt()
            if (port !in 1..65535) return null
            PairedMac(
                id = id,
                name = name,
                psk = psk,
                port = port,
                hosts = query.getValue("hosts").split(",").filter(::isPrivateIpv4).distinct().take(8),
            )
        }.getOrNull()

        /** True for dotted IPv4 literals in private, carrier-grade NAT (Tailscale) or link-local ranges. */
        fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split(".")
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return false
            val (a, b) = parts.map(String::toInt).also { octets -> if (octets.any { it > 255 }) return false }
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254)
        }
    }
}
