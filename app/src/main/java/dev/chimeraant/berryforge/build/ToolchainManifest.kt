package dev.chimeraant.berryforge.build

/**
 * The on-device toolchain, pinned to exact upstream artefacts.
 *
 * GENERATED from the Termux Packages index — do not hand-edit the hashes. Each entry
 * records the exact filename, byte size and SHA-256 published by the repository, so
 * [ToolchainInstaller] can verify every download rather than trusting it.
 *
 * Why Termux packages rather than Google's SDK zips:
 * Google ships build-tools and platform-tools as Linux **x86_64** binaries, which
 * cannot execute on an ARM Android device. Termux packages are built against Android's
 * own `/system/bin/linker64` for aarch64 and run in an app's private storage.
 *
 * The JDK is not a single archive: `openjdk-21` depends on native libraries
 * (libandroid-spawn, libiconv, zlib and others) that the JVM loads at runtime, and on
 * `ca-certificates-java` for its trust store. The dependency closure is resolved here at
 * build time rather than at install time, so the installer never has to run a package
 * manager on the device.
 *
 * ## On version pinning
 *
 * These URLs point at specific published files, so they cannot break silently — but they
 * also will not pick up security updates. When Termux rotates a package the filename
 * changes and the download 404s with a clear error, which is the intended failure mode:
 * loud, not silent. Regenerate this file to move to newer versions.
 */
internal object ToolchainManifest {

    /** Base URL for every package below. */
    const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main/"

    /**
     * The canonical package index for aarch64.
     *
     * The pinned URLs above are generated from this index. Reading it at install time is
     * the fallback when a pinned version has been rotated away: it lists the current
     * `Filename`, `Size` and `SHA256` for every package, so the download can be repaired
     * against the same upstream data the pins came from.
     */
    const val INDEX_URL = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages"

    /** Package layout used by Termux debs; stripped during extraction. */
    const val TERMUX_DATA_PREFIX = "data/data/com.termux/files/usr/"

    /**
     * JDK 21, its native runtime dependencies, and the CA trust store.
     *
     * Audio packages are trimmed, not dropped: `alsa-lib` (which provides the
     * `libasound.so` the JVM's sound library probes) is kept, while the server pieces
     * (`alsa-plugins`, `pulseaudio` and its dbus/libc++/webrtc closure) are dropped —
     * a headless IDE never routes audio, and they would add tens of megabytes for
     * nothing. X11 packages remain dropped entirely.
     */
    val JDK_PACKAGES: List<TermuxPackage> = listOf(
        TermuxPackage("abseil-cpp", "pool/main/a/abseil-cpp/abseil-cpp_20260817.0_aarch64.deb", 1214712L,
            "b698963bb7096494ad928ce89cd869a53e6adf0b05f5f407b40fda9c85c6e2b0"),
        TermuxPackage("alsa-lib", "pool/main/a/alsa-lib/alsa-lib_1.2.16.1_aarch64.deb", 453056L,
            "34d850de4cf6a832b638a125ac2b029a46697e7a44c66f88fb388486c93314a5"),
        TermuxPackage("ca-certificates", "pool/main/c/ca-certificates/ca-certificates_1:2026.10.20-1_all.deb", 110132L,
            "ab8a7fe63293b455c02399ee125eb8a0041e430ccfdf89a5be8317522fdf9107"),
        TermuxPackage("ca-certificates-java", "pool/main/c/ca-certificates-java/ca-certificates-java_1:2026.10.20-1_all.deb", 95020L,
            "3ce94c49c9d77fbb4db097e434b4117b267ad48ee5ede512e69eeadaf47ac6f4"),
        TermuxPackage("libandroid-execinfo", "pool/main/liba/libandroid-execinfo/libandroid-execinfo_0.1-3_aarch64.deb", 14964L,
            "725dd2c6da7fc96e860fcc928e18aa9fb0e85e0bdcaf4f8ce0f0654ca6860fc2"),
        TermuxPackage("libandroid-glob", "pool/main/liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb", 7032L,
            "2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826"),
        TermuxPackage("libandroid-shmem", "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", 7216L,
            "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"),
        TermuxPackage("libandroid-spawn", "pool/main/liba/libandroid-spawn/libandroid-spawn_0.3_aarch64.deb", 15216L,
            "7988fa788ef48ab5da9660443905a2e4099ac36221739d72f5c39acc644b4d1c"),
        TermuxPackage("libandroid-sysv-semaphore", "pool/main/liba/libandroid-sysv-semaphore/libandroid-sysv-semaphore_0.1-1_aarch64.deb", 3568L,
            "c3369a92814690d56278d0455ae6df7bf2ad7678ae9543e86d1e3aa433937c69"),
        TermuxPackage("libandroid-stub", "pool/main/liba/libandroid-stub/libandroid-stub_30-2_aarch64.deb", 26788L,
            "74087792a86e3f90772627f2217797e422e5d5fc47f24ad825fc3bbb539a667a"),
        TermuxPackage("libandroid-support", "pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb", 10880L,
            "f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517"),
        TermuxPackage("libc++", "pool/main/libc/libc++/libc++_30_aarch64.deb", 341604L,
            "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"),
        TermuxPackage("libexpat", "pool/main/libe/libexpat/libexpat_2.9.0_aarch64.deb", 100256L,
            "f8a104e8227c9c181f6818658a3ee2fa4c107fac94219b068144fc6591086cdb"),
        TermuxPackage("libiconv", "pool/main/libi/libiconv/libiconv_1.19_aarch64.deb", 562724L,
            "fe9481b1dc101c6c3552943f25435109fd522aecc615ae49594f9fbee863bb37"),
        TermuxPackage("libjpeg-turbo", "pool/main/libj/libjpeg-turbo/libjpeg-turbo_3.2.0_aarch64.deb", 453600L,
            "e6339075bcde28b4902d5a51f4063164834abfd2fa115e881ae637a34585e54f"),
        TermuxPackage("littlecms", "pool/main/l/littlecms/littlecms_2.19.1_aarch64.deb", 143396L,
            "274f732a186563beca204f5d016d30cac8e5c24f7c4395b60881f9613eddf1a1"),
        TermuxPackage("openjdk-21", "pool/main/o/openjdk-21/openjdk-21_21.0.13_aarch64.deb", 106105996L,
            "5e5bff2252236b30339a68739834ec98baf858f19905f9fae87198bf5b117cb2"),
        TermuxPackage("zlib", "pool/main/z/zlib/zlib_1.3.2_aarch64.deb", 62840L,
            "75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"),
    )

