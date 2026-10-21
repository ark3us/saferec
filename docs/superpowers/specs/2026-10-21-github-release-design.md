# SafeRec on GitHub: releases built by CI, a new package name, no Play Store

Date: 2026-10-21. Status: for the owner's approval.

## Intent

The owner no longer wants to maintain SafeRec on Google Play. The app will be distributed from GitHub instead: the owner pushes a version tag, GitHub Actions builds, signs and publishes the APK as a GitHub Release.

Nobody has the app installed from Play, so there is no migration to care about: no existing users to move, no update path to keep.

Since 2026, Android requires every app installed on a certified phone to be registered by a verified developer (Android developer verification): a package name is bound to the SHA-256 of the certificate that signs it. Enforcement started on 2026-09-30 in Brazil, Indonesia, Singapore and Thailand, and reaches the rest of the world in 2027. The owner is registered on the Android Developer Console, the console for apps distributed outside Play.

The current package name cannot be registered there. `net.ark3us.saferec` was published on Play with Play App Signing, so the only key Android has seen for it is Google's app signing key, which the owner cannot download. The console answers "net.ark3us.saferec is already in use on Android… prove ownership of an existing signing key" and lists no eligible key. The owner chose a new package name rather than keeping the Play Console account alive to add a key there.

```
  owner                          GitHub Actions (tag v1.17)                    GitHub Releases
  ─────                          ──────────────────────────                    ───────────────
  scripts/release.sh 1.17  ──►   1. tag == versionName?        ── no ──► stop
    bump version, commit,        2. unit tests, verifier tests
    tag v1.17, push              3. assembleRelease (R8)
                                 4. sign with keystore.jks (secrets of the
                                    "release" environment)
                                 5. APK certificate == registered SHA-256? ── no ──► stop
                                 6. publish  ─────────────────────────────────►  Release v1.17
                                                                                  ├─ SafeRec-1.17.apk
                                                                                  ├─ SafeRec-1.17-mapping.txt
                                                                                  └─ SHA256SUMS
```

## Goals

- **A tag is a release.** Pushing a tag `v<versionName>` produces a public GitHub Release with the signed APK, without any other manual step.
- **One command for the owner.** `scripts/release.sh 1.17` bumps the version, commits, tags and pushes.
- **A wrong release cannot be published.** The CI refuses a tag that does not match `versionName`, and an APK whose signing certificate is not the registered one.
- **Unit tests on every push and pull request**, without secrets (this is the IDEAS.md item "Unit tests on every push").
- **The new package name works end to end**: installable on a certified phone, Drive sign-in working in debug and release builds, the sessions already on Drive still visible.
- **No Play leftovers** in the README, AGENTS.md, IDEAS.md and the privacy policy.

## Non-goals

