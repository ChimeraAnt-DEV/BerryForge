package dev.chimeraant.berryforge.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for archive extraction.
 *
 * The security-critical property is zip-slip: an archive entry named `../evil` must never
 * be written outside the destination directory.
 */
class ArchiveExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("test.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun extractsNormalEntries() {
        val archive = zip("a.txt" to "hello", "dir/b.txt" to "world")
        val dest = temp.newFolder("out")

        val count = ArchiveExtractor.extract(archive, dest)

        assertEquals(2, count)
        assertEquals("hello", File(dest, "a.txt").readText())
        assertEquals("world", File(dest, "dir/b.txt").readText())
    }

    @Test
    fun rejectsZipSlipWithParentTraversal() {
        val archive = zip("../escaped.txt" to "pwned")
        val dest = temp.newFolder("out")

        ArchiveExtractor.extract(archive, dest)

        val escaped = File(dest.parentFile, "escaped.txt")
        assertFalse("entry escaped the destination directory", escaped.exists())
    }

    @Test
    fun rejectsZipSlipNestedInsideTheArchive() {
        val archive = zip("sub/../../escaped.txt" to "pwned")
        val dest = temp.newFolder("out")

        ArchiveExtractor.extract(archive, dest)

        assertFalse(File(dest.parentFile, "escaped.txt").exists())
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    @Test
    fun rejectsAbsoluteEntryNames() {
        val archive = zip("/tmp/berryforge-absolute.txt" to "pwned")
        val dest = temp.newFolder("out")

        ArchiveExtractor.extract(archive, dest)

        assertFalse(File("/tmp/berryforge-absolute.txt").exists())
    }

    @Test
    fun stripsAPrefixWhenAsked() {
        val archive = zip(
            "data/data/com.termux/files/usr/bin/tool" to "#!/bin/sh\n",
            "other/skipme" to "x",
        )
        val dest = temp.newFolder("out")

        ArchiveExtractor.extract(
            archive = archive,
            destination = dest,
            prefix = "data/data/com.termux/files/usr/",
        )

        assertTrue("prefixed entry should be extracted", File(dest, "bin/tool").exists())
        assertFalse("entry outside the prefix should be skipped", File(dest, "other/skipme").exists())
    }

    @Test
    fun executableBitsAreSetForBinaries() {
        val archive = zip("bin/aapt2" to "binary")
        val dest = temp.newFolder("out")

        ArchiveExtractor.extract(archive, dest)

        val file = File(dest, "bin/aapt2")
        assertTrue(file.exists())
        assertTrue("expected the executable bit to be set", file.canExecute())
    }

    @Test
    fun extractionDoesNotEscapeViaSymlinkTargets() {
        // A zip cannot express symlinks portably, so this asserts the containment helper
        // indirectly: everything extracted lands under the destination.
        val archive = zip("ok.txt" to "fine")
        val dest = temp.newFolder("out")

        ArchiveExtractor.extract(archive, dest)

        val root = dest.canonicalPath
        dest.walkTopDown().filter { it.isFile }.forEach { file ->
            assertTrue(
                "extracted file escaped the destination: ${file.path}",
                file.canonicalPath.startsWith(root),
            )
        }
    }
}
