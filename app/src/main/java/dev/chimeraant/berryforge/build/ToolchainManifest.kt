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
 * (libandroid-spawn, libiconv, zlib and others) that the JVM loads at runtime. The
 * dependency closure is resolved here at build time rather than at install time, so the
 * installer never has to run a package manager on the device.
 */
internal object ToolchainManifest {

    /** Base URL for every package below. */
    const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main/"

    /** Package layout used by Termux debs; stripped during extraction. */
    const val TERMUX_DATA_PREFIX = "data/data/com.termux/files/usr/"

    /**
     * JDK 21 plus its native runtime dependencies.
     * Audio and X11 packages in the closure are dropped: a headless build never
     * loads them, and they would add tens of megabytes for nothing.
     */
    val JDK_PACKAGES: List<TermuxPackage> = listOf(
        TermuxPackage("abseil-cpp", "pool/main/a/abseil-cpp/abseil-cpp_20260526.0_aarch64.deb", 1197740L,
            "e489fac652cddc39d9436141e627285f1034a545a06fbb19c420514a419ad877"),
        TermuxPackage("libandroid-execinfo", "pool/main/liba/libandroid-execinfo/libandroid-execinfo_0.1-3_aarch64.deb", 14964L,
            "725dd2c6da7fc96e860fcc928e18aa9fb0e85e0bdcaf4f8ce0f0654ca6860fc2"),
        TermuxPackage("libandroid-glob", "pool/main/liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb", 7032L,
            "2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826"),
        TermuxPackage("libandroid-shmem", "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", 7216L,
            "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"),
        TermuxPackage("libandroid-spawn", "pool/main/liba/libandroid-spawn/libandroid-spawn_0.3_aarch64.deb", 15216L,
            "7988fa788ef48ab5da9660443905a2e4099ac36221739d72f5c39acc644b4d1c"),
        TermuxPackage("libandroid-stub", "pool/main/liba/libandroid-stub/libandroid-stub_30-1_aarch64.deb", 26644L,
            "5b392496146309b8454780821591dccb7e6a7050f8789f3bc97b4bb6c2b6bc67"),
        TermuxPackage("libandroid-support", "pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb", 10880L,
            "f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517"),
        TermuxPackage("libc++", "pool/main/libc/libc++/libc++_30_aarch64.deb", 341604L,
            "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"),
        TermuxPackage("libexpat", "pool/main/libe/libexpat/libexpat_2.8.5_aarch64.deb", 98900L,
            "3b2c9b061fa890b69b88d2f5dd993efba902d678fe8e1990268892d1faf15e5e"),
        TermuxPackage("libiconv", "pool/main/libi/libiconv/libiconv_1.19_aarch64.deb", 562724L,
            "fe9481b1dc101c6c3552943f25435109fd522aecc615ae49594f9fbee863bb37"),
        TermuxPackage("openjdk-21", "pool/main/o/openjdk-21/openjdk-21_21.0.12_aarch64.deb", 106132164L,
            "05b08dd961c30928992f87632539c3704bf082e2984aee11441c6127f9fd7884"),
        TermuxPackage("xorg-util-macros", "pool/main/x/xorg-util-macros/xorg-util-macros_1.20.2_all.deb", 21948L,
            "11765ad1a6f181e36f807464f7b4360593d847dce245202a393eb2ca156520d0"),
        TermuxPackage("xorgproto", "pool/main/x/xorgproto/xorgproto_2026.1_all.deb", 249848L,
            "2463651143a386eba0d7baebac87ca5d2db5239d36cf38f9b2086f3c1bfdcbce"),
        TermuxPackage("zlib", "pool/main/z/zlib/zlib_1.3.2_aarch64.deb", 62840L,
            "75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"),
    )

    /** adb and its dependencies, for installing and debugging built APKs. */
    val ADB_PACKAGES: List<TermuxPackage> = listOf(
        TermuxPackage("abseil-cpp", "pool/main/a/abseil-cpp/abseil-cpp_20260526.0_aarch64.deb", 1197740L,
            "e489fac652cddc39d9436141e627285f1034a545a06fbb19c420514a419ad877"),
        TermuxPackage("android-tools", "pool/main/a/android-tools/android-tools_37.0.0-2_aarch64.deb", 2373532L,
            "6bcbded86e6e310f05045aa4cf2b1e1dc8c7e497f27e970e84f5c6eb4f80e169"),
        TermuxPackage("brotli", "pool/main/b/brotli/brotli_1.2.0_aarch64.deb", 340592L,
            "db1502601d40fb44e6085ad8bfd9311a8b472e98db831ceec9d404c5708bb52c"),
        TermuxPackage("fmt", "pool/main/f/fmt/fmt_1:11.2.0-1_aarch64.deb", 168824L,
            "dad595afcb3b1096d725c6772c0c5531764caa5841affba3c533c6298a32ab08"),
        TermuxPackage("libc++", "pool/main/libc/libc++/libc++_30_aarch64.deb", 341604L,
            "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"),
        TermuxPackage("liblz4", "pool/main/libl/liblz4/liblz4_1.10.0-1_aarch64.deb", 84676L,
            "09b9449418d5c2dc4f5c1c140ba8138d56be3e9ae5fd3be3318825ec9f8a0499"),
        TermuxPackage("liblzma", "pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb", 194796L,
            "44e95e6e60dddb3705e60a344a4f009a1085a210797342b721eaff41b44033c0"),
        TermuxPackage("libprotobuf", "pool/main/libp/libprotobuf/libprotobuf_2:35.1_aarch64.deb", 1387016L,
            "a1ba7c7f0e5903a2134662653d3e7b9ffceaa78bdd00e07ac985e2d313ebc738"),
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
