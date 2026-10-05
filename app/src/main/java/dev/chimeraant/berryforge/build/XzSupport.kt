package dev.chimeraant.berryforge.build

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream

/**
 * XZ decompression for Termux's `.tar.xz` JDK builds.
 *
 * The JDK tarball is tens of megabytes and must not be fully buffered in memory on a
 * phone, so the stream is handed straight to the tar reader.
 */
object XzSupport {
    fun decompress(archive: File): InputStream = XZInputStream(archive.inputStream().buffered(64 * 1024))
}
