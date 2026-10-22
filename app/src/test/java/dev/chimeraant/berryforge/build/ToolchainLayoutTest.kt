package dev.chimeraant.berryforge.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for the toolchain's on-disk layout.
 *
 * Three separate shipped bugs are pinned here, each of which left the JDK install
 * "successful" while every build afterwards failed:
 *
 *  1. **Nested build-tools.** The tarball is rooted at `build-tools/<version>/…` and was
 *     extracted without stripping that segment, so the tree became
 *     `build-tools/build-tools/34.0.4/`. The version directory was then chosen by
 *     alphabetically-highest name, which picked the *outer* `build-tools` directory,
 *     `aapt2` was never found, and every later install saw a non-empty directory and
 *     skipped re-extraction — a broken install that could never heal.
 *  2. **Trust store under the wrong JDK.** `ca-certificates-java` unpacks to
 *     `java-17-openjdk/lib/security/jssecacerts` while the installed JDK is
 *     `java-21-openjdk`, so a lookup that named one version found nothing.
 *  3. **Unrunnable shell tools.** `d8`, `apksigner` and `lld` carry `#!/bin/bash`, which
 *     Android does not have; and the rewrite only looked in `usr/bin`, never in the
 *     build-tools directory.
 */
class ToolchainLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- 1. build-tools resolution ----

    @Test
    fun `the normal layout resolves the version directory`() {
        val parent = temp.newFolder("build-tools")
        val version = File(parent, "34.0.4").apply { mkdirs() }
        File(version, "aapt2").writeText("elf")

        assertEquals(version, resolveBuildToolsDir(parent))
    }

    @Test
    fun `the legacy nested layout still resolves`() {
        // This is exactly what the old extraction produced, and it must heal rather than
        // demand a wipe.
        val parent = temp.newFolder("build-tools")
        val nested = File(parent, "build-tools/34.0.4").apply { mkdirs() }
        File(nested, "aapt2").writeText("elf")

        assertEquals(nested, resolveBuildToolsDir(parent))
    }

    @Test
    fun `a directory without aapt2 is never chosen`() {
        // The old name-based pick selected the outer `build-tools` directory here, because
        // "build-tools" sorts after "34.0.4". It has no aapt2, so it must not be returned.
        val parent = temp.newFolder("build-tools")
        File(parent, "build-tools").mkdirs()
        File(parent, "34.0.4").mkdirs()

        assertNull(resolveBuildToolsDir(parent))
    }

    @Test
    fun `an empty directory resolves to null`() {
        assertNull(resolveBuildToolsDir(temp.newFolder("empty")))
    }

    // ---- 2. trust store discovery ----

    @Test
    fun `a trust store under a different jdk version is found`() {
        // ca-certificates-java writes java-17 even when java-21 is installed.
        val jvm = temp.newFolder("jvm")
        val seventeen = File(jvm, "java-17-openjdk/lib/security").apply { mkdirs() }
        val store = File(seventeen, "jssecacerts").apply { writeText("certs") }
        File(jvm, "java-21-openjdk/lib/security").mkdirs()

        val candidates = trustStoreCandidatesIn(jvm)

        assertTrue("the java-17 store must be a candidate", candidates.contains(store))
    }

    @Test
    fun `jssecacerts is preferred over cacerts`() {
        val jvm = temp.newFolder("jvm")
        val security = File(jvm, "java-21-openjdk/lib/security").apply { mkdirs() }
        File(security, "cacerts").writeText("a")
        val jsse = File(security, "jssecacerts").apply { writeText("b") }

        val candidates = trustStoreCandidatesIn(jvm)

        assertEquals("the JVM prefers jssecacerts, so it is listed first", jsse, candidates.first())
    }

    @Test
    fun `an absent jvm directory yields no candidates`() {
        assertTrue(trustStoreCandidatesIn(File(temp.root, "nope")).isEmpty())
    }

    // ---- 3. shebang rewriting ----

    private val platform = "#!/system/bin/sh"

    @Test
    fun `a bash shebang is rewritten to the platform shell`() {
        val original = "#!/bin/bash\necho hi\n".toByteArray()
        val rewritten = withPlatformShebang(original, platform)

        assertTrue("a /bin/bash script must be rewritten", rewritten != null)
        assertEquals(platform + "\necho hi\n", String(rewritten!!))
    }

    @Test
    fun `a termux shebang is rewritten`() {
        val original = "#!/data/data/com.termux/files/usr/bin/sh\nexec java\n".toByteArray()
        val rewritten = withPlatformShebang(original, platform)

        assertEquals(platform + "\nexec java\n", String(rewritten!!))
    }

    @Test
    fun `an elf binary is left untouched`() {
        // 0x7F 'E' 'L' 'F' — the build-tools aapt2 header.
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1)
        assertNull(withPlatformShebang(elf, platform))
    }

    @Test
    fun `a jar is left untouched`() {
        val jar = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        assertNull(withPlatformShebang(jar, platform))
    }

    @Test
    fun `rewriting is idempotent`() {
        // A second pass must be a no-op, otherwise every install would rewrite the file and
        // the operation could never be called twice safely.
        val once = withPlatformShebang("#!/bin/bash\necho hi\n".toByteArray(), platform)!!
        assertNull(withPlatformShebang(once, platform))
    }

    @Test
    fun `bytes after the shebang survive the rewrite`() {
        // The rewrite must splice raw bytes, not round-trip through a charset: a script
        // containing a non-UTF-8 byte would otherwise be corrupted.
        val body = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'x'.code.toByte())
        val original = platform.toByteArray() + "\n".toByteArray() + body
        val withBash = "#!/bin/bash".toByteArray() + "\n".toByteArray() + body

        val rewritten = withPlatformShebang(withBash, platform)!!

        assertTrue(
            "the raw body must be preserved byte for byte",
            rewritten.copyOfRange(platform.length + 1, rewritten.size).contentEquals(body),
        )
        assertEquals(platform.length, rewritten.size - body.size - 1)
    }

    @Test
    fun `a shebang with no newline is handled`() {
        val rewritten = withPlatformShebang("#!/bin/bash".toByteArray(), platform)
        assertEquals(platform, String(rewritten!!))
    }

    @Test
    fun `an empty file is left untouched`() {
        assertNull(withPlatformShebang(ByteArray(0), platform))
        assertNull(withPlatformShebang(byteArrayOf('#'.code.toByte()), platform))
    }

    @Test
    fun `a file that is not a script is left untouched`() {
        assertNull(withPlatformShebang("just text\n".toByteArray(), platform))
    }

    @Test
    fun `only the first line is rewritten`() {
        val original = "#!/bin/bash\n#!/bin/bash\n".toByteArray()
        val rewritten = String(withPlatformShebang(original, platform)!!)
        assertEquals("$platform\n#!/bin/bash\n", rewritten)
    }

    @Test
    fun `the shipped d8 script shape is rewritten correctly`() {
        // Verbatim opening of the real build-tools d8 script.
        val d8 = "#!/bin/bash\n#\n# Copyright (C) 2017 The Android Open Source Project\n" +
            "jarfile=d8.jar\nlibdir=\"\$progdir\"\n"
        val rewritten = String(withPlatformShebang(d8.toByteArray(), platform)!!)

        assertTrue(rewritten.startsWith(platform + "\n"))
        assertTrue("the body must be intact", rewritten.contains("jarfile=d8.jar"))
        assertFalse("no /bin/bash reference may remain on line one", rewritten.startsWith("#!/bin/bash"))
    }
}
