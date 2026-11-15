package dev.eloa.launcher.core.common.hash

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class HashAlgorithm(val jcaName: String, val hexLength: Int) {
    SHA1("SHA-1", 40), SHA256("SHA-256", 64), SHA512("SHA-512", 128);
}

object Hashing {
    fun digest(input: InputStream, algorithm: HashAlgorithm): String {
        val md = MessageDigest.getInstance(algorithm.jcaName)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().toHex()
    }

    fun digest(file: File, algorithm: HashAlgorithm): String = file.inputStream().use { digest(it, algorithm) }

    fun digest(bytes: ByteArray, algorithm: HashAlgorithm): String =
        MessageDigest.getInstance(algorithm.jcaName).digest(bytes).toHex()

    /** Compares an expected checksum (case-insensitive hex) with the file content. */
    fun verify(file: File, expectedHex: String, algorithm: HashAlgorithm): Boolean {
        val expected = expectedHex.trim().lowercase()
        if (expected.length != algorithm.hexLength || !expected.all { it in '0'..'9' || it in 'a'..'f' }) return false
        return digest(file, algorithm) == expected
    }

    fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(chars)
    }
}
