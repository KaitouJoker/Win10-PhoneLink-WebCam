package com.win10phonelink.webcam.net

import org.json.JSONObject

data class PairingPayload(
    val version: Int = 1,
    val ip: String,
    val port: Int = 9876,
    val token: String,
    val name: String = "",
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    fun toDisplaySummary(): String {
        val hostLabel = name.ifBlank { "Windows 10 PC" }
        return "$hostLabel • $ip:$port"
    }

    fun toJsonString(): String {
        return JSONObject().apply {
            put("v", version)
            put("ip", ip)
            put("port", port)
            put("token", token)
            put("name", name)
            if (!codec.isNullOrBlank()) put("codec", codec)
            if (width != null && width > 0) put("width", width)
            if (height != null && height > 0) put("height", height)
        }.toString()
    }

    companion object {
        fun fromQrJson(raw: String): PairingPayload? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                val obj = JSONObject(trimmed)
                val version = obj.optInt("v", 1)
                if (version != 1) return null
                val ip = obj.optString("ip", "").trim()
                val port = obj.optInt("port", 9876)
                val token = obj.optString("token", "").trim()
                val name = obj.optString("name", "").trim()
                val codec = obj.optString("codec", "").trim().takeIf { it.isNotEmpty() }
                val width = obj.optInt("width", 0).takeIf { it > 0 }
                val height = obj.optInt("height", 0).takeIf { it > 0 }
                if (ip.isEmpty() || port !in 1..65535 || token.isEmpty()) {
                    return null
                }
                PairingPayload(
                    version = version,
                    ip = ip,
                    port = port,
                    token = token,
                    name = name,
                    codec = codec,
                    width = width,
                    height = height
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
