package dev.chimeraant.berryforge.core

import android.content.Context
import dev.chimeraant.berryforge.ai.AiReviewService
import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.CommitFlow
import dev.chimeraant.berryforge.data.github.GitHubApi
import dev.chimeraant.berryforge.data.github.GitHubAuth
import dev.chimeraant.berryforge.data.github.RepoCache
import dev.chimeraant.berryforge.data.settings.SecureStore
import dev.chimeraant.berryforge.data.settings.SettingsStore
import dev.chimeraant.berryforge.sandbox.SandboxGuard

/**
 * Hand-rolled dependency container. A DI framework would cost cold-start time for no
 * benefit at this size; everything here is either lazy or cheap to construct.
 */
class AppContainer(private val context: Context) {

    /** Exposed so UI-layer helpers (install/share intents) can reach a Context. */
    val appContext: Context get() = context

    val settings: SettingsStore by lazy { SettingsStore(context) }
    val secure: SecureStore by lazy { SecureStore(context) }

    val auth: GitHubAuth by lazy { GitHubAuth(secure) }
    val api: GitHubApi by lazy { GitHubApi(auth) }
    val repoCache: RepoCache by lazy { RepoCache(context) }
    val workspace: EditorWorkspace by lazy { EditorWorkspace(context) }
    val sandbox: SandboxGuard by lazy { SandboxGuard(context, settings) }
    val commitFlow: CommitFlow by lazy { CommitFlow(api, workspace, sandbox) }

    val aiReview: AiReviewService by lazy { AiReviewService(settings, secure) }
}
