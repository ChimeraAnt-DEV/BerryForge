package dev.chimeraant.berryforge.build

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Archive extraction for the toolchain installer.
 *
 * Handles the three formats the upstream artefacts use: zip (Google SDK packages),
 * tar.gz and tar.xz (Termux JDK builds). Extraction is streamed and reports entry
 * counts so the wizard can show determinate progress rather than a spinner.
 *
 * Tar entries are sanitised before writing: absolute paths and `..` segments are
 * rejected so a malicious archive cannot escape the toolchain directory.
 */
object ArchiveExtractor {

    fun extract(archive: File, destination: File, onEntry: (Int) -> Unit = {}): Int {
        destination.mkdirs()
        val name = archive.name.lowercase()
        return when {
            name.endsWith(".zip") -> extractZip(archive, destination, onEntry)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTar(
                GZIPInputStream(BufferedInputStream(archive.inputStream())),
                destination,
                onEntry,
            )
            name.endsWith(".tar.xz") -> extractTar(
                XzSupport.decompress(archive),
                destination,
                onEntry,
            )
            else -> error("Unsupported archive format: ${archive.name}")
        }
    }

    private fun extractZip(archive: File, destination: File, onEntry: (Int) -> Unit): Int {
        var count = 0
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = safeTarget(destination, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                    markExecutable(target, entry.name)
                    count++
                    if (count % 25 == 0) onEntry(count)
                }
                zip.closeEntry()
            }
        }
        onEntry(count)
        return count
    }

    private fun extractTar(stream: java.io.InputStream, destination: File, onEntry: (Int) -> Unit): Int {
        var count = 0
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val target = safeTarget(destination, entry.name) ?: continue
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        // Preserve links inside the tree (JDK builds rely on them) but
                        // refuse any link that points outside the destination.
                        val linkTarget = entry.linkName
                        if (linkTarget != null && !linkTarget.startsWith("/") && !linkTarget.contains("..")) {
                            runCatching {
                                target.parentFile?.mkdirs()
                                java.nio.file.Files.createSymbolicLink(
                                    target.toPath(),
                                    java.nio.file.Paths.get(linkTarget),
                                )
                            }
                        }
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> tar.copyTo(out) }
                        markExecutable(target, entry.name)
                        count++
                        if (count % 25 == 0) onEntry(count)
                    }
                }
            }
        }
        onEntry(count)
        return count
    }

    /**
     * Resolves an archive entry name against the destination, rejecting anything that
     * would escape it. Returns null for entries that must be skipped.
     */
    private fun safeTarget(destination: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        if (entryName.startsWith("/") || entryName.startsWith("\\")) return null
        if (entryName.contains("..")) return null
        val target = File(destination, entryName)
        val root = destination.canonicalPath
        val resolved = target.canonicalPath
        return if (resolved == root || resolved.startsWith(root + File.separator)) target else null
    }

    private fun markExecutable(file: File, entryName: String) {
        val needsExec = entryName.startsWith("bin/") ||
            entryName.contains("/bin/") ||
            entryName.contains("/libexec/") ||
            file.name in setOf("aapt2", "d8", "zipalign", "apksigner", "adb", "aidl", "java", "javac", "gradlew")
        if (needsExec) runCatching { file.setExecutable(true, false) }
    }
}
