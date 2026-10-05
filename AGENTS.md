# AGENTS.md — battery_charge_limiter

Guide for AI agents and developers working on **Battery Charge Limiter (BCL)**, a rooted Android app
that stops charging at a configured level by writing a kernel control file.

Repo: `/Users/prady/Projects/battery_charge_limiter` — the repo is the source of truth for the
implementation; the Obsidian notes below are the source of truth for status, decisions and evidence.

## 1. Obsidian notes (required)

The project's documentation lives in the Obsidian vault at
`/Users/prady/Vault/Projects/battery_charge_limiter/` and follows the vault rules in
`/Users/prady/Vault/Projects/AGENTS.md`.

After any code change:

1. Open the project's `Project Map.md` and follow the affected topic note.
2. Add dated evidence (commands, outputs, device, app version) to the owning topic note. Never mark
   work complete based on compilation alone.
3. Record decisions in `Decisions and Open Questions.md` (date, status, problem, options, choice,
   rationale, related note) and log open questions instead of guessing.
4. If you add a note, link it from `Project Map.md` using scoped links
   `[[Projects/battery_charge_limiter/Note Name|Note Name]]` and keep the map sorted.
5. Keep earlier phases readable; do not rewrite history.

Key notes: `Project Map.md` (navigation), `Code Repository.md` (landmarks and validation),
`Architecture.md` (runtime model), `Charge Limit Enforcement.md` (the A7 Lite incident and evidence),
`Engineering Workflow.md` (build/verify procedure), `Decisions and Open Questions.md` (decision log),
`References and Research.md` (issue #1 and external links).

## 2. Build and verify

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

JDK 21 is required: the Android Studio JBR (Java 25) fails with
`Unsupported class file major version 69`. `local.properties` must point at
`/Users/prady/Library/Android/sdk`. On-device verification commands are in the
`Engineering Workflow` note; always run `dumpsys battery reset` after simulations.

## 3. Architecture rules (learned the hard way)

- **Control-file writes:** never join the `mount`/`chmod` preparation with the `echo` using `&&` —
  the mount fails on sysfs kernels and the write is skipped silently. Separate with `;`, verify by
  reading the file back, and log failures. See `Utils.writeCtrlFile()`.
- **Start on charging** must not rely on manifest power broadcasts: they are blocked on Android 13
  GSIs ("Background execution not allowed"). `ChargeStartJobService` (persisted,
  `requiresCharging`) is the reliable path; it is armed at boot and when the service stops on unplug.
- **Honest notification state:** only report "maintaining" after the control-file write succeeded.
- **Samsung MediaTek selection:** `batt_slate_mode` does not gate charging and is marked
  `issues: true` so `input_suspend` is selected; do not reorder that back.
- **Two settings stores:** `Settings` (`Utils.getSettings`) for limit/control-file state; default
  prefs (`Utils.getPrefs`) for app behaviour.

## 4. Conventions

- Kotlin; compile/target SDK 36/35; keep existing style and minimal diffs.
- The release build reuses `app/dev_keystore.jks` so the APK stays installable over the current one.
- Version bumps: update `versionCode`/`versionName` and add
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- Commit messages: short imperative subject; body explains why and the evidence. Never add
  attribution footers ("Generated with …", "Co-Authored-By …", bot emoji) — owner's standing rule.
