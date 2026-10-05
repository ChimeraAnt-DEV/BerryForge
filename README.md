# BerryForge

An Android IDE that builds, reviews and ships — on the device.

BerryForge is a standalone application. It is not a fork of AndroidIDE and not a
wrapper around Termux: it bundles Termux's terminal-emulator library as a
component, and nothing else. Everything else is new code.

- **Package:** `dev.chimeraant.berryforge`
- **Min SDK:** 26 · **Target SDK:** 35
- **Kotlin + Jetpack Compose only.** No XML layouts, no Material Design defaults,
  no Material icons.

## What it does

| Phase | Capability |
| --- | --- |
| 1 | GitHub device-flow sign-in, repo browser, code editor with TextMate highlighting, AI code review, commit and PR flow |
| 2 | On-device JDK and Android SDK installer, Gradle runner with live output, searchable build log, APK install and share |
| 3 | Embedded MCP server exposing eight tools, on a public HTTPS endpoint via Cloudflare, ngrok or a custom relay |
| 4 | Live agent session viewer, approval gates, sandbox mode, session history with revert |
| 5 | PTY-backed terminal, profile screen, owner badge |

## Design

Dark-first, on a deep navy base (`#0A0E1A`). A single accent shifts by
interaction state rather than being a fixed brand colour:

- **Blue** — editing, navigation, primary actions
- **Purple** — everything AI: review, suggestions, agent activity
- **Gold** — owner affordances only, and nothing else

Inter for UI, JetBrains Mono for code and terminal. The component library in
`ui/components` and the 75-vector icon set in `ui/design/BerryIcons.kt` are
purpose-built; no Material icon is referenced anywhere in the project. Every
interaction animates on a shared motion scale.

## Building

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

Requires JDK 17+ to build and Android SDK platform 35 with build-tools 35.0.0.

## Architecture

```
core/        manual DI container, shared OkHttp clients
data/
  github/    device-flow auth, REST client, two-tier repo cache, commit flow
  editor/    on-device repo mirror, base-sha tracking, dirty state
  diff/      line diff used by both the commit sheet and session revert
  settings/  encrypted credential store + non-secret preferences
editor/      Sora Editor bootstrap, BerryForge TextMate theme, Compose wrapper
ai/          OpenAI-compatible review and commit-message generation
build/       toolchain installer, archive extraction, Gradle runner, log parser
mcp/         MCP protocol, HTTP/SSE transport, eight tools, three tunnel modes
sandbox/     the single authority on what a connected agent may do
session/     agent activity feed, approval gates, change capture and revert
terminal/    shell environment, shims, PTY host
ui/          design system, component library, screens
```

### Notes on the parts that are easy to get wrong

**The user's API key never leaves the device.** It is stored in
`EncryptedSharedPreferences` under a Keystore-backed master key, read at call
time, and placed only in the `Authorization` header of a request to the endpoint
the user themselves configured. It is never logged, cached or sent anywhere else.
The preferences file is excluded from cloud backup.

**`write_file` does not push.** The MCP tool stages into the local mirror so the
user can review the diff. Publishing goes through `commit`, and both the UI and
the MCP tool call the same `CommitFlow`, so the two paths cannot diverge.

**Sandbox mode refuses rather than defers.** Pushes and APK installs are blocked
outright when sandbox mode is on, not queued for approval, so an agent cannot be
argued into a destructive action. `SandboxGuard` is the only thing that decides.

**Archive extraction is sanitised.** `ArchiveExtractor` rejects absolute paths,
`..` segments and out-of-tree symlinks, so a malicious toolchain archive cannot
escape its directory.

**The owner badge is cosmetic.** It is a client-side check against
`GET /user/orgs`, cached for a day. Nothing treats it as authorisation, and the
UI says so.

## Known limitations

- **The tunnel is not verified end to end.** It cannot be exercised in a headless
  build environment. See [docs/PHASE3_TUNNEL_NOTES.md](docs/PHASE3_TUNNEL_NOTES.md)
  for what is implemented, what is untested, and how to verify it on a device.
- **The contribution graph is approximate.** GitHub exposes no public calendar
  endpoint, so the profile strip is derived from recent public push events. The
  UI labels it as such.
- **`git` and `curl` in the terminal are shims**, not real binaries. Android ships
  neither, and bundling a full Git for every ABI would dominate the APK.
- **The MCP client id in `GitHubAuth` is a public device-flow client id.** Replace
  it with your own registered OAuth App id to sign in against a different client.

## Manual test checklists

Each phase's commit message carries a device test checklist for that phase's
surfaces. They are also the fastest way to validate a change to any one area.
