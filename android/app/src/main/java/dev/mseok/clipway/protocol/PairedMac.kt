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

        /** Parses clipway://pair?v=1&id=..&name=..&psk=<base64url>&port=..&hosts=a,b */
        fun fromPairingLink(link: String): PairedMac? = runCatching {
            val uri = URI(link.trim())
            if (uri.scheme != "clipway" || uri.host != "pair") return null
            val query = uri.rawQuery.split("&").associate {
                val (key, value) = it.split("=", limit = 2)
                key to URLDecoder.decode(value, "UTF-8")
            }
            if (query["v"] != Wire.VERSION.toString()) return null
            val psk = Base64.getUrlDecoder().decode(query.getValue("psk"))
            if (psk.size != 32) return null
            PairedMac(
                id = query.getValue("id"),
                name = query.getValue("name"),
                psk = psk,
                port = query.getValue("port").toInt(),
                hosts = query.getValue("hosts").split(",").filter(String::isNotBlank),
            )
        }.getOrNull()
    }
}
