# Quick Start and Safe Stop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start a recording from a locked phone in under a second, with three presses of the power key, the tile, an app shortcut or, optionally, the camera double press. Stop it only on purpose, and show at once that it is recording and how many pieces are safe.

**Architecture:**
- **One entry point for every quick start.** `QuickStartActivity` is the only screen that may show over the lock screen. It asks `SafeRecService` to start with the quick-start defaults, then disappears (locked) or opens the viewfinder (unlocked).
- **The power-key guard lives in a separate Gradle module, `:guard`.** The app depends on it only when the build switch `saferec.powerGesture` is on, so with the switch off its manifest entries (`specialUse` service, overlay permission) are not in the app at all.
- **The service:** `CMD_START` becomes idempotent and camera switching gets its own command. The notification shows at once, with the elapsed time, the safe count and a Stop that needs the unlock.

**Tech Stack:** Java 11, Android SDK 26-36, Views/XML, Material Components 1.13, WorkManager 2.11.2, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-20-ui-redesign-design.md`. This plan covers its "Starting a recording", "Stopping" and "Feedback while recording" sections. Two more plans follow, both from the same spec: the viewfinder, status chip, first run, settings and texts; then the recordings list, session screen and playback.

## Global Constraints

- minSdk 26, targetSdk 36, compileSdk 36. Views/XML only.
- The reliability rules in `AGENTS.md` hold. Recording never waits for Drive, the network or a token, and errors never stop a recording.
- Strings in English (`values/`) and Italian (`values-it/`). Code, comments, commits and docs in English.
- Commit messages: short and clear, no logs, no `Co-Authored-By`. Never stage `gradlew`, which has a local mode change. Run Gradle as `bash ./gradlew`.
- Never commit secrets or personal data (local paths, usernames, emails, device serials).
- Device tests run on the Galaxy S10+ only, with `ANDROID_SERIAL=<test phone serial>`, and check the model `SM-G975F`. The owner's Pixel is touched only in Task 13, after asking the owner.
- Temporary files go to `./tmp`, never `/tmp`.
- Spec (Starting a recording): "Recording starts **without unlocking**." Only `QuickStartActivity` shows over the lock screen. `MainActivity` never does.
- Spec (Google Play): with the switch off, "nothing about the guard appears: no setting, no first-run screen, no manifest entries (the `specialUse` service and its permission, and the overlay permission)."
- Spec (Stopping): "A start command while already recording does nothing." Camera switching uses its own command.

## Review Focus

1. **A second quick start while recording:** the tile tapped twice, three presses during a recording, the guard and the camera double press firing together. Expected: the start is ignored, with no restart and no gap. Owner: Task 1 (`startWhileRecordingIsIgnored`), plus Task 12 step 3.
2. **A locked phone never shows the viewfinder:** `QuickStartActivity` reads the keyguard once, in `onCreate`. Expected: locked means black, then gone. `MainActivity` is never reachable without the unlock, even when the phone is unlocked a moment later. Owner: Task 3 (`QuickStartActivity` never starts `MainActivity` when locked), plus Task 12 step 2.
3. **The guard after a kill, or with a permission revoked:** expected, the guard comes back (exemption granted) or its status says why it is off. A trigger without the overlay permission is never silent in the log. Owner: Task 7 (`GuardStatusTest`), Task 8, plus Task 12 step 5.
4. **Screen changes that are not the power key:** two quick on/off checks of the time, a camera double press, proximity during a call. Expected: only three changes within 2.5 s trigger, then a 5 s cooldown. Owner: Task 7 (`TriggerRuleTest`), plus Task 12 step 6.
5. **A build with the switch off:** expected, no guard manifest entries, no guard settings row, and no `NoClassDefFoundError` at runtime, since `:guard` is compileOnly. Owner: Task 7 (build switch), plus Task 12 step 1.

---

### Task 1: Idempotent start, and a camera switch command

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/services/StartPolicy.java`
- Modify: `app/src/main/java/net/ark3us/saferec/services/SafeRecService.java` (`onStartCommand`, the `CMD_START` case at ~275)
- Modify: `app/src/main/java/net/ark3us/saferec/MainActivity.java` (`btnSwitchCamera` listener at ~203)
- Test: `app/src/test/java/net/ark3us/saferec/services/StartPolicyTest.java`

**Interfaces:**
- Produces: `SafeRecService.CMD_SWITCH_CAMERA = "SWITCH_CAMERA"`; `StartPolicy.Action { START, RESTART, IGNORE }`; `static StartPolicy.Action StartPolicy.forCommand(String command, boolean recording)`.

- [ ] **Step 1: Write the failing test**

```java
@Test public void startWhileIdleStarts() { assertEquals(START, StartPolicy.forCommand(SafeRecService.CMD_START, false)); }
@Test public void startWhileRecordingIsIgnored() { assertEquals(IGNORE, StartPolicy.forCommand(SafeRecService.CMD_START, true)); }
@Test public void switchWhileRecordingRestarts() { assertEquals(RESTART, StartPolicy.forCommand(SafeRecService.CMD_SWITCH_CAMERA, true)); }
@Test public void switchWhileIdleIsIgnored() { assertEquals(IGNORE, StartPolicy.forCommand(SafeRecService.CMD_SWITCH_CAMERA, false)); }
```

- [ ] **Step 2: Run it and see it fail**

Run: `bash ./gradlew :app:testDebugUnitTest --tests 'net.ark3us.saferec.services.StartPolicyTest'`
Expected: compilation fails (`StartPolicy` and `CMD_SWITCH_CAMERA` do not exist).

- [ ] **Step 3: Implement `StartPolicy.forCommand` and use it in `onStartCommand`**

`CMD_START` and `CMD_SWITCH_CAMERA` share one case. Each enters the foreground with the recording type exactly as `CMD_START` does today, then follows the action:
- `START` → `startRecording()`;
- `RESTART` → `stopRecording()` then `startRecording()`;
- `IGNORE` → `Log.i(TAG, "Already recording: " + command + " ignored")`.

In `MainActivity`, the camera switch during a recording sends `CMD_SWITCH_CAMERA` through `startSafeRecService` (it goes with `startService`: the service is already in the foreground and the app is visible).

- [ ] **Step 4: Run the tests and see them pass**

Run: `bash ./gradlew :app:testDebugUnitTest`
Expected: all pass (75 before this plan, plus the new ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ark3us/saferec/services/StartPolicy.java app/src/test/java/net/ark3us/saferec/services/StartPolicyTest.java app/src/main/java/net/ark3us/saferec/services/SafeRecService.java app/src/main/java/net/ark3us/saferec/MainActivity.java
git commit -m "Ignore a start while recording; switch cameras with its own command"
```

### Task 2: Quick-start defaults and vibrations

**Files:**
- Modify: `app/src/main/java/net/ark3us/saferec/misc/Settings.java`
- Create: `app/src/main/java/net/ark3us/saferec/services/Haptics.java`
- Modify: `app/src/main/java/net/ark3us/saferec/services/SafeRecService.java` (`startRecording`, `onStartCommand`)
- Modify: `app/src/main/AndroidManifest.xml` (`android.permission.VIBRATE`)

**Interfaces:**
- Consumes: Task 1's `StartPolicy.Action`.
- Produces:
  - `Settings.isQuickStartAudioOnly(Context)` / `setQuickStartAudioOnly(Context, boolean)`: key `quickStartAudioOnly`, default false.
  - `Settings.isQuickStartFrontCamera(Context)` / `setQuickStartFrontCamera(Context, boolean)`: key `quickStartFrontCamera`, default false.
  - `Settings.isVibrationsEnabled(Context)` / `setVibrationsEnabled(Context, boolean)`: key `vibrations`, default true.
  - `SafeRecService.EXTRA_QUICK_START = "quick_start"` (boolean extra on `CMD_START`).
  - `Haptics.started(Context)` and `Haptics.stopped(Context)`.

- [ ] **Step 1: Add the three settings** with the keys and defaults above.

- [ ] **Step 2: Choose what to record from the command**

`startRecording(boolean includeVideo, boolean useFront)` replaces the reads of `Settings.onlyAudio` and `Settings.getUseFrontCamera` inside it:
- `CMD_START` with `EXTRA_QUICK_START` true passes the quick-start settings;
- every other start, and `RESTART`, passes the viewfinder's (`!Settings.onlyAudio`, `Settings.getUseFrontCamera`).

- [ ] **Step 3: Vibrate on `START` and on `CMD_STOP`, never on `RESTART`**

`Haptics.started` plays the waveform `{0, 80, 120, 80}` (two short pulses), and `Haptics.stopped` one 400 ms pulse. Both do nothing when `Settings.isVibrationsEnabled` is false. Add `<uses-permission android:name="android.permission.VIBRATE" />`.

- [ ] **Step 4: Build and run the tests**

Run: `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ark3us/saferec/misc/Settings.java app/src/main/java/net/ark3us/saferec/services/Haptics.java app/src/main/java/net/ark3us/saferec/services/SafeRecService.java app/src/main/AndroidManifest.xml
git commit -m "Quick-start defaults, and vibrate on start and stop"
```

### Task 3: QuickStartActivity, the one screen over the lock screen, and the app shortcut

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/QuickStartActivity.java`
- Create: `app/src/main/res/xml/shortcuts.xml`
- Modify: `app/src/main/AndroidManifest.xml` (activity, MainActivity's `android.app.shortcuts` meta-data)
- Modify: `app/src/main/res/values/themes.xml` (`Theme.SafeRec.QuickStart`: black window background, no window animations)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`

**Interfaces:**
- Consumes: `SafeRecService.CMD_START`, `EXTRA_QUICK_START` (Task 2).
- Produces:
  - `QuickStartActivity.EXTRA_SOURCE = "net.ark3us.saferec.extra.SOURCE"`;
  - the source values `SOURCE_TILE = "tile"`, `SOURCE_SHORTCUT = "shortcut"`, `SOURCE_POWER = "power"`, `SOURCE_CAMERA = "camera"`;
  - the component name `net.ark3us.saferec.QuickStartActivity`, which the guard (Task 8) and the camera alias (Task 10) target.

- [ ] **Step 1: Write `QuickStartActivity`** (extends `Activity`).

`onCreate`:
1. Show over the lock screen and turn the screen on: `setShowWhenLocked(true)` and `setTurnScreenOn(true)` on API 27+; `FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON` window flags on 26.
2. Set a black view whose content description is `R.string.quick_start_recording`.
3. Only when `savedInstanceState == null`, send `CMD_START` with `EXTRA_QUICK_START = true` via `ContextCompat.startForegroundService`. A resumed or recreated activity never sends a second start; the spike's `onResume` did.
4. Read `KeyguardManager.isKeyguardLocked()` **once**:
   - if locked, `finish()` after 1500 ms;
   - otherwise start `MainActivity` (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`) and `finish()`.
5. Log the source: `EXTRA_SOURCE`, or `SOURCE_CAMERA` when the action is `STILL_IMAGE_CAMERA` or `STILL_IMAGE_CAMERA_SECURE`.

- [ ] **Step 2: Declare it in the manifest**

- `exported="false"`. The launcher starts static shortcuts as the publishing app; Task 12 verifies it. Only the Task 10 alias is exported.
- `excludeFromRecents="true"`, `noHistory="true"`, `launchMode="singleTask"`, `taskAffinity="net.ark3us.saferec.quickstart"`, `theme="@style/Theme.SafeRec.QuickStart"`.
- No `showWhenLocked` attribute: the code sets it.

- [ ] **Step 3: Add the static shortcut**

`shortcuts.xml` declares shortcut `record`:
- labels `@string/shortcut_record_short` ("Record" / "Registra") and `@string/shortcut_record_long` ("Record now" / "Registra ora");
- icon `@drawable/ic_stat_shield_record`;
- intent: action `android.intent.action.VIEW`, `targetClass="net.ark3us.saferec.QuickStartActivity"`, extra `EXTRA_SOURCE = shortcut`.

Reference it from MainActivity with `<meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />`. Strings: `quick_start_recording` = "Recording" / "Registrazione".

- [ ] **Step 4: Build**

Run: `bash ./gradlew :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL. Lint shows no warnings beyond the baseline (InlinedApi ×2, OldTargetApi ×1).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ark3us/saferec/QuickStartActivity.java app/src/main/res/xml/shortcuts.xml app/src/main/AndroidManifest.xml app/src/main/res/values/themes.xml app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "Quick start over the lock screen, and a Record now shortcut"
```

### Task 4: The tile always records; drop the hidden auto-start

**Files:**
- Modify: `app/src/main/java/net/ark3us/saferec/services/SafeRecTileService.java`
- Modify: `app/src/main/java/net/ark3us/saferec/misc/Settings.java` (remove `isAutoStartOnLaunch`/`setAutoStartOnLaunch` and key `autoStartOnLaunch`)
- Modify: `app/src/main/java/net/ark3us/saferec/MainActivity.java` (`handleLaunchCommand` at ~566-598, the auto-start settings row at ~271)
- Modify: `app/src/main/java/net/ark3us/saferec/services/SafeRecService.java` (remove `EXTRA_FROM_TILE`, `EXTRA_FROM_NOTIFICATION`)
- Modify: `app/src/main/java/net/ark3us/saferec/services/NotificationHelper.java` (content intent without the extra)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml` (remove `auto_start`)
- Modify: `scripts/device-tests.sh` (`tile_start`: drop `--ez from_tile true`; drop the `autoStartOnLaunch` writes)

**Interfaces:**
- Consumes: `QuickStartActivity`, `EXTRA_SOURCE`, `SOURCE_TILE` (Task 3).

- [ ] **Step 1: Tile.** `onClick` without `unlockAndRun`. It starts `QuickStartActivity` with `EXTRA_SOURCE = tile` through `startActivityAndCollapse`: a `PendingIntent` (`FLAG_IMMUTABLE`) on API 34+, the `Intent` below.

- [ ] **Step 2: Remove the auto-start gate.**
  - `handleLaunchCommand` runs a launch command as it is: no tile, notification or auto-start checks. A notification tap only opens the app.
  - Remove the setting, its row, the two extras and the `auto_start` strings.
  - In `scripts/device-tests.sh`, `tile_start` keeps launching `MainActivity --es command START`.

- [ ] **Step 3: Build, tests, and the script's syntax**

Run: `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug && bash -n scripts/device-tests.sh && ! grep -rn "autoStartOnLaunch\|EXTRA_FROM_TILE\|EXTRA_FROM_NOTIFICATION\|from_tile" app/src scripts`
Expected: BUILD SUCCESSFUL, all tests pass, and the grep finds nothing.

- [ ] **Step 4: Commit**

```bash
git add app/src scripts/device-tests.sh
git commit -m "The tile records at once, also from the lock screen"
```

### Task 5: Count the pieces safe on Drive

**Files:**
- Modify: `app/src/main/java/net/ark3us/saferec/net/FileUploader.java` (`UploadListener`, `submit` at ~135-160)
- Create: `app/src/main/java/net/ark3us/saferec/services/SafeCount.java`
- Modify: `app/src/main/java/net/ark3us/saferec/data/ServiceState.java`
- Modify: `app/src/main/java/net/ark3us/saferec/services/SafeRecService.java` (the uploader listener at ~113, session start)
- Test: `app/src/test/java/net/ark3us/saferec/net/FileUploaderTest.java`, `app/src/test/java/net/ark3us/saferec/services/SafeCountTest.java`

**Interfaces:**
- Produces:
  - `FileUploader.UploadListener.onFileUploaded(File file)`: a default method, called once per successful upload, before the next scan;
  - `ServiceState.getSafeChunks(): MutableLiveData<Integer>` (starts at 0) and `ServiceState.setSafeChunks(int)` (`postValue`);
  - `SafeCount.isSessionChunk(@Nullable MediaFile file, @Nullable String sessionId): boolean`.

- [ ] **Step 1: Write the failing tests**

In `FileUploaderTest`, `aSuccessfulUploadIsReportedToTheListener`: a listener that collects names. With the existing `FakeUploader` succeeding, uploading `x.mp4` reports exactly `["x.mp4"]`. A failing upload of `y.mp4` reports nothing.

`SafeCountTest`:
```java
@Test public void aChunkOfTheSessionCounts() { assertTrue(SafeCount.isSessionChunk(MediaFile.fromFile(new File("1_video_2.0.mp4")), "1")); }
@Test public void aTimestampDoesNotCount() { assertFalse(SafeCount.isSessionChunk(MediaFile.fromFile(new File("1_video_2.0.tsa")), "1")); }
@Test public void anotherSessionDoesNotCount() { assertFalse(SafeCount.isSessionChunk(MediaFile.fromFile(new File("9_video_2.0.mp4")), "1")); }
@Test public void unknownFilesDoNotCount() { assertFalse(SafeCount.isSessionChunk(null, "1")); assertFalse(SafeCount.isSessionChunk(MediaFile.fromFile(new File("1_video_2.0.mp4")), null)); }
```

- [ ] **Step 2: Run them and see them fail**

Run: `bash ./gradlew :app:testDebugUnitTest --tests '*FileUploaderTest' --tests '*SafeCountTest'`
Expected: compilation fails.

- [ ] **Step 3: Implement**
  - `submit` calls the listener's `onFileUploaded(file)` when `uploaded` is true, before `onUploadSucceeded()`.
  - The service resets its `safeChunks` to 0 when it creates a new session id. On `onFileUploaded`, when `SafeCount.isSessionChunk` is true for `currentSessionId`, it posts to the main thread: increment, `ServiceState.setSafeChunks`, `updateNotification()`.

- [ ] **Step 4: Run all tests and see them pass**

Run: `bash ./gradlew :app:testDebugUnitTest`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ark3us/saferec/net/FileUploader.java app/src/main/java/net/ark3us/saferec/services/SafeCount.java app/src/main/java/net/ark3us/saferec/data/ServiceState.java app/src/main/java/net/ark3us/saferec/services/SafeRecService.java app/src/test/java/net/ark3us/saferec/net/FileUploaderTest.java app/src/test/java/net/ark3us/saferec/services/SafeCountTest.java
git commit -m "Count the chunks of a session that reached Drive"
```

### Task 6: A notification that shows at once, discreet on the lock screen, with an authenticated Stop

**Files:**
- Modify: `app/src/main/java/net/ark3us/saferec/services/NotificationHelper.java`
- Modify: `app/src/main/java/net/ark3us/saferec/services/SafeRecService.java` (`buildNotification` at ~172)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`

**Interfaces:**
- Consumes: Task 5's safe count; `SafeRecService.CMD_STOP`.
- Produces: `NotificationHelper.buildNotification(Context context, boolean isRecording, long recordingSinceMillis, int safeChunks, int activeUploads, int activeDeletions, int activeTimestamping, int pendingFiles)`.

- [ ] **Step 1: Implement**
  - API 31+: `setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)`.
  - While recording: `setUsesChronometer(true)`, `setWhen(recordingSinceMillis)` (the session id, parsed: the session's start in ms, kept across a camera switch), `setShowWhen(true)`. The text is the plural `notif_safe_chunks` ("%d piece safe on Drive" / "%d pieces safe on Drive"; IT "%d pezzo al sicuro su Drive" / "%d pezzi al sicuro su Drive"), or the existing recording text while 0.
  - While recording, a Stop action labelled `notif_action_stop` ("Stop" / "Ferma"), with icon `outline_stop_circle_24`:
    - API 31+: `PendingIntent.getService(context, 1, SafeRecService.createCommandIntent(context, CMD_STOP), FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)` and `setAuthenticationRequired(true)`;
    - below 31: the action's intent opens `MainActivity`.
  - `setVisibility(Notification.VISIBILITY_PRIVATE)` and a public version: title `app_name`, text `notif_public_active` ("Active" / "Attivo"), no actions.

- [ ] **Step 2: Build and run the tests**

Run: `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL, all tests pass, no new lint warnings.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ark3us/saferec/services/NotificationHelper.java app/src/main/java/net/ark3us/saferec/services/SafeRecService.java app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "Show the recording notification at once, with an authenticated Stop"
```

### Task 7: The `:guard` module, its build switch and its pure rules

**Files:**
- Modify: `settings.gradle.kts` (`include(":guard")`)
- Modify: `gradle/libs.versions.toml` (`[plugins] android-library = { id = "com.android.library", version.ref = "agp" }`)
- Modify: `build.gradle.kts` (`alias(libs.plugins.android.library) apply false`)
- Modify: `gradle.properties` (`saferec.powerGesture=true`, commented: set false for a Play build without the three presses)
- Create: `guard/build.gradle.kts`: android library; namespace `net.ark3us.saferec.guard`; compileSdk 36, minSdk 26; Java 11; `unitTests.isReturnDefaultValues = true`; `implementation(libs.work.runtime)`, `testImplementation(libs.junit)`
- Create: `guard/src/main/AndroidManifest.xml` (empty `<manifest/>` for now; Task 8 fills it)
- Modify: `app/build.gradle.kts`: `val powerGesture = providers.gradleProperty("saferec.powerGesture").map { it.toBoolean() }.getOrElse(true)`; `buildConfigField("boolean", "POWER_GESTURE", powerGesture.toString())`; `if (powerGesture) implementation(project(":guard")) else compileOnly(project(":guard"))`
- Create: `guard/src/main/java/net/ark3us/saferec/guard/TriggerRule.java`
- Create: `guard/src/main/java/net/ark3us/saferec/guard/Guard.java` (only `Status` and `statusOf` in this task)
- Test: `guard/src/test/java/net/ark3us/saferec/guard/TriggerRuleTest.java`, `guard/src/test/java/net/ark3us/saferec/guard/GuardStatusTest.java`

**Interfaces:**
- Produces:
  - `TriggerRule`: `SPAN_MS = 2500`, `COOLDOWN_MS = 5000`, and `boolean onScreenChange(long elapsedMs)`. It is true when this change and the two before it fall within `SPAN_MS`, and no trigger happened in the last `COOLDOWN_MS`.
  - `Guard.Status { DISABLED, NEEDS_OVERLAY, NEEDS_BATTERY_EXEMPTION, STOPPED, ACTIVE }` and `static Guard.Status Guard.statusOf(boolean enabled, boolean canDrawOverlays, boolean ignoringBatteryOptimizations, boolean running)`. Precedence follows the declaration order; `ACTIVE` needs all four.
  - App code may reference `net.ark3us.saferec.guard.*` only inside `if (BuildConfig.POWER_GESTURE)` blocks. With the switch off the constant is false, javac drops those blocks, and the compileOnly module never needs to load.

- [ ] **Step 1: Write the failing tests**

```java
// TriggerRuleTest: feed the times in order; only the listed call returns true
@Test public void threeChangesWithinTheSpanTrigger()  { /* 0, 740, 830 → third true */ }
@Test public void slowChangesDoNotTrigger()           { /* 0, 1500, 3000 → all false */ }
@Test public void aDoublePressNeverTriggers()          { /* 0, 60 → both false */ }
@Test public void aBurstTriggersOnce()                 { /* 0,100,200,300,400 → only the third true */ }
@Test public void afterTheCooldownItTriggersAgain()    { /* burst above, then 6000, 6100, 6200 → the last true */ }
@Test public void theSpanIncludesItsEnd()              { /* 0,1250,2500 → third true; fresh rule 0,1250,2501 → false */ }
// GuardStatusTest
@Test public void disabledWinsOverEverything()         { assertEquals(DISABLED, Guard.statusOf(false, false, false, false)); }
@Test public void theOverlayComesFirst()               { assertEquals(NEEDS_OVERLAY, Guard.statusOf(true, false, false, true)); }
@Test public void thenTheBatteryExemption()            { assertEquals(NEEDS_BATTERY_EXEMPTION, Guard.statusOf(true, true, false, true)); }
@Test public void allGrantedButNotRunningIsStopped()   { assertEquals(STOPPED, Guard.statusOf(true, true, true, false)); }
@Test public void allGrantedAndRunningIsActive()       { assertEquals(ACTIVE, Guard.statusOf(true, true, true, true)); }
```

- [ ] **Step 2: Run them and see them fail**

Run: `bash ./gradlew :guard:testDebugUnitTest`
Expected: compilation fails.

- [ ] **Step 3: Implement `TriggerRule` and `Guard.statusOf`**, and wire the module and the switch as listed under Files.

- [ ] **Step 4: Tests pass, and both switch positions build**

Run: `bash ./gradlew :guard:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug && bash ./gradlew :app:assembleDebug -Psaferec.powerGesture=false`
Expected: all tests pass, and both builds succeed.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml build.gradle.kts gradle.properties app/build.gradle.kts guard
git commit -m "Add the guard module behind a build switch"
```

### Task 8: The guard: service, boot receiver, watchdog and API

**Files:**
- Create in `guard/src/main/java/net/ark3us/saferec/guard/`: `GuardService.java`, `GuardBootReceiver.java`, `GuardWatchdog.java`
- Modify: `guard/src/main/java/net/ark3us/saferec/guard/Guard.java`
- Modify: `guard/src/main/AndroidManifest.xml`:
  - permissions `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`;
  - `GuardService`, `exported="false"`, `foregroundServiceType="specialUse"`, with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Personal safety: start a recording with three presses of the power key, with the phone locked" />`;
  - `GuardBootReceiver`, `exported="false"`, for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- Create: `guard/src/main/res/values/strings.xml`, `values-it/strings.xml`, `drawable/ic_stat_guard.xml`

**Interfaces:**
- Consumes: `TriggerRule`, `Guard.statusOf` (Task 7). It reaches `QuickStartActivity` by name only: component `net.ark3us.saferec.QuickStartActivity`, extra `"net.ark3us.saferec.extra.SOURCE"` = `"power"`, matching Task 3's constants. A comment points there; the module has no compile dependency on the app.
- Produces, for the app's settings and first run:
  - `Guard.setEnabled(Context, boolean)` and `Guard.isEnabled(Context)` (prefs `saferec_guard`, key `enabled`);
  - `Guard.status(Context): Guard.Status`;
  - `Guard.ensureRunning(Context)`;
  - `Guard.overlaySettingsIntent(Context): Intent` (`ACTION_MANAGE_OVERLAY_PERMISSION`, `package:` URI);
  - `Guard.batterySettingsIntent(): Intent` (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`);
  - `GuardService.running` (`static volatile boolean`).

- [ ] **Step 1: `GuardService`**
  - `onCreate`:
    - creates channel `guard` (`IMPORTANCE_MIN`);
    - builds a silent, ongoing, `VISIBILITY_SECRET` notification: title `guard_ready_title` "SafeRec ready" / "SafeRec pronto"; text `guard_ready_text` "Press the power key three times to record" / "Premi tre volte il tasto di accensione per registrare";
    - calls `startForeground(4242, …, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` on API 34+, without a type below;
    - on `ForegroundServiceStartNotAllowedException` (API 31+) logs and `stopSelf()`;
    - registers `SCREEN_ON`/`SCREEN_OFF` and sets `running = true`.
  - When `TriggerRule.onScreenChange(SystemClock.elapsedRealtime())` is true, it starts the quick-start component with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION`. If the start throws, it logs "trigger refused" with the exception.
  - `START_STICKY`; `onDestroy` unregisters and sets `running = false`.

- [ ] **Step 2: `Guard`, `GuardBootReceiver`, `GuardWatchdog`**
  - `ensureRunning` starts the service (`startForegroundService`, exceptions logged) when the guard is enabled, the overlay permission is granted, and it is not running. It runs without the battery exemption too: the status then warns.
  - `setEnabled(true)` stores the flag, calls `ensureRunning` and schedules the watchdog. `setEnabled(false)` stops the service and cancels the watchdog.
  - The receiver calls `ensureRunning`.
  - The watchdog is a `Worker` (`ensureRunning`, `Result.success()`) on a 15-minute `PeriodicWorkRequest`, unique name `guard-watchdog`, `ExistingPeriodicWorkPolicy.KEEP`.

- [ ] **Step 3: Build and run the tests**

Run: `bash ./gradlew :guard:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: BUILD SUCCESSFUL, and no lint warnings beyond the baseline.

- [ ] **Step 4: Commit**

```bash
git add guard
git commit -m "Start a recording with three presses of the power key"
```

### Task 9: Settings rows for quick start, vibrations and the three presses

**Files:**
- Modify: `app/src/main/java/net/ark3us/saferec/MainActivity.java` (`populateSettings` at ~261; `onResume`)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`

**Interfaces:**
- Consumes: Task 2's settings; with `BuildConfig.POWER_GESTURE`, the `Guard` API (Task 8).

These rows are interim: the settings plan restyles them.

- [ ] **Step 1: Add the rows**
  - "Quick start records": a single choice of Video / Audio only.
  - "Quick start camera": a single choice of Back / Front.
  - "Vibrations": a toggle.
  - Only inside `if (BuildConfig.POWER_GESTURE)`: "Three presses of the power key". Its value shows `Guard.status`. Turning it on calls `Guard.setEnabled(true)`, then opens `Guard.overlaySettingsIntent` if the overlay is missing. Back in `onResume`, a field `guardSetupPending` opens `Guard.batterySettingsIntent` once if the status is `NEEDS_BATTERY_EXEMPTION`. Turning it off calls `Guard.setEnabled(false)`.
  - EN/IT strings for each title and value.

- [ ] **Step 2: Build both switch positions**

Run: `bash ./gradlew :app:assembleDebug :app:lintDebug && bash ./gradlew :app:assembleDebug -Psaferec.powerGesture=false`
Expected: both succeed, with no new lint warnings.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ark3us/saferec/MainActivity.java app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "Settings for quick start, vibrations and the three presses"
```

### Task 10: The optional camera double press

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/CameraGesture.java`
- Modify: `app/src/main/AndroidManifest.xml`:
  - `<activity-alias android:name=".CameraGestureAlias" android:targetActivity=".QuickStartActivity" android:enabled="false" android:exported="true">`, with an intent filter for `android.media.action.STILL_IMAGE_CAMERA` and `android.media.action.STILL_IMAGE_CAMERA_SECURE`, category `DEFAULT`;
  - `<queries><package android:name="com.android.systemui" /></queries>`.
- Modify: `app/src/main/java/net/ark3us/saferec/MainActivity.java` (one more settings row)
- Modify: `app/src/main/res/values/strings.xml`, `values-it/strings.xml`
- Test: `app/src/test/java/net/ark3us/saferec/CameraGestureTest.java`

**Interfaces:**
- Consumes: `QuickStartActivity` (Task 3).
- Produces:
  - `CameraGesture.allowsOtherApps(@Nullable String gesturePackage, String ownPackage): boolean`;
  - `CameraGesture.isAvailable(Context)`, `isEnabled(Context)` and `setEnabled(Context, boolean)` (`setComponentEnabledSetting` on `net.ark3us.saferec.CameraGestureAlias`, `DONT_KILL_APP`);
  - `CameraGesture.tryIntent(): Intent` (`MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA`).

- [ ] **Step 1: Write the failing test**

```java
@Test public void noFixedPackageAllowsOthers() { assertTrue(CameraGesture.allowsOtherApps(null, "net.ark3us.saferec")); assertTrue(CameraGesture.allowsOtherApps("", "net.ark3us.saferec")); }
@Test public void ourOwnPackageAllows() { assertTrue(CameraGesture.allowsOtherApps("net.ark3us.saferec", "net.ark3us.saferec")); }
@Test public void anotherFixedCameraDoesNot() { assertFalse(CameraGesture.allowsOtherApps("com.google.android.GoogleCamera", "net.ark3us.saferec")); }
```

- [ ] **Step 2: Run it and see it fail**

Run: `bash ./gradlew :app:testDebugUnitTest --tests '*CameraGestureTest'`
Expected: compilation fails.

- [ ] **Step 3: Implement**
  - `isAvailable` reads SystemUI's `config_cameraGesturePackage` (`getResourcesForApplication("com.android.systemui")`, `getIdentifier(…, "string", "com.android.systemui")`) and returns `allowsOtherApps(value, getPackageName())`. If the read fails, it returns true: "try it now" will tell.
  - The settings row "Camera double press opens SafeRec" appears only when `isAvailable`. Turning it on enables the alias and shows a dialog: "Choose SafeRec and Always". It has a "Try it now" button (`tryIntent()`) and the undo path (Settings → Apps → SafeRec → Open by default). Turning it off disables the alias. EN/IT strings.

- [ ] **Step 4: Run the tests and see them pass**

Run: `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: all pass, with no new lint warnings.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ark3us/saferec/CameraGesture.java app/src/test/java/net/ark3us/saferec/CameraGestureTest.java app/src/main/AndroidManifest.xml app/src/main/java/net/ark3us/saferec/MainActivity.java app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "Optionally open SafeRec with the camera double press"
```

### Task 11: Docs

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md` (Features: quick start)

- [ ] **Step 1: Update `AGENTS.md`**
  - Package structure: `QuickStartActivity`, `CameraGesture`, `StartPolicy`, `SafeCount`, `Haptics`, and the `guard/` module.
  - SafeRecService commands: `CMD_START` is ignored while recording; `CMD_SWITCH_CAMERA`; `EXTRA_QUICK_START`.
  - Settings table: remove `autoStartOnLaunch`; add `quickStartAudioOnly`, `quickStartFrontCamera` and `vibrations`.
  - The notification: immediate, a chronometer, the safe count, the authenticated Stop, and the public version.
  - A "Quick start" section:
    - the three presses (broadcast timing, the 2.5 s rule, the overlay and `BAL_ALLOW_SAW`, the spike's measurements: 0.6 s to video, 30-54 ms to the screen);
    - the guard's costs and robustness (exemption, watchdog, boot);
    - the build switch (`-Psaferec.powerGesture=false`);
    - the camera double press;
    - the Play `specialUse` declaration text, ready to paste: the property value from Task 8 plus one sentence on why no other type fits.
  - The new unit tests.
- [ ] **Step 2: Commit**

```bash
git add AGENTS.md README.md
git commit -m "Document quick start and safe stop"
```

### Task 12: Verify on the S10+

**Files:**
- Create: `tmp/quick_start_check.sh`. It is temporary and not committed. It pins `ANDROID_SERIAL=<test phone serial>`, checks `SM-G975F`, refuses to run when the outbox or `recording/` is not empty, captures logcat to `tmp/quick-start/full.log`, sends `STOP` through `MainActivity` (the service is not exported), and trashes every session it recorded at the end.

Do each step on the device and keep its evidence (log lines, screenshots) in `tmp/quick-start/`. Steps marked **owner** need the owner's hands: ask, then wait.

- [ ] **Step 1: Switch off.** `bash ./gradlew :app:assembleDebug -Psaferec.powerGesture=false`. The merged manifest (`find app/build/intermediates -path '*merged_manifest*' -name AndroidManifest.xml`) has none of `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_SPECIAL_USE` or `GuardService`. Install it, open settings: no three-presses row, no crash. Then install the normal build.
- [ ] **Step 2: Tile from the lock screen.** Lock with the owner's PIN, screen on. Tap the tile (**owner**, or `cmd statusbar click-tile net.ark3us.saferec/.services.SafeRecTileService`). Expected:
  - "Starting recording session" in under 1 s;
  - `isKeyguardShowing=true` throughout;
  - `MainActivity` never resumed.
- [ ] **Step 3: Second start ignored.** While recording, tap the tile again. Expected: "Already recording: START ignored", and no new "Starting recording session".
- [ ] **Step 4: Notification.** Expected:
  - posted under 1 s after the start (`dumpsys notification`);
  - a chronometer, and the safe count after the first chunk;
  - the lock screen shows "SafeRec · Active";
  - Stop asks for the unlock (**owner**).
- [ ] **Step 5: Three presses.** Enable them in settings: overlay, then the battery page (**owner** grants both). With the phone locked and the screen off, three slow presses with `input keyevent KEYCODE_POWER` as in the spike, then the **owner**'s own. Expected: recording in under 1 s, phone still locked. Then `am crash net.ark3us.saferec`: the guard is back (`GuardService.running` in the log, or `dumpsys activity services`) within the sticky restart or 15 min.
- [ ] **Step 6: False triggers.** Expected nothing from each of these:
  - a camera double press;
  - two quick on/off presses;
  - lift to wake;
  - (**owner**, if the S10+ has a SIM) moving the phone to and from the ear during a call.
- [ ] **Step 7: Camera double press.** Enable the option, "Try it now", choose SafeRec and Always (**owner**). Locked, double press: recording. Disable it: the double press opens Aperture again.
- [ ] **Step 8: Shortcut.** Long press on the icon, then "Record now": recording.
- [ ] **Step 9: R8.** `bash ./gradlew :app:assembleR8test`, install it over the debug build, repeat steps 2 and 5 once. Expected: no `ClassNotFoundException`, `NoSuchMethodException` or crash in the log. Then reinstall the debug build.
- [ ] **Step 10: Clean up.**
  - Trash the test sessions; Drive keeps the owner's 7 sessions.
  - Revoke the overlay with `appops set net.ark3us.saferec SYSTEM_ALERT_WINDOW default`, unless the owner keeps the feature on.
  - Delete `tmp/quick-start*`.
  - Record the measured numbers in `AGENTS.md`'s Quick start section and commit: `git commit -m "Record the quick start device checks"`.

### Task 13: Verify on the Pixel (owner's consent first)

The Pixel is the owner's everyday phone. Ask before connecting to it, then follow the answer.

- [ ] **Step 1:** With consent, install the debug build. Then check:
  - a single camera double press opens only Pixel Camera, and SafeRec does not start;
  - three slow presses with the phone locked start SafeRec;
  - whether the "camera double press" row appears, i.e. whether `config_cameraGesturePackage` is set.
- [ ] **Step 2:** Clean up as the owner asks: the test sessions, the overlay permission, and which build stays installed. Record the results in `AGENTS.md` and commit.