    /** adb and its dependencies, for installing and debugging built APKs. */
    val ADB_PACKAGES: List<TermuxPackage> = listOf(
        TermuxPackage("abseil-cpp", "pool/main/a/abseil-cpp/abseil-cpp_20260817.0_aarch64.deb", 1214712L,
            "b698963bb7096494ad928ce89cd869a53e6adf0b05f5f407b40fda9c85c6e2b0"),
        TermuxPackage("android-tools", "pool/main/a/android-tools/android-tools_37.0.0p1-3_aarch64.deb", 2374720L,
            "534e90d338bd432c06677cb402d0e9ed98a82a74de4bf5f90e643f15d8fc4274"),
        TermuxPackage("brotli", "pool/main/b/brotli/brotli_1.2.1_aarch64.deb", 344000L,
            "4fba29438b2b44f8470d52173b4548e938542dc2fc632f8699be5a284cbb9485"),
        TermuxPackage("fmt", "pool/main/f/fmt/fmt_1:12.2.0_aarch64.deb", 183656L,
            "4a2cb43ab167b32daf0d7978f21c5391d17bce47dae6aa2a8b3a8f958e663ce7"),
        TermuxPackage("libc++", "pool/main/libc/libc++/libc++_30_aarch64.deb", 341604L,
            "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"),
        TermuxPackage("liblz4", "pool/main/libl/liblz4/liblz4_1.10.0-1_aarch64.deb", 84676L,
            "09b9449418d5c2dc4f5c1c140ba8138d56be3e9ae5fd3be3318825ec9f8a0499"),
        TermuxPackage("liblzma", "pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb", 194796L,
            "44e95e6e60dddb3705e60a344a4f009a1085a210797342b721eaff41b44033c0"),
        TermuxPackage("libprotobuf", "pool/main/libp/libprotobuf/libprotobuf_2:36.2-1_aarch64.deb", 1433000L,
            "daa3b9169e094644ba95c35d40a5cc283473144decc7dae8396c49cc51440815"),
        TermuxPackage("pcre2", "pool/main/p/pcre2/pcre2_10.49_aarch64.deb", 1001320L,
            "c27995e5f52b8ecc9b8b3e662eb08e49f82a18bfd3f34343936acc7b23964724"),
        TermuxPackage("zlib", "pool/main/z/zlib/zlib_1.3.2_aarch64.deb", 62840L,
            "75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"),
        TermuxPackage("zstd", "pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb", 360488L,
            "e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3"),
    )
}

/** One pinned Termux package. */
internal data class TermuxPackage(
    val name: String,
    val filename: String,
    val size: Long,
    val sha256: String,
) {
    val url: String get() = ToolchainManifest.TERMUX_REPO + filename
}
