package dev.chimeraant.berryforge.core

import android.content.Context
import dev.chimeraant.berryforge.ai.AiReviewService
import dev.chimeraant.berryforge.build.ApkRepository
import dev.chimeraant.berryforge.build.BuildLogParser
import dev.chimeraant.berryforge.build.GradleRunner
import dev.chimeraant.berryforge.build.ToolchainInstaller
import dev.chimeraant.berryforge.mcp.McpHttpServer
import dev.chimeraant.berryforge.mcp.McpServer
import dev.chimeraant.berryforge.mcp.TunnelManager
import dev.chimeraant.berryforge.session.SessionRecorder
import dev.chimeraant.berryforge.terminal.ShellEnvironment
import dev.chimeraant.berryforge.data.editor.EditorWorkspace
import dev.chimeraant.berryforge.data.github.CommitFlow
import dev.chimeraant.berryforge.data.github.GitHubApi
import dev.chimeraant.berryforge.data.github.GitHubAuth
import dev.chimeraant.berryforge.data.github.RepoCache
import dev.chimeraant.berryforge.data.settings.SecureStore
import dev.chimeraant.berryforge.data.settings.SettingsStore
import dev.chimeraant.berryforge.sandbox.SandboxGuard
import kotlinx.coroutines.flow.first

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

    val toolchain: ToolchainInstaller by lazy { ToolchainInstaller(context) }
    val gradle: GradleRunner by lazy { GradleRunner(context, toolchain) }
    val buildLogs: BuildLogParser by lazy { BuildLogParser() }
    val apkRepository: ApkRepository by lazy { ApkRepository(context) }

    val shellEnv: ShellEnvironment by lazy { ShellEnvironment(context, toolchain) }
    val sessions: SessionRecorder by lazy { SessionRecorder(context, workspace) }

    val mcpServer: McpServer by lazy {
        McpServer(
            context = context,
            api = api,
            cache = repoCache,
            workspace = workspace,
            gradle = gradle,
            buildLogs = buildLogs,
            settings = settings,
            secure = secure,
            sandbox = sandbox,
            sessions = sessions,
        )
    }

    val tunnels: TunnelManager by lazy { TunnelManager(context, settings, secure) }

    /**
     * The loopback HTTP server hosting the MCP endpoint.
     *
     * Created eagerly with the container but not bound until [McpHttpServer.start] is
     * called, so no socket is open until the user turns the server on.
     */
    val mcpHttpServer: McpHttpServer by lazy { McpHttpServer(mcpServer) }
}
