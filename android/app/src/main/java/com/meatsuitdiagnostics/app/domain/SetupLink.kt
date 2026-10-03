package com.meatsuitdiagnostics.app.domain

import java.net.URI
import java.net.URLDecoder

/** The contents of the admin app's setup QR code: meatsuit://setup?url=<https server>&key=<api key>. */
data class SetupLink(val serverUrl: String, val apiKey: String) {

    val serverHost: String get() = URI(serverUrl).host

    companion object {
        fun parse(raw: String): SetupLink? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "meatsuit" || uri.host != "setup") return null
            val params = (uri.rawQuery ?: return null).split("&").mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                if (pieces.size == 2) decode(pieces[0]) to decode(pieces[1]) else null
            }.toMap()

            val url = params["url"]?.trimEnd('/') ?: return null
            val key = params["key"] ?: return null
            val server = runCatching { URI(url) }.getOrNull() ?: return null
            if (server.scheme != "https" || server.host.isNullOrEmpty()) return null
            if (!key.startsWith("msd_") || key.length < 20) return null
            return SetupLink(url, key)
        }

        private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}
