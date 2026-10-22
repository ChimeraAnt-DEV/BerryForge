# BerryForge — working notes

Android IDE (`dev.chimeraant.berryforge`). Jetpack Compose UI, Sora Editor for text,
Termux terminal-emulator/view for the PTY, okhttp + kotlinx.serialization for GitHub,
commons-compress for toolchain archives.

## Build and verify

```bash
./gradlew :app:testDebugUnitTest   # 155 unit tests — must stay green
./gradlew :app:lintDebug           # lint must report 0 errors
./gradlew :app:assembleRelease     # R8: verifies the ProGuard keeps actually hold
```

`assembleRelease` is the cheapest real crash check available without a device: it runs R8
over everything. Run it when touching serialization models, reflection, or JNI-facing code.

## Constraints that are load-bearing

**Android 10+ W^X blocks exec of app-storage binaries.** BerryForge targets SDK 35, so a
JDK unpacked into `filesDir` cannot be `execve`'d no matter its file mode — the install
succeeds and every `java` call fails with EACCES. `ToolchainInstaller.execBlockedReason()`
probes `java -version` so this surfaces as `ToolchainState.ExecBlocked` instead of a
misleading "Ready". The real fix is packaging the JDK as `jniLibs` inside the APK (Termux
does this, plus `targetSdk 28` + termux-exec); that is a large, deliberate change.

**The commit flow reads from disk, not from the editor buffer.** `CommitFlow` and
`dirtyPaths` call back into `EditorWorkspace` on disk. Anything that stages a commit must
flush the editor first, and must open the commit sheet only after that write returns —
opening it concurrently lets the sheet read a stale tree.

**Mecha must stay deterministic.** `MechaBrain.classify` is pure keyword/regex matching
with no model in the hot path; an unknown query *optionally* falls back to the configured
LLM. `MechaBrainTest` pins the whole pipeline at <50 ms per command on a 4k-line file, so
any change that adds I/O, a model call, or a re-parse to classification will fail the build.

**Crash guard conventions.** Background work goes through `CoroutineScope.launchSafely`
(see `core/SafeLaunch.kt`) — an exception out of a plain `launch` on `viewModelScope` or
`rememberCoroutineScope()` reaches the default handler and kills the process. Reads a
screen performs on first composition use the `setting { }` helper so a corrupt DataStore or
an invalidated Keystore entry degrades to a default instead of a dead screen. A
`LaunchedEffect` that can throw is a crash on screen open.

**`BerrySheet` needs a bounded height.** Its `heightIn(max = ...)` is load-bearing: a
weighted `LazyColumn` inside an unbounded constraint throws
"measured with infinity maximum height". Do not relax it.

**Fullscreen is re-applied from several lifecycle points.** Android re-shows the system
bars on IME open, dialogs, resume and focus changes. Hiding them once in `onCreate` gave
"fullscreen until the first keyboard". `MainActivity` re-hides from `onResume`,
`onWindowFocusChanged`, and an insets listener that watches IME visibility transitions
(the keyboard does not move window focus, so focus alone is not enough).

**Motion must not cost frames.** The animated background runs off one throttled clock
(`BACKGROUND_FRAME_MS`) read in the draw phase, not from `rememberInfiniteTransition` at
display refresh rate. Repeated identical animations (skeleton shimmer rows, per-message
Mecha heads) are hoisted to one shared transition or made static — see
`rememberShimmerProgress()` and `MechaHead(animateBlink = false)`.

## Conventions

- Comments explain *why*, never *what*. Several existing ones carry a real bug history;
  keep that context when editing nearby code.
- Imports: project imports first, then `kotlinx.*`, alphabetised within each group.
- UI tokens live in `ui/design/` (`BerryColors`, `BerryType`, `BerrySpacing`,
  `BerryRadius`, `BerryMotion`). Use them rather than raw values.
- Rounding: `BerryRadius` (`sm` 6 → `xxl` 24, `pill` 999). Keep new surfaces rounded and
  consistent with their neighbours.
