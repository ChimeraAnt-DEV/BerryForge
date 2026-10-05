package dev.chimeraant.berryforge.sandbox

import android.content.Context
import dev.chimeraant.berryforge.data.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * The sandbox gate.
 *
 * When sandbox mode is on, a connected agent can only touch BerryForge's own cache
 * directory: no pushes to GitHub, no APK installs, no writes outside the mirror.
 * This class is the single authority for that decision — every mutating path in the
 * MCP server and the approval flow asks it first.
 *
 * Reads are always allowed; sandbox mode restricts effects, not observation.
 */
class SandboxGuard(
    private val context: Context,
    private val settings: SettingsStore,
) {

    val sandboxMode: Flow<Boolean> = settings.sandboxMode

    private val allowedRoot: File = File(context.cacheDir, "sandbox").apply { mkdirs() }

    suspend fun isSandboxed(): Boolean = settings.sandboxMode.first()

    /** Effects an agent may request. Reads are implicitly always permitted. */
    enum class Effect {
        ReadFile,
        WriteFile,
        DeleteFile,
        Commit,
        Push,
        RunBuild,
        RunTests,
        InstallApk,
        RunShell,
    }

    data class Decision(
        val allowed: Boolean,
        val requiresApproval: Boolean,
        val reason: String? = null,
    )

    /**
     * Resolves whether [effect] may proceed right now.
     *
     * - In sandbox mode, effects that leave the device or mutate the real working tree
     *   are refused outright rather than queued for approval, so an agent cannot be
     *   talked into a destructive action.
     * - Outside sandbox mode, writes/builds/installs defer to the per-category approval
     *   settings; reads are auto-approved unless the user asked to gate them too.
     */
    suspend fun evaluate(effect: Effect): Decision {
        val sandboxed = isSandboxed()
        val approveReads = settings.approveReads.first()
        val approveWrites = settings.approveWrites.first()
        val approveBuilds = settings.approveBuilds.first()

        if (sandboxed) {
            return when (effect) {
                Effect.ReadFile -> Decision(allowed = true, requiresApproval = approveReads)
                Effect.WriteFile -> Decision(allowed = true, requiresApproval = true, reason = "Sandboxed: writing to the BerryForge cache only")
                Effect.DeleteFile -> Decision(allowed = true, requiresApproval = true, reason = "Sandboxed: deletes stay inside the cache")
                Effect.Commit -> Decision(allowed = true, requiresApproval = true, reason = "Sandboxed: commit recorded locally, never pushed")
                Effect.Push -> Decision(allowed = false, requiresApproval = false, reason = "Sandbox mode blocks pushing to GitHub")
                Effect.RunBuild -> Decision(allowed = true, requiresApproval = true, reason = "Sandboxed: build output discarded after the run")
                Effect.RunTests -> Decision(allowed = true, requiresApproval = true)
                Effect.InstallApk -> Decision(allowed = false, requiresApproval = false, reason = "Sandbox mode blocks installing APKs")
                Effect.RunShell -> Decision(allowed = true, requiresApproval = true, reason = "Sandboxed shell: cache directory only")
            }
        }

        return when (effect) {
            Effect.ReadFile -> Decision(allowed = true, requiresApproval = approveReads)
            Effect.WriteFile -> Decision(allowed = true, requiresApproval = approveWrites)
            Effect.DeleteFile -> Decision(allowed = true, requiresApproval = approveWrites)
            Effect.Commit -> Decision(allowed = true, requiresApproval = approveWrites)
            Effect.Push -> Decision(allowed = true, requiresApproval = true, reason = "Pushing changes the remote repository")
            Effect.RunBuild -> Decision(allowed = true, requiresApproval = approveBuilds)
            Effect.RunTests -> Decision(allowed = true, requiresApproval = approveBuilds)
            Effect.InstallApk -> Decision(allowed = true, requiresApproval = true, reason = "Installing replaces the app on this device")
            Effect.RunShell -> Decision(allowed = true, requiresApproval = true)
        }
    }

    /**
     * Resolves the directory an agent write should land in. In sandbox mode this is a
     * scratch area; otherwise it is the real repo mirror.
     */
    suspend fun resolveWriteTarget(realTarget: File): File =
        if (isSandboxed()) {
            File(allowedRoot, realTarget.name).also { it.parentFile?.mkdirs() }
        } else {
            realTarget
        }

    /** Guards a raw path against traversal before it reaches the filesystem. */
    fun isPathSafe(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    val sandboxRoot: File get() = allowedRoot

    fun clearSandbox() {
        runCatching { allowedRoot.deleteRecursively(); allowedRoot.mkdirs() }
    }
}