- **Telling users about updates.** The owner has not decided yet and will come back to it. GitHub Releases with an APK asset already work with update checkers such as Obtainium, at no cost.
- **Removing the power-gesture build switch.** `saferec.powerGesture=false` stays in standby (owner's decision): kept working, not published.
- **F-Droid, other stores, an AAB.**
- **Unpublishing the app from Play.** The owner does that in the Play Console if and when they want.
- **Migrating data from the old package.** The old `net.ark3us.saferec` app on the owner's test phone is just another app; it is uninstalled when the owner says so.

## Anti-goals (a failure even if it works)

- A signing secret readable by a workflow that a pull request, a branch push or a fork can trigger.
- A keystore or a password committed to the repository, printed in a CI log, or handed to a process that runs third-party code (Gradle and its plugins). The key cannot be replaced: it is bound to the Developer Console registration and to the OAuth client, so a leak is permanent.
- A published APK signed with a key other than `keystore.jks`: it would not install on certified phones in 2027 and its Drive sign-in would fail.
- A version number burned by a failed run that leaves a published Release half done (APK missing, wrong assets).

## Decisions taken with the owner

| Topic | Decision |
|---|---|
| Release trigger | Pushing a tag. GitHub builds the APK. |
| Version | `scripts/release.sh <version>`; the CI checks the tag against `versionName` in `app/build.gradle.kts` (source of truth stays in the code, so local builds know their version). |
| Signing key | Keep `keystore.jks` (alias `cert`, RSA 2048, valid to 2051). It was the Play upload key; nobody has an app signed with any other key. No new key. |
| Package name | `applicationId = "net.ark3us.saferec.app"`. The owner owns the domain `ark3us.net`. `namespace` and the Java packages stay `net.ark3us.saferec`. |
| Google Cloud project | The same project. New Android OAuth clients for the new package name; the old clients stay. |
| Power-gesture switch | Standby: CI keeps it from rotting, releases ship the full build only. |
| App name | Stays "SafeRec". A full rebrand and a new name were considered and dropped; no "2.0" jump: the next version is 1.17. |
| GitHub secrets | The builder creates the `release` environment, its tag rule and the three secrets with `gh`, reading `keystore.jks` and the passwords from `local.properties` on this machine. Values go through stdin, never on a command line, in the terminal or in a log, and the builder asks once more right before sending them. |
| `play_store_description.txt` | Deleted (it stays in git history). The README is the only description of the app. |

## Key facts

| | Value |
|---|---|
| `keystore.jks` SHA-256 (register on the Android Developer Console) | `0A:54:1D:9A:8D:FC:3C:06:EE:9E:C2:31:BD:D3:DE:30:7A:D9:32:8C:FA:82:AA:0B:A5:3A:D6:B2:47:EC:52:BC` |
| `keystore.jks` SHA-1 (new release OAuth client) | `EF:74:3F:7A:22:DE:D3:19:8D:55:70:D1:90:55:86:92:1C:C3:5E:9E` |
| Current version | `versionName = "1.16"`, `versionCode = 17` |
| Toolchain | AGP 9.1.0, Gradle 9.3.1 (wrapper), compileSdk/targetSdk 36, minSdk 26, Java 11 bytecode |

The fingerprints are public information: they can be written in the workflow and in the README.

## The parts

### 1. Package name

- `app/build.gradle.kts`: `applicationId = "net.ark3us.saferec.app"`. `namespace` unchanged, so `R`, `BuildConfig` and every class name keep `net.ark3us.saferec`.
- `app/src/main/res/xml/shortcuts.xml`: `android:targetPackage` is the application id, written by hand: becomes `net.ark3us.saferec.app`. `android:targetClass` stays `net.ark3us.saferec.QuickStartActivity` (a class name). There is no application id suffix in any build type, so one hard-coded value is right for debug, release and `r8test`.
- Code that builds components already uses `getPackageName()` for the package part and a class name for the rest (`CameraGesture`, `GuardService`, the `FileProvider` authority `getPackageName() + ".fileprovider"`): it follows the new id with no change. The manifest's `taskAffinity="net.ark3us.saferec.quickstart"` is a free string and can stay.
- `scripts/device-tests.sh`: `PKG=net.ark3us.saferec.app`. Its `ACTIVITY="$PKG/.MainActivity"` must become the full class name (`$PKG/net.ark3us.saferec.MainActivity`), since `.MainActivity` is resolved against the application id, which no longer equals the namespace. Check the script for any other `$PKG/.` form and for `run-as`/path uses.
- `CameraGestureTest` passes `"net.ark3us.saferec"` as an own package name only as test data: leave it or update it, the builder's call.
- AGENTS.md paths such as `/data/data/net.ark3us.saferec/files/` become the new id.
- **To verify on the phone** (not assumed): with the same Cloud project, the new app sees the `SafeRec` folder and the sessions the old package created. `drive.file` grants access per Cloud project as far as we know; if it turns out per OAuth client, the app would create a second `SafeRec` folder. That would be acceptable (the old sessions stay readable in Drive itself), but it must be reported, not discovered later.

### 2. Signing configuration

- **Locally, Gradle signs** as today: `keystore.jks` and the passwords in `local.properties`, alias `cert`. A local release build without the passwords fails loudly at signing time, never produces an unsigned or debug-signed `release` APK.
- **In CI, Gradle never sees the key** (builder's call, reasoned below). A Gradle property (for example `-Psaferec.unsignedRelease=true`) drops the release `signingConfig`, so `assembleRelease` produces `app-release-unsigned.apk`. Only that explicit switch can produce an unsigned release. A separate job then signs it with the SDK's `apksigner`.
- Why: a Gradle build downloads and runs hundreds of third-party artifacts (plugins, AGP, the toolchain resolver). With signing in Gradle, the decoded keystore and both passwords would live in that process. Signing in a job that runs no build code keeps them away from all of it, by construction.

### 3. CI on every push and pull request (`.github/workflows/ci.yml`)

- Triggers: push to any branch, pull requests. No secrets, no environment, `permissions: contents: read`.
- Steps: JDK (Temurin 17 or 21, whatever AGP 9.1 and Gradle 9.3 accept), Gradle cache, then
  - `bash ./gradlew testDebugUnitTest :guard:testDebugUnitTest`;
  - `bash ./gradlew lintDebug` (a `lint.xml` exists);
  - `bash scripts/check-guard-refs.sh` (the standby switch);
  - the verifier's tests: `uv run --with pytest --with certifi pytest scripts/test_verify_session.py -q --basetemp=tmp/pytest` (they need `openssl` on the runner).
- Always `bash ./gradlew`, never `./gradlew`: the working copy is synced by Filen, which drops the executable bit (today `gradlew` shows as mode 100644 locally). The committed mode is 100755, but `bash` makes the CI immune to a mode change committed by mistake.

### 4. Release on a tag (`.github/workflows/release.yml`)

- Trigger: push of a tag matching `v*`. Checkout with the full history and tags (the notes need the previous tag).
- Secrets: `RELEASE_KEYSTORE_BASE64` (the keystore, base64), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`. They live in a GitHub **environment** `release` whose deployment rule allows tags `v*` only, not at repository level: a branch, a PR or a fork can never read them.
- **Job `build`** (no environment, no secrets), each step stopping the run on failure:
  1. The tag, without the `v`, equals `versionName` in `app/build.gradle.kts`.
  2. The same checks as the CI workflow (a tag can point to a commit no push ever tested).
  3. `assembleRelease` unsigned (part 2), then hand the unsigned APK and R8's `mapping.txt` to the next job as workflow artifacts.
- **Job `publish`** (environment `release`, needs `build`, runs no Gradle and no project code):
  4. Decode the keystore into `$RUNNER_TEMP`, sign the APK with `apksigner` (v2 and v3 schemes; `zipalign` first if the unsigned APK is not aligned), delete the keystore at the end of the job even on failure.
  5. `apksigner verify --print-certs`: the certificate SHA-256 equals the registered one, hard-coded in the workflow.
  6. Name the assets `SafeRec-<version>.apk` and `SafeRec-<version>-mapping.txt` (kept per release as AGENTS.md asks: without it library frames in a crash report cannot be read), and write `SHA256SUMS` for both.
  7. Create the Release `v<version>` with the three assets.
- **Release notes** (builder's call): GitHub's generated notes list merged pull requests, and the owner merges without them, so they would be empty. The notes are the commit subjects since the previous tag (`git log --format='- %s' <previous>..<tag>`). The first Release has no previous tag (1.16 was never tagged, 279 commits): its notes are one fixed paragraph, "First release distributed from GitHub", with a link to the README. Notes can be edited on GitHub afterwards.
- The Release appears only once every asset is ready (created with its assets in one step, or as a draft published last), so a failed run never leaves a half Release.
- Actions used are pinned to a full commit SHA (the repository is public; a tag of a third-party action can be moved).

### 5. `scripts/release.sh <version>`

- Accepts `X.Y` or `X.Y.Z`; refuses anything else, and a version not greater than the current `versionName`.
- Refuses to run unless: on `main`, up to date with `origin/main` (fetch first), nothing staged, `app/build.gradle.kts` without local changes, and the tag does not exist locally or on the remote.
- Before pushing anything, runs every check the release workflow runs: the CI checks (unit tests, lint, `check-guard-refs.sh`, the verifier's tests), then a local signed `assembleRelease` and the same certificate check (`apksigner`, the hard-coded SHA-256). The keystore is on this machine, so a signing problem shows here and not after the tag is public. `check-guard-refs.sh` leaves an off build in `app/build/`: the release build runs after it.
- Sets `versionName = "<version>"`, `versionCode = <current + 1>`.
- Commits **only** `app/build.gradle.kts` with the message `Version <version>` (as the existing "Version 1.16" commit), creates an annotated tag `v<version>`, pushes `main` and the tag.
- Prints the Actions URL to follow the run.
- Unrelated local changes (such as the `gradlew` mode) are neither committed nor a reason to refuse.
- **A run that failed** (builder's call). A passing glitch: re-run the job on GitHub; a fixed secret is picked up by a re-run too. A failure that needs a code fix: `scripts/release.sh --retag` moves the tag of the current `versionName` to the new `HEAD`, only when GitHub has no Release for that tag (`gh release view` fails), and pushes it again. A version number is then never used up by a Release that never existed, and a published Release is never moved.

### 6. Documentation

- **README.md**: the Play badge becomes a link to the latest GitHub Release. An install section: download the APK, allow installs from the browser, and how to check the APK (its SHA-256 in `SHA256SUMS`, the signing certificate fingerprint above).
- **README.md, "Build it yourself"** (builder's call: self-builds stay possible, no build machinery for them). A self-build must use its own `applicationId`: from 2027 an APK with SafeRec's id and another key does not install on certified phones. The section says to change `applicationId` and `android:targetPackage` in `shortcuts.xml` together (or "Record now" breaks), and to register an OAuth client for that id.
- **gradle.properties**: the `saferec.powerGesture` comment no longer calls the switch-off build the Play build.
- **AGENTS.md**:
  - Release Build: the CI workflows, the `release` environment and its secrets, `scripts/release.sh`, the fingerprint check, `mapping.txt` attached to each Release instead of "uploaded to the Play Console", and the Android developer verification facts (package `net.ark3us.saferec.app`, SHA-256, why the old name could not be kept).
  - The "Google Play declaration" section goes.
  - Build switch: no longer "the build for Google Play", now in standby, kept green by CI.
  - Package name and data paths updated.
- **IDEAS.md**: "Unit tests on every push" is done (remove or mark it); "No Play Store bundle unless…" becomes "No release tag unless…"; "The Play build checks itself" loses its Play framing.
- **play_store_description.txt**: deleted.
- **privacy_policy.html**: the contact line no longer mentions the Play Store contact page. Wherever it is hosted, the Cloud project's OAuth consent screen keeps pointing to it.
- Docs in English (the owner's standing choice).

## What only the owner can do (outside the repository)

None of these is done yet (2026-10-21). They do not block the code: the plan can build everything first, and the device checks of the definition of done wait for steps 1 and 2.

1. **Android Developer Console**: register the package `net.ark3us.saferec.app` with the SHA-256 above. A name never seen on Android needs no proof of ownership, so no `adi-registration.properties` file is involved.
2. **Google Cloud Console**, same project: two Android OAuth clients for `net.ark3us.saferec.app`, one with the debug keystore's SHA-1 (debug builds use the same application id, so without it Drive sign-in breaks in device tests; the owner can copy it from the existing debug client), one with `EF:74:3F:…:5E:9E`.
3. Optionally, unpublish the app in the Play Console.

GitHub's environment and secrets are set up by the builder (see Decisions).

## Verification (definition of done)

- A pushed branch and a pull request run the CI workflow, green, with no secret available to it.
- A test tag on a throwaway version (for example `v1.17` for real, or a pre-agreed test tag deleted afterwards, the builder's call with the owner) produces a Release with the three assets; `SHA256SUMS` matches; `apksigner` shows the registered certificate.
- A tag that does not match `versionName` fails at step 1 and creates no Release.
- The APK downloaded from the Release installs on the Galaxy S10+ (test phone, `ANDROID_SERIAL` pinned) next to the old app, signs in to Drive, records, uploads, and lists the sessions already on Drive (or the second-folder outcome above is reported).
- The debug build with the new id signs in to Drive too (debug OAuth client).
- `scripts/device-tests.sh` runs against the new package.

## Left to the builder

- Workflow file names and job layout, JDK version within what the toolchain accepts, the Gradle cache action.
- Exact environment variable and Gradle property names for the signing inputs.
- Whether the release workflow calls the CI workflow (`workflow_call`) or repeats its steps.
- How the Release is created (`gh release create` on the runner or a pinned action), as long as no half Release can appear.
- Whether `release.sh --retag` is a flag or a separate script.
