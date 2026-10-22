package dev.chimeraant.berryforge.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of [ToolchainState].
 *
 * The shipped bug this guards: a toolchain that had fully unpacked but could not be
 * executed (Android 10+ W^X blocks `execve` on files under the app's data directory)
 * reported [ToolchainState.Ready]. The wizard therefore said "Toolchain ready" and
 * enabled the build button, and the only symptom was a `Permission denied` buried in the
 * Gradle log. [ToolchainState.ExecBlocked] exists so that state is distinguishable from a
 * genuinely working install, and the two must never be conflated.
 */
class ToolchainStateTest {

    @Test
    fun `exec blocked carries the reason and is not ready`() {
        val blocked = ToolchainState.ExecBlocked("W^X blocks execve")
        assertTrue("ExecBlocked must report a message", blocked.message.isNotBlank())
        assertNotEquals(
            "ExecBlocked must not be reported as Ready",
            ToolchainState.Ready(java.io.File("jdk"), java.io.File("sdk")),
            blocked as ToolchainState,
        )
    }

    @Test
    fun `every terminal state is distinguishable`() {
        // The wizard branches on these; two states comparing equal would silently pick the
        // wrong copy and the wrong accent colour.
        val states: List<ToolchainState> = listOf(
            ToolchainState.NotInstalled,
            ToolchainState.Failed("jdk", "boom"),
            ToolchainState.ExecBlocked("blocked"),
            ToolchainState.Ready(java.io.File("jdk"), java.io.File("sdk")),
            ToolchainState.Extracting("jdk", 3),
        )
        assertEquals("states must all be distinct", states.size, states.distinct().size)
    }

    @Test
    fun `progress states carry the component and a bounded fraction`() {
        val downloading = ToolchainState.Downloading("jdk", "openjdk-21", 5, 10, 0.5f)
        assertEquals("jdk", downloading.component)
        assertTrue(downloading.percent in 0f..1f)
    }
}
