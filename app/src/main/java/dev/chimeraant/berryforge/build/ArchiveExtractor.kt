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
 * Handles the three formats the upstream artefacts use:
 *  - `.zip`    Google SDK packages and the Termux bootstrap
 *  - `.tar.gz` / `.tar.xz`  tarballs
 *  - `.deb`    Termux packages (an `ar` archive wrapping `data.tar.xz`)
 *
 * Extraction is streamed and reports entry counts so the wizard can show determinate
 * progress rather than a spinner.
 *
 * Every entry name is sanitised before writing: absolute paths and `..` segments are
 * rejected so a malicious archive cannot escape the destination. Symlinks are preserved
 * because the JDK relies on 200+ of them, but only when they resolve inside the
 * destination — a link pointing outside is dropped rather than followed.
 *
 * [prefix] lets a caller strip a known leading path, which is how Termux's
 * `data/data/com.termux/files/usr/` layout is flattened into the app's own prefix.
 */
object ArchiveExtractor {

    fun extract(
        archive: File,
        destination: File,
        prefix: String? = null,
        onEntry: (Int) -> Unit = {},
    ): Int {
        destination.mkdirs()
        val name = archive.name.lowercase()
        return when {
            name.endsWith(".zip") -> extractZip(archive, destination, prefix, onEntry)
            name.endsWith(".deb") -> extractDeb(archive, destination, prefix, onEntry)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTar(
                GZIPInputStream(BufferedInputStream(archive.inputStream())),
                destination,
                prefix,
                onEntry,
            )
            name.endsWith(".tar.xz") -> extractTar(
                XzSupport.decompress(archive),
                destination,
                prefix,
                onEntry,
            )
            else -> error("Unsupported archive format: ${archive.name}")
        }
    }

    /**
     * Unpacks a Debian package: `ar` archive containing `data.tar.{xz,gz}`.
     *
     * The `ar` container is parsed by hand rather than pulling in another dependency —
     * the format is a fixed 60-byte header per member, and we only need the one member.
     */
    private fun extractDeb(
        archive: File,
        destination: File,
        prefix: String?,
        onEntry: (Int) -> Unit,
    ): Int {
        archive.inputStream().use { input ->
            val magic = ByteArray(8)
            if (input.read(magic) != 8 || String(magic) != "!<arch>\n") {
                error("Not a Debian package: ${archive.name}")
            }
            while (true) {
                val header = ByteArray(60)
                var read = 0
                while (read < 60) {
                    val n = input.read(header, read, 60 - read)
                    if (n <= 0) return 0
                    read += n
                }
                val headerText = String(header, Charsets.US_ASCII)
                val memberName = headerText.substring(0, 16).trim().removeSuffix("/")
                val size = headerText.substring(48, 58).trim().toIntOrNull() ?: 0

                if (memberName.startsWith("data.tar")) {
                    // Copy the member out so it can be decompressed independently.
                    val payload = File(archive.parentFile, "${archive.name}.$memberName")
                    payload.outputStream().use { out ->
                        var remaining = size
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                    val count = payload.inputStream().use { stream ->
                        val decompressed = when {
                            memberName.endsWith(".xz") -> XzSupport.decompress(payload)
                            memberName.endsWith(".gz") -> GZIPInputStream(BufferedInputStream(stream))
                            else -> BufferedInputStream(stream)
                        }
                        extractTar(decompressed, destination, prefix, onEntry)
                    }
                    payload.delete()
                    return count
                }

                // Skip this member (padded to an even byte boundary).
                var remaining = size + (size % 2)
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (n <= 0) break
                    remaining -= n
                }
            }
        }
        return 0
    }

    private fun extractZip(
        archive: File,
        destination: File,
        prefix: String?,
        onEntry: (Int) -> Unit,
    ): Int {
        var count = 0
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = stripPrefix(entry.name, prefix) ?: continue
                val target = safeTarget(destination, name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                    markExecutable(target, name)
                    count++
                    if (count % 25 == 0) onEntry(count)
                }
                zip.closeEntry()
            }
        }
        onEntry(count)
        return count
    }

    private fun extractTar(
        stream: java.io.InputStream,
        destination: File,
        prefix: String?,
        onEntry: (Int) -> Unit,
    ): Int {
        var count = 0
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = stripPrefix(entry.name, prefix) ?: continue
                val target = safeTarget(destination, name) ?: continue
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        // Preserve links inside the tree (the JDK relies on them) but
                        // refuse any link that would escape the destination.
                        val linkTarget = entry.linkName
                        if (linkTarget != null && !linkTarget.startsWith("/") && !linkTarget.contains("..")) {
                            target.parentFile?.mkdirs()
                            runCatching {
                                if (!target.exists()) {
                                    java.nio.file.Files.createSymbolicLink(
                                        target.toPath(),
                                        java.nio.file.Paths.get(linkTarget),
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> tar.copyTo(out) }
                        markExecutable(target, name)
                        count++
                        if (count % 25 == 0) onEntry(count)
                    }
                }
            }
        }
        onEntry(count)
        return count
    }

    /** Strips a known leading path, returning null if the entry is not under it. */
    private fun stripPrefix(entryName: String, prefix: String?): String? {
        if (prefix == null) return entryName
        val normalised = entryName.removePrefix("./")
        val cleanPrefix = prefix.removePrefix("./")
        return if (normalised.startsWith(cleanPrefix)) {
            normalised.removePrefix(cleanPrefix)
        } else {
            null
        }
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
            file.name in setOf(
                "aapt2", "d8", "r8", "zipalign", "apksigner", "adb", "aidl",
                "java", "javac", "jar", "jarsigner", "keytool", "jlink", "jmod",
                "gradlew", "split-select", "dexdump",
            )
        if (needsExec) runCatching { file.setExecutable(true, false) }
    }
}

