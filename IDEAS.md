# SafeRec — Ideas

Ideas not yet planned. Each one says what it is, why it matters for evidence safety, and a starting point.

## Safety and alerts

### A vibration language, to know without looking
In a pocket, or with eyes on the danger, the screen tells nothing. Short vibration patterns could: two short pulses when the recording starts, one long one when it stops, a tick each time a chunk is safe on Drive, and three when something stops working (camera lost, Drive unreachable). The owner learns four patterns once, in the emergency drill, and knows the evidence is being saved with the phone hidden. A setting turns them off for when even a buzz could be heard.

### Trusted contacts auto-share
At session start, share the session folder with 1–3 preset emails (Drive permission `type=user`, `role=reader`). Google emails them a link, so the share doubles as a free alert, and they keep access even if the owner's account is wiped. Safer than the current "anyone with the link" share of the whole root folder.

### Dead man's switch with Apps Script
Each uploaded chunk updates a `heartbeat` file on Drive. A time-driven Google Apps Script in the user's own account checks it every minute. If the heartbeat is older than ~2 minutes and the session has no end marker, `MailApp` notifies the trusted contacts. No server to run.

### Exemption from permission auto-reset
Android revokes permissions of apps unused for months, which is exactly how an emergency app is used. Ask the user to exempt SafeRec with `IntentCompat.createManageUnusedAppRestrictionsIntent()` so the camera and microphone still work on the day it matters.

### Emergency drill
A settings button that records 15 s, checks the chunk exists, uploads it and shows ✅/❌ for each step (permissions, network, Drive, TSA). Better to find a broken setup during a drill than during an emergency.

### Drill and test sessions, trashed together
Sessions recorded by the emergency drill, or by a test, could carry a marker: a `drill` line in the manifest, or a Drive `appProperties` key. The recordings list would then offer one "Trash drill recordings" action, with a confirmation saying how many. Without multi-select, cleaning up after a drill or a test round would no longer mean opening every session. A real recording can never be in that batch, because it was never marked.

### Quick-start readiness at a glance
One list of every way to start a recording, each with ✅/❌ and a one-tap fix. The tile: is it in quick settings? `StatusBarManager.requestAddTileService()` (Android 13+) adds it with one system dialog, no dragging. The three presses: `Guard.status()`. The camera double press: do both camera actions resolve to SafeRec (`resolveActivity`)? A test found that Android's chooser can make "Always" unreachable on the first try. A "Record now" icon on the home screen: `ShortcutManager.requestPinShortcut()`. The owner finds a broken path before the day it is needed, and the drill above can run each path once.

## Evidence integrity

### Hash chain for timestamps
Chain chunk hashes (`H_n = sha256(H_{n-1} ‖ sha256(chunk_n))`) and timestamp only the chain head, at most every 15 s. One timestamp then proves existence, order and completeness of all previous chunks, and respects Sectigo's request of ≥15 s between automated requests.

### OpenTimestamps as a second anchor
Anchor the chain head with OpenTimestamps (free, no rate limit, anchored to Bitcoin) next to the Sectigo RFC 3161 timestamp. Two independent witnesses are harder to dispute than one.

### Verification guide
A short guide plus script (`openssl ts -verify -data chunk.mp4 -in chunk.tsa -CAfile …`) so a lawyer or expert can check a recording in two minutes.

## Upload reliability

### Say when Drive is full
Drive answers HTTP 403 `storageQuotaExceeded` when the account is full. Today that is retried forever, silently. Report it as its own error ("Drive is full: recordings stay on the phone") so the user can free space before an emergency, not after.

### Reconnect Drive from the notification
When a background refresh needs user interaction, the `AuthorizationResult` already holds the consent `PendingIntent`. Put it in a notification ("Tap to reconnect Google Drive: 4 recordings waiting"). One tap fixes it, without opening the app, and the waiting count makes the stakes clear.

### Idempotent folder creation
A folder `create` whose response is lost (timeout) can still succeed on Drive. Pre-generate folder IDs with `files.generateIds`, persist them, and create with that ID: a retry then fails with 409 instead of making a duplicate, whatever Drive's search index says.

### A recordings list that opens at once
The list waits for Drive every time it opens: with 34 sessions it took 14-20 s on the S10+, because each session's manifest is read before any card shows. Keep the last list on the phone (session ids, status, duration, thumbnail links: no media, nothing secret beyond what Drive already holds) and show it at once, marked "updating…", then replace it when Drive answers. A status may only get better or worse after the refresh, never be shown as Complete from the cache alone: a cached card shows its last checked status with the time of that check.

### Drive repair tool
One button in the recordings screen that merges duplicate `SafeRec` (and session) folders made by older builds into the oldest one, then deletes the empty duplicates.

### One merged file per orientation
An MP4 holds one orientation hint, so merging a session where the phone was turned shows some segments sideways. When the hints differ, write one merged file per run of equal hints (`merged_<session>_1.mp4`, `_2` …) instead of one wrong file. No re-encoding, and every part plays upright.

## Quality and testing

### In-app chunk health check
After closing each chunk, check it with `MediaExtractor`: both tracks have samples, duration ~10 s. On failure, warn in the notification. The "no audio after the first chunk" bug would have been caught on users' phones instead of after an emergency.

### Device tests as a release gate
No release tag unless `scripts/device-tests.sh` passed on at least one real phone: `scripts/release.sh` could ask for the log of a passing run on the same commit.

