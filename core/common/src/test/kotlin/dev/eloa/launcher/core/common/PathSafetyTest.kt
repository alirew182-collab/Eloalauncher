package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.io.PathSafety
import dev.eloa.launcher.core.common.io.PathTraversalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `resolves normal relative path inside root`() {
        val root = tmp.newFolder("root")
        val f = PathSafety.resolveInside(root, "a/b/c.txt")
        assertEquals(File(root.canonicalFile, "a/b/c.txt").path, f.path)
    }

    @Test fun `dot segments are ignored`() {
        val root = tmp.newFolder("root")
        assertEquals(File(root.canonicalFile, "a/b").path, PathSafety.resolveInside(root, "./a/./b").path)
    }

    @Test(expected = PathTraversalException::class) fun `rejects parent traversal`() {
        PathSafety.resolveInside(tmp.newFolder("r"), "../evil.txt")
    }

    @Test(expected = PathTraversalException::class) fun `rejects nested traversal`() {
        PathSafety.resolveInside(tmp.newFolder("r"), "a/../../evil.txt")
    }

    @Test(expected = PathTraversalException::class) fun `rejects backslash traversal`() {
        PathSafety.resolveInside(tmp.newFolder("r"), "a\\..\\..\\evil.txt")
    }

    @Test(expected = PathTraversalException::class) fun `rejects absolute paths`() {
        PathSafety.resolveInside(tmp.newFolder("r"), "/etc/passwd")
    }

    @Test(expected = PathTraversalException::class) fun `rejects windows drive paths`() {
        PathSafety.resolveInside(tmp.newFolder("r"), "C:/Windows/x")
    }

    @Test(expected = PathTraversalException::class) fun `rejects NUL bytes`() {
        PathSafety.resolveInside(tmp.newFolder("r"), "a\u0000b")
    }

    @Test fun `rejects symlink escaping root`() {
        val root = tmp.newFolder("root")
        val outside = tmp.newFolder("outside")
        val link = File(root, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        var thrown = false
        try { PathSafety.resolveInside(root, "link/file.txt") } catch (_: PathTraversalException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun `sanitizes file names`() {
        assertEquals("a_b_c", PathSafety.sanitizeFileName("a/b\\c"))
        assertEquals("file", PathSafety.sanitizeFileName("..."))
        assertEquals("_CON.txt", PathSafety.sanitizeFileName("CON.txt"))
        assertEquals("My World", PathSafety.sanitizeFileName("  My World  "))
        assertTrue(PathSafety.sanitizeFileName("x".repeat(500) + ".jar").length <= 120)
        assertTrue(PathSafety.sanitizeFileName("x".repeat(500) + ".jar").endsWith(".jar"))
        assertFalse(PathSafety.isValidFileName("a/b"))
        assertTrue(PathSafety.isValidFileName("mod.jar"))
    }
}
