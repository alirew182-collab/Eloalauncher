package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.hash.HashAlgorithm
import dev.eloa.launcher.core.common.hash.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HashingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `known vectors`() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hashing.digest("abc".toByteArray(), HashAlgorithm.SHA1))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.digest("abc".toByteArray(), HashAlgorithm.SHA256))
    }

    @Test fun `verify file with mixed case and rejects malformed`() {
        val f = tmp.newFile(); f.writeText("abc")
        assertTrue(Hashing.verify(f, "A9993E364706816ABA3E25717850C26C9CD0D89D", HashAlgorithm.SHA1))
        assertFalse(Hashing.verify(f, "a9993e36", HashAlgorithm.SHA1))
        assertFalse(Hashing.verify(f, "z".repeat(40), HashAlgorithm.SHA1))
        assertFalse(Hashing.verify(f, "0".repeat(40), HashAlgorithm.SHA1))
    }
}
