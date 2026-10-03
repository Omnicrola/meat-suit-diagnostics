package com.meatsuitdiagnostics.app.domain

import java.net.URI
import java.net.URLDecoder

/** The contents of the admin app's setup QR code: meatsuit://setup?url=<https server>&key=<api key>. */
data class SetupLink(val serverUrl: String, val apiKey: String) {

    val serverHost: String get() = URI(serverUrl).host

    companion object {
        /** The development machine as seen from the Android emulator. */
        const val EMULATOR_HOST = "10.0.2.2"

        /**
         * Parses a setup link. The server must use HTTPS, except that [allowHttpToEmulatorHost] (debug builds only)
         * permits plain HTTP to [EMULATOR_HOST] for testing against a local server.
         */
        fun parse(raw: String, allowHttpToEmulatorHost: Boolean = false): SetupLink? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "meatsuit" || uri.host != "setup") return null
            val params = (uri.rawQuery ?: return null).split("&").mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                if (pieces.size == 2) decode(pieces[0]) to decode(pieces[1]) else null
            }.toMap()

            val url = params["url"]?.trimEnd('/') ?: return null
            val key = params["key"] ?: return null
            val server = runCatching { URI(url) }.getOrNull() ?: return null
            if (server.host.isNullOrEmpty()) return null
            val secure = server.scheme == "https"
            val emulatorDev = allowHttpToEmulatorHost && server.scheme == "http" && server.host == EMULATOR_HOST
            if (!secure && !emulatorDev) return null
            if (!key.startsWith("msd_") || key.length < 20) return null
            return SetupLink(url, key)
        }

        private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}
