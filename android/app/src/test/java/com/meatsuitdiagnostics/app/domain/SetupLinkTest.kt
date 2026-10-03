package com.meatsuitdiagnostics.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLinkTest {
    private val key = "msd_AbCdEfGhIjKlMnOpQrStUvWxYz-_0123456789abcde"

    @Test
    fun parsesAdminAppQrCode() {
        // Exactly what server/app/admin/system.py:setup_uri produces.
        val link = SetupLink.parse("meatsuit://setup?url=https%3A%2F%2Fmsd.up.railway.app&key=$key")
        assertEquals(SetupLink("https://msd.up.railway.app", key), link)
        assertEquals("msd.up.railway.app", link?.serverHost)
    }

    @Test
    fun stripsTrailingSlash() {
        assertEquals("https://example.com", SetupLink.parse("meatsuit://setup?url=https%3A%2F%2Fexample.com%2F&key=$key")?.serverUrl)
    }

    @Test
    fun rejectsPlainHttp() {
        assertNull(SetupLink.parse("meatsuit://setup?url=http%3A%2F%2Fexample.com&key=$key"))
    }

    @Test
    fun httpToEmulatorHostOnlyWhenAllowed() {
        val raw = "meatsuit://setup?url=http%3A%2F%2F10.0.2.2%3A8000&key=$key"
        assertNull(SetupLink.parse(raw))
        assertEquals("http://10.0.2.2:8000", SetupLink.parse(raw, allowHttpToEmulatorHost = true)?.serverUrl)
        assertNull(SetupLink.parse("meatsuit://setup?url=http%3A%2F%2Fexample.com&key=$key", allowHttpToEmulatorHost = true))
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(SetupLink.parse("https://example.com"))
        assertNull(SetupLink.parse("meatsuit://other?url=https%3A%2F%2Fexample.com&key=$key"))
        assertNull(SetupLink.parse("meatsuit://setup?url=https%3A%2F%2Fexample.com"))
        assertNull(SetupLink.parse("meatsuit://setup?url=https%3A%2F%2Fexample.com&key=notakey"))
        assertNull(SetupLink.parse("not a uri at all"))
    }
}
