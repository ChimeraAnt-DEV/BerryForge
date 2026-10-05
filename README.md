<p align="center">
  <img src="https://raw.githubusercontent.com/ChimeraAnt-DEV/BerryForge-ICON/main/berryforge-icon-512.png" width="150" height="150" alt="BerryForge">
</p>



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

These are stated plainly because several of them are not fixable in code and the
earlier version of this file implied otherwise.

### On-device builds cannot execute binaries unpacked into app storage

Android 10 (API 29) and later enforce W^X: the platform refuses to `execve()` a file
that lives in a writable app-private directory, regardless of the file's mode bits.

That is exactly what the toolchain installer does — it unpacks a JDK and Android
build-tools into `filesDir` and then tries to run them. Termux and AndroidIDE work
around this by targeting SDK 28. BerryForge targets SDK 35, so the restriction
applies, and **an on-device Gradle build cannot work by unpacking and executing
binaries this way.**

What is fixed and what is not:

- The download, checksum, extraction and layout problems are fixed. The installer
  fetches aarch64 artefacts, installs the JDK's native dependencies and trust store,
  and produces a correct tree.
- Every **Termux package** is SHA-256 verified against the hash published in the
  repository index. The **build-tools tarball and the platform zip are not
  checksum-verified** — neither publisher offers a stable digest for those URLs, and
  inventing one would be worse than saying so. A failed or partial download is still
  rejected, because the extraction is checked for the specific files it must produce.
- Invoking `gradlew` as `sh gradlew` rather than `./gradlew` fixes the shebang
  lookup, but does **not** lift W^X.
- The workaround that does work is shipping the executables inside the APK as
  `jniLibs`, which the platform treats as read-only and executable, instead of
  extracting them at runtime. That is a packaging change and is not done here.

The editor, AI review, GitHub flows, MCP server, sandbox and terminal all work
without the toolchain. Only `run_build`, `run_tests` and on-device compilation are
affected.

### The tunnel is not verified end to end

It cannot be exercised in a headless build environment. See
[docs/PHASE3_TUNNEL_NOTES.md](docs/PHASE3_TUNNEL_NOTES.md) for what is implemented,
what is untested, and how to verify it on a device.

### Toolchain packages are version-pinned

`ToolchainManifest` pins exact Termux filenames, sizes and SHA-256 hashes. They
cannot break silently, but they also will not pick up security updates. When Termux
rotates a package the filename changes and the download fails with a clear 404,
which is the intended failure mode. Regenerating the manifest is the upgrade path.

### No `git` or `curl` in the terminal

Android ships neither, and the shims that previously pretended to provide them could
not work: they called an endpoint that was never implemented, invoked `python3`
(which Android lacks) and read an environment variable nothing set. They have been
removed, and the shell banner lists what is actually available. `gradle` is still
shimmed because it only needs `gradlew`, which exists.

### Other

- **The contribution graph is approximate.** GitHub exposes no public calendar
  endpoint, so the profile strip is derived from recent public push events. The UI
  labels it as such.
- **The MCP client id in `GitHubAuth` is a public device-flow client id.** Replace it
  with your own registered OAuth App id to sign in against a different client.
- **The diff engine degrades above ~4M line-pairs.** Rather than allocating an
  enormous LCS table on a phone it falls back to a prefix comparison, so a very large
  file reports an approximate diff.
- **Approval prompts auto-deny after five minutes.** That is deliberate — an
  unattended device should not be drivable — but it means a long-running agent needs
  the user present.

## Tests

```bash
./gradlew testDebugUnitTest
```

47 unit tests across the build log parser, the diff engine, MCP argument validation
and archive extraction. They are pure JVM, so they run without a device, and CI runs
them plus lint on every push and pull request.

## Manual test checklists

Each phase's commit message carries a device test checklist for that phase's
surfaces. They are also the fastest way to validate a change to any one area.
