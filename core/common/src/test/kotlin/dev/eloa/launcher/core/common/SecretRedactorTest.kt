package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.logging.SecretRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretRedactorTest {
    @Test fun `masks tokens in json and query strings`() {
        val s = SecretRedactor.redact("""{"access_token":"abc.def","refresh_token": "xyz"} url?code=1&access_token=SECRET""")
        assertFalse(s.contains("abc.def")); assertFalse(s.contains("xyz")); assertFalse(s.contains("SECRET"))
    }

    @Test fun `masks bearer and jwt and xbl`() {
        val s = SecretRedactor.redact("Authorization: Bearer abcdef123 then eyJhbGciOiJIUzI1.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2QT4 and XBL3.0 x=uhs;tokenvalue")
        assertFalse(s.contains("abcdef123")); assertFalse(s.contains("SflKxwRJSMeKKF2QT4")); assertFalse(s.contains("tokenvalue"))
    }

    @Test fun `context keys masked`() {
        val c = SecretRedactor.redactContext(mapOf("refreshToken" to "x", "file" to "a.jar"))
        assertEquals("***", c["refreshToken"]); assertEquals("a.jar", c["file"])
    }
}
