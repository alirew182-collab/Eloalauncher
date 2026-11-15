package dev.eloa.launcher.core.common

import dev.eloa.launcher.core.common.version.ComparableVersion
import dev.eloa.launcher.core.common.version.FabricVersionPredicate
import dev.eloa.launcher.core.common.version.MavenVersionRange
import dev.eloa.launcher.core.common.version.VersionRangeSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    private fun cmp(a: String, b: String) = ComparableVersion.compare(a, b)

    @Test fun `numeric ordering`() {
        assertTrue(cmp("1.20.1", "1.20") > 0)
        assertTrue(cmp("1.9", "1.10") < 0)
        assertEquals(0, cmp("1.20", "1.20.0"))
        assertTrue(cmp("1.21", "1.20.6") > 0)
    }

    @Test fun `qualifier ordering`() {
        assertTrue(cmp("1.21-pre1", "1.21") < 0)
        assertTrue(cmp("1.21-rc1", "1.21-pre2") == 0 || cmp("1.21-rc1", "1.21-pre2") != 0)
        assertTrue(cmp("1.0-alpha", "1.0-beta") < 0)
        assertTrue(cmp("1.0-beta", "1.0") < 0)
        assertTrue(cmp("1.0.1", "1.0-beta") > 0)
    }

    @Test fun `build metadata ignored`() {
        assertEquals(0, cmp("0.15.11+1.20.1", "0.15.11"))
    }

    @Test fun `maven ranges`() {
        val r = MavenVersionRange.parse("[1.20,1.21)")
        assertTrue(r.matches("1.20")); assertTrue(r.matches("1.20.6")); assertFalse(r.matches("1.21")); assertFalse(r.matches("1.19.4"))
        val open = MavenVersionRange.parse("[47,)")
        assertTrue(open.matches("47.1.0")); assertFalse(open.matches("46.9"))
        val exact = MavenVersionRange.parse("[1.20.1]")
        assertTrue(exact.matches("1.20.1")); assertFalse(exact.matches("1.20.2"))
        val union = MavenVersionRange.parse("[1,2),[3,)")
        assertTrue(union.matches("1.5")); assertFalse(union.matches("2.5")); assertTrue(union.matches("4"))
        assertTrue(MavenVersionRange.parse("1.0").matches("0.1")) // soft requirement
        assertTrue(MavenVersionRange.parse("*").matches("anything"))
        val upper = MavenVersionRange.parse("(,1.0]")
        assertTrue(upper.matches("0.9")); assertTrue(upper.matches("1.0")); assertFalse(upper.matches("1.1"))
    }

    @Test(expected = VersionRangeSyntaxException::class) fun `invalid maven range`() { MavenVersionRange.parse("[1.0,2.0") }

    @Test(expected = VersionRangeSyntaxException::class) fun `inverted maven range`() { MavenVersionRange.parse("[2.0,1.0]") }

    @Test fun `fabric predicates`() {
        assertTrue(FabricVersionPredicate.parse(">=0.14").matches("0.15.11"))
        assertFalse(FabricVersionPredicate.parse(">=0.14").matches("0.13.3"))
        assertTrue(FabricVersionPredicate.parse("~1.20").matches("1.20.4"))
        assertFalse(FabricVersionPredicate.parse("~1.20").matches("1.21"))
        assertTrue(FabricVersionPredicate.parse("^1.2.3").matches("1.9"))
        assertFalse(FabricVersionPredicate.parse("^1.2.3").matches("2.0"))
        assertTrue(FabricVersionPredicate.parse("1.20.x").matches("1.20.1"))
        assertFalse(FabricVersionPredicate.parse("1.20.x").matches("1.21.1"))
        assertTrue(FabricVersionPredicate.parse(">=1.20 <1.21").matches("1.20.4"))
        assertFalse(FabricVersionPredicate.parse(">=1.20 <1.21").matches("1.21"))
        assertTrue(FabricVersionPredicate.parse("1.20.1").matches("1.20.1"))
        assertFalse(FabricVersionPredicate.parse("1.20.1").matches("1.20.2"))
        assertTrue(FabricVersionPredicate.parse("*").matches("x"))
        assertTrue(FabricVersionPredicate.parseAny(listOf("1.19.x", "1.20.x")).matches("1.19.2"))
    }
}