### Signing check before Drive tests
When Drive authorization fails with `UNREGISTERED_ON_API_CONSOLE`, have `scripts/device-tests.sh` print the APK's signing SHA-1 and the exact Cloud Console steps to register it. A new dev machine then costs two minutes instead of an investigation.

### Device health guard in device tests
Record `MemAvailable` and `SUnreclaim` (kernel unreclaimable memory) before and after each run, and refuse to run on a thrashing device. A custom-ROM kernel leak (5.3 GB of unreclaimable slab) once caused an ANR that looked like an app bug.

### Fault injection in debug builds
A debug-only `HttpRequestInitializer` that, on a switch from `adb shell am broadcast`, delays, fails or drops the response of the next Drive request after it was sent. Lost responses, timeouts and 401/403/404 then become repeatable tests instead of waiting for a bad network.

### Network sanity check in device tests
Before running, check that the default network is `VALIDATED` and that no global HTTP proxy is set (`settings get global http_proxy`). A leftover proxy pointing to `127.0.0.1:8080` once cut every app off the internet while `adb shell` (which ignores the proxy) still looked fine.

### The standby build checks itself
The build with `saferec.powerGesture=false` crashed at first launch once, from a reference javac kept in a synthetic class, and only a device test caught it. CI now runs `scripts/check-guard-refs.sh` on every push. Still missing: make it a Gradle task that `check` and every off build depend on, and a one-minute device smoke test of the off build (install, first run, record, stop). The off build is in standby, so nobody runs it day to day.

### Smoke test the release build in CI
The `r8test` build can be checked automatically. A GitHub Actions job would build it and start it on an emulator (`reactivecircus/android-emulator-runner`). It would then launch the main screen, the recordings screen and the outbox job, and fail on any `ClassNotFoundException`, `NoSuchFieldException` or crash in logcat. No Google account is needed to catch R8 breaking reflection at startup. It would have caught a missing rule before a release, not on a user's phone.

### One verifier, two languages, one test set
The phone (`SessionCheck`, Java) and the computer (`verify_session.py`) apply the same rules, written twice. A shared set of test sessions would keep them in step: a few session folders (complete, missing chunk, edited manifest, later lines, untrusted authority) with the verdict each must give, stored once under `scripts/testdata/`. Both test suites would run on the same folders and fail when one of them drifts. Its authority's certificate would expire, so the folders are generated by a script rather than committed with their tokens.

### A refused-camera scenario in the device tests
`scripts/device-tests.sh` could cover the sound-only start: `pm revoke net.ark3us.saferec.app android.permission.CAMERA`, then a start from the app and a quick start (`QuickStartActivity` with source `tile`). It would check that the service runs in the foreground as microphone only (`dumpsys activity services`), that the log says "No camera permission: recording sound only", and that the chunks land in the session's `audio/` folder with sound in them. `pm grant` on exit restores the camera. Revoking kills the app, which is what a user revoking it in Settings does too.

## Distribution

### Build provenance for every release
The release workflow could add `actions/attest-build-provenance` for the APK. Anyone could then run `gh attestation verify SafeRec-1.17.apk --repo ark3us/saferec` and see which commit and which workflow run built it. An app whose purpose is evidence would ship with evidence of its own origin, and a tampered mirror of the APK would fail the check.

### "A new version is out" inside the app
Without the Play Store nothing tells a user about updates. Once a day, online only, the app could ask GitHub's API for the latest Release (`/repos/ark3us/saferec/releases/latest`, no token needed). When its tag is newer than `versionName`, a quiet row in Settings would link to the Release page. The app would never download or install anything by itself. Obtainium already reads GitHub Releases, so this is for users who never heard of it.

### Reproducible builds
If two builds of the same tag gave the same unsigned APK, a third party could rebuild SafeRec from source and compare it with the Release, byte for byte apart from the signature. That is what F-Droid's reproducible-build checks need, and it would let the app reach F-Droid while keeping the owner's signature.

### Cross-check the CI build against the local one
`scripts/release.sh` already builds a signed release on the owner's machine before tagging. Once the Release is out, it could download `SafeRec-<version>.apk` and compare its zip entries with the local build, the signing block left out. R8 gives the same output for the same inputs, so a difference would expose a poisoned dependency on one of the two machines: the release key protects who signs, not what was built.

### "Check this copy" in Settings
A row in Settings, Help, showing the SHA-256 of the certificate that signed the installed app (`PackageManager.getPackageInfo(…, GET_SIGNING_CERTIFICATES)`) next to the one published in the README, with a plain "This is the official SafeRec" or "This copy was signed by someone else". Users who install APKs by hand would not need `apksigner` to know whether a copy someone handed them is genuine.

### Say when Google does not know this copy
A copy Google has no OAuth client for (the old id `net.ark3us.saferec` signed with the release key, or a build signed with another key) gets `UNREGISTERED_ON_API_CONSOLE` from Play services after the account picker. Seen on a Pixel running 1.16 under the old id: the chip kept reading "Reconnect Drive", each tap opened the picker and failed in silence, and recordings could only wait on the phone. On that status (and `DEVELOPER_ERROR`) the app could say "This copy of SafeRec cannot use Google Drive: install the latest version from GitHub Releases", with a link, instead of asking to reconnect again.
