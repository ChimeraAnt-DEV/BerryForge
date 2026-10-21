package dev.chimeraant.berryforge.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural tests for the pinned Termux package manifest.
 *
 * The shipped bug this guards: every package entry pointed at a Termux index version
 * that no longer existed, so eight URLs — including openjdk-21 — returned 404 and the
 * whole JDK install failed. The checks below cannot hit the network, but they do enforce
 * the shape of every entry and catch the usual ways the manifest rots:
 *
 *  - a filename that cannot correspond to a real Termux `pool/` path
 *  - a hash that is not 64 lowercase hex characters
 *  - a non-positive or absurd size
 *  - duplicate package names (which would silently keep a stale entry)
 */
class ToolchainManifestTest {

    private val allPackages: List<TermuxPackage> =
        ToolchainManifest.JDK_PACKAGES + ToolchainManifest.ADB_PACKAGES

    private val REASONABLE_MAX_DEB_BYTES = 512L * 1024 * 1024

    @Test
    fun manifestHasAllKeyComponents() {
        val names = allPackages.map { it.name }.toSet()
        assertTrue("openjdk-21 must be present for javac to exist", "openjdk-21" in names)
        assertTrue("android-tools must be present for adb", "android-tools" in names)
        assertTrue("ca-certificates-java is what ships the trust store", "ca-certificates-java" in names)
    }

    @Test
    fun everyEntryIsAWellFormedTermuxDeb() {
        allPackages.forEach { pkg ->
            with(pkg) {
                assertTrue(
                    "$name: filename '$filename' is not under the termux-main pool",
                    filename.startsWith("pool/") && filename.endsWith(".deb"),
                )
                assertFalse(
                    "$name: filename contains a URL-unsafe space",
                    filename.any { it == ' ' },
                )
            }
        }
    }

    @Test
    fun everyHashIsSixtyFourLowercaseHex() {
        allPackages.forEach { pkg ->
            assertTrue(
                "${pkg.name}: hash '${pkg.sha256}' is not 64 lowercase hex characters",
                pkg.sha256.matches(Regex("[0-9a-f]{64}")),
            )
        }
    }

    @Test
    fun sizesAreSane() {
        allPackages.forEach { pkg ->
            assertTrue("${pkg.name}: size ${pkg.size} is not positive", pkg.size > 0)
            assertTrue(
                "${pkg.name}: size ${pkg.size} exceeds the sanity bound",
                pkg.size < REASONABLE_MAX_DEB_BYTES,
            )
        }
        // openjdk-21 is the dominant download and should sit in the 50–150 MB band.
        val jdk = allPackages.first { it.name == "openjdk-21" }
        assertTrue("openjdk-21 size looks wrong: ${jdk.size}", jdk.size in 50_000_000..150_000_000)
    }

    @Test
    fun noDuplicatePackageNamesWithinAList() {
        // The JDK and ADB lists legitimately share runtime deps (abseil-cpp, libc++,
        // zlib). What must not happen is the same package listed twice inside one list,
        // which would silently keep a stale entry alive.
        listOf(ToolchainManifest.JDK_PACKAGES, ToolchainManifest.ADB_PACKAGES).forEach { list ->
            val duplicates = list.groupBy { it.name }.filter { it.value.size > 1 }.keys
            assertTrue("duplicate pinned packages in one list: $duplicates", duplicates.isEmpty())
        }
    }
}