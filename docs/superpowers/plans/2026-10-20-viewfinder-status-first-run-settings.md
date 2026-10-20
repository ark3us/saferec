# Viewfinder, Status, First Run and Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the main screen a camera-style viewfinder whose record button nothing can cover or disable, show at a glance whether it records and whether the evidence is safe, replace the 8-step tutorial with a first run that never delays a recording, and turn settings into plain-language groups whose setup steps check themselves.

**Architecture:**
- **Pure rules, thin views.** What the status chip says (`StatusModel`), when a hold stops a recording (`HoldToStop`), which first-run steps exist (`FirstRunSteps`) and which camera try comes next (`CameraGestureSetup.nextTry`) are pure Java with JUnit tests. Activities only feed them inputs and draw the result.
- **The service publishes, the UI reads.** `ServiceState` gains the session start, the audio-only state, the files waiting and a sticky "Drive needs reconnecting" flag. The UI never infers them.
- **Drive never interrupts.** No account picker pops up by itself. The app checks the stored token silently, only when online, and the status chip offers "Connect Drive" or "Reconnect Drive".
- **Settings move to their own screen**, `SettingsActivity`, rebuilt on every `onResume()`, so a status can never go stale.

**Tech Stack:** Java 11, Android SDK 26-36, Views/XML, Material Components 1.13 (Material 3), JUnit 4. No new library.

**Spec:** `docs/superpowers/specs/2026-10-20-ui-redesign-design.md`. This plan covers: "Main screen: the Viewfinder direction", "Stopping" (hold to stop), "Status, never blocking", "Other screens" (Settings, First run, Texts, Visual language), and the open items of "Findings from Plan 1" except the recordings list. Plan 3 follows: recordings list, session screen, playback, and the real thumbnail in the gallery button.

## Global Constraints

- minSdk 26, targetSdk 36, compileSdk 36. Views/XML only, Material Components 1.13, no new dependency (R8 checks are needed only for a new library or reflection).
- The reliability rules in `AGENTS.md` hold. Recording never waits for Drive, the network, a token, a dialog or the first run.
- Spec (Anti-goals): no "full-screen error, or any surface over the record button"; no "text that promises something the app does not do".
- Spec (Main screen): "Top left: the status chip. Top right: switch camera, recordings, settings. Only these: sharing moves to settings." Bottom: "a Video|Audio segmented control, the record button, the latest session's thumbnail (opens the recordings)". While recording: "the red pill with the elapsed time, the 'N pieces safe' chip, and the stop button. Video|Audio and the thumbnail disappear". Switch camera stays.
- Spec (Stopping): "hold the stop button for about 1 s. A ring fills while held … Releasing early does nothing. TalkBack users get an accessibility action 'Stop recording'."
- Spec (Visual language): "dark theme, Material 3. One icon family (Material Symbols Rounded). Colors with one meaning each: 🔴 recording, 🟢 safe, 🟠 attention."
- Tokens (all tasks use these names, `res/values/colors.xml` and `res/values/dimens.xml`):
  - colors: `rec_red #FFF2474B`, `safe_green #FF34C77B`, `attention_orange #FFFFA43A`, `on_glass #FFFFFFFF`, `on_glass_dim #B3FFFFFF`, `glass_bg #73000000`, `glass_stroke #26FFFFFF`, `surface_dark #FF0F0F10`, `surface_card #FF1C1C1E`, `scrim_end #CC000000`;
  - dimens: `record_visual 88dp`, `record_touch 112dp`, `top_button 48dp`, `chip_height 36dp`, `glass_radius 18dp`, `gutter 16dp`, `scrim_height 240dp`.
  - Red appears only for recording, green only for safe, orange only for attention. `colorPrimary` is white (`#FFFFFFFF`, `colorOnPrimary #FF000000`).
- Icons: Material Symbols Rounded vectors from `https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml` (filled variant: `<name>_fill1_24px.xml`), saved as `app/src/main/res/drawable/ms_<name>.xml` (filled: `ms_<name>_fill.xml`), unchanged. Apache 2.0: add one line to `README.md` credits.
- Strings in English (`values/`) and Italian (`values-it/`), every key in both. Exact copy is given in the tasks. No hard-coded "OK"/"Cancel": use `android.R.string.ok` / `android.R.string.cancel`.
- With `saferec.powerGesture=false`: no three-presses row, no first-run step, no chip state, and app code touches `net.ark3us.saferec.guard` only inside `if (BuildConfig.POWER_GESTURE)`.
- Code, comments, commits and docs in English. Commit messages short and clear, no `Co-Authored-By`. Never stage `gradlew`. Run Gradle as `bash ./gradlew`. Never commit secrets or personal data.
- Device tests run on the Galaxy S10+ only, with `ANDROID_SERIAL=<test phone serial>`, checking the model `SM-G975F`. Temporary files go to `./tmp`.

## Review Focus

1. **Nothing disables or covers the record button:** no Drive token, offline, an auth error, permissions denied, the first-run card showing. Expected: the button stays enabled; a tap records, or asks for the camera and microphone first and then records. Owner: Task 8 (the button's listener never checks Drive or the first run; every banner and Snackbar sits above `bottom_controls`), plus Task 12 step 3.
2. **A hold that is not a deliberate stop:** released at 999 ms, the finger sliding off the button, a cancel (the phone locked mid-hold), a second press after completion. Expected: no stop; a completed hold stops once. Owner: Task 4 (`HoldToStopTest`).
3. **Combined status conditions:** offline with no token, offline while Play services ask for interaction, recording while offline, an auth problem with no token. Expected: never "Reconnect Drive" because of a missing network; the most actionable state wins. Owner: Task 3 (`StatusModelTest`), Task 5 (no silent check offline).
4. **Camera double-press setup that did not store "Always":** Android's chooser suggesting another app, the user backing out, choices left over from before. Expected: the app checks after each try and offers the missing one again; stored tries are skipped. Owner: Task 6 (`CameraGestureSetupTest`), plus Task 12 step 6.
5. **The build with the switch off:** expected no three-presses row, no first-run step, no "Three presses off" chip, and no `NoClassDefFoundError`. Owner: Task 9 (`FirstRunStepsTest.noThreePressesStepWithoutTheGuard`), Task 12 step 1.

---

### Task 1: Visual language: tokens, theme and icons

**Files:**
- Modify: `app/src/main/res/values/colors.xml`, `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values/dimens.xml`, `app/src/main/res/drawable/glass_bg.xml`, `glass_circle_bg.xml`, `bottom_scrim.xml`, `recording_pill_bg.xml`, `ic_shortcut_record.xml`, and `ms_*.xml` icons
- Modify: `app/src/main/res/xml/shortcuts.xml` (icon), `README.md` (icon credit)

**Interfaces:**
- Produces: the color and dimen names in Global Constraints; drawables `glass_bg` (rounded `glass_radius`, fill `glass_bg`, 1dp `glass_stroke`), `glass_circle_bg` (oval, same fill and stroke), `bottom_scrim` (vertical gradient `#00000000` → `scrim_end`), `recording_pill_bg` (rounded 18dp, fill `rec_red`), `ic_shortcut_record` (48dp vector: circle `#FF1C1C1E` radius 24, inner circle `rec_red` radius 10, no theme attributes); icons `ms_cameraswitch`, `ms_video_library`, `ms_settings`, `ms_videocam`, `ms_mic`, `ms_graphic_eq`, `ms_photo_library`, `ms_cloud_done`, `ms_cloud_upload`, `ms_cloud_off`, `ms_add_to_drive`, `ms_sync_problem`, `ms_power_settings_new`, `ms_shield_fill`, `ms_arrow_back`, `ms_chevron_right`, `ms_help`, `ms_bolt`, `ms_vibration`, `ms_person_add`, `ms_tune`, `ms_lock`.

- [ ] **Step 1:** Download the icons above into `res/drawable/` with the names given (a loop over the URL pattern in Global Constraints; `ms_shield_fill` comes from `shield_fill1_24px.xml`). Check each file starts with `<vector`.
- [ ] **Step 2:** Replace `colors.xml` with the tokens (keep `white` and `ic_launcher_background`; drop `primary*`, `control_icon*`, `overlay_bg`, `record_red` only after `grep -r` shows no use left, otherwise keep them for Plan 3). Add `dimens.xml`.
- [ ] **Step 3:** In `themes.xml`, `Base.Theme.SafeRec`: `colorPrimary` `#FFFFFFFF`, `colorOnPrimary` `#FF000000`, `colorSurface` `@color/surface_dark`, `colorSurfaceContainer` `@color/surface_card`, `colorError` `@color/attention_orange` (red means recording only). The recordings screens inherit the theme as they are; Plan 3 restyles them. Glass is the dark veil everywhere: views have no backdrop blur (`RenderEffect` blurs a view's own content, not what is behind it), which is the platform limit the spec allows for. Add `Widget.SafeRec.GlassChip` (parent `Widget.Material3.Chip.Assist`, `chipBackgroundColor` `@color/glass_bg`, `chipStrokeColor` `@color/glass_stroke`, `chipStrokeWidth` 1dp, `android:textColor` `@color/on_glass`, `chipMinHeight` `@dimen/chip_height`) and `Widget.SafeRec.GlassButton` (an `ImageButton` style: `top_button` square, background `@drawable/glass_circle_bg`, `tint` `@color/on_glass`, padding 12dp).
- [ ] **Step 4:** Create the drawables. Point the shortcut in `shortcuts.xml` to `@drawable/ic_shortcut_record`.
- [ ] **Step 5:** `bash ./gradlew :app:assembleDebug` passes; `bash ./gradlew :app:lintDebug` shows no new error.
- [ ] **Step 6:** Commit: `git add app/src/main/res README.md && git commit -m "Add the viewfinder's colors, glass styles and icons"`

### Task 2: What the service tells the UI

**Files:**
- Modify: `app/src/main/java/net/ark3us/saferec/data/ServiceState.java`
- Modify: `app/src/main/java/net/ark3us/saferec/services/SafeRecService.java` (session start ~408, camera lost ~500, `updateNotification()`), `app/src/main/java/net/ark3us/saferec/net/GoogleDriveFileUploader.java` (nothing: `reportError` sets the flag)

**Interfaces:**
- Produces, on `ServiceState`:
  - `MutableLiveData<Long> getSessionStart()`: session id as epoch millis while a session exists, `0L` otherwise;
  - `MutableLiveData<Boolean> getAudioOnly()`: the running recording records no video (an audio-only start, or the camera lost);
  - `MutableLiveData<Integer> getPendingFiles()`: files in the outbox, as the service last counted them;
  - `MutableLiveData<Boolean> getDriveAuthProblem()`: sticky; `reportError(ERROR_AUTH)` sets it `true`;
  - `void setSession(long startMillis, boolean audioOnly)`, `void setAudioOnly(boolean audioOnly)`, `void setPendingFiles(int count)`, `void setDriveAuthProblem(boolean problem)` (all `postValue`).

- [ ] **Step 1:** Add the four LiveData (initial values `0L`, `false`, `0`, `false`) and setters. `reportError(error)` also posts `true` to `driveAuthProblem` when `error` is `ERROR_AUTH`.
- [ ] **Step 2:** In `SafeRecService`: when a new session id is created, `setSession(Long.parseLong(currentSessionId), !includeVideo)`; on every `startRecording` (camera switch too) `setAudioOnly(!includeVideo)`; where the camera is lost (`ERROR_CAMERA`), `setAudioOnly(true)`; when `CMD_STOP` clears the session, `setSession(0, false)`; at the end of `updateNotification()`, `setPendingFiles(pendingFiles())`.
- [ ] **Step 3:** `bash ./gradlew :app:testDebugUnitTest` passes (no behavior of the engine changes).
- [ ] **Step 4:** Commit: `git commit -m "Publish session start, audio only and waiting files to the UI"`

### Task 3: The status chip's rules

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/ui/StatusModel.java`
- Test: `app/src/test/java/net/ark3us/saferec/ui/StatusModelTest.java`
- Modify: `values/strings.xml`, `values-it/strings.xml`

**Interfaces:**
- Produces: `enum StatusModel.Kind { HIDDEN, DRIVE_OK, WAITING, OFFLINE, CONNECT_DRIVE, RECONNECT_DRIVE, THREE_PRESSES_OFF }`; `static Kind StatusModel.kindOf(boolean recording, boolean hasToken, boolean authProblem, boolean online, int pendingFiles, boolean threePressesOff)`; `static boolean StatusModel.isAttention(Kind)` (true for `RECONNECT_DRIVE`, `THREE_PRESSES_OFF`).

The rule, first match wins:
```
!hasToken                                → CONNECT_DRIVE
authProblem                              → RECONNECT_DRIVE
threePressesOff && !recording            → THREE_PRESSES_OFF
pendingFiles > 0 && (!online || !recording) → WAITING
!online                                  → OFFLINE
otherwise                                → recording ? HIDDEN : DRIVE_OK
```
While recording online, the safe chip shows progress, so a waiting count would be noise.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void noTokenAsksToConnectEvenOffline() { assertEquals(CONNECT_DRIVE, kindOf(false, false, false, false, 3, true)); }
@Test public void authProblemAsksToReconnect() { assertEquals(RECONNECT_DRIVE, kindOf(true, true, true, true, 0, false)); }
@Test public void threePressesOffShowsWhenIdle() { assertEquals(THREE_PRESSES_OFF, kindOf(false, true, false, true, 2, true)); }
@Test public void threePressesOffHidesWhileRecording() { assertEquals(HIDDEN, kindOf(true, true, false, true, 0, true)); }
@Test public void waitingWhenIdleWithFiles() { assertEquals(WAITING, kindOf(false, true, false, true, 2, false)); }
@Test public void waitingWhileRecordingOffline() { assertEquals(WAITING, kindOf(true, true, false, false, 1, false)); }
@Test public void noWaitingNoiseWhileRecordingOnline() { assertEquals(HIDDEN, kindOf(true, true, false, true, 4, false)); }
@Test public void offlineWithNothingWaiting() { assertEquals(OFFLINE, kindOf(false, true, false, false, 0, false)); }
@Test public void allSafe() { assertEquals(DRIVE_OK, kindOf(false, true, false, true, 0, false)); }
@Test public void onlyDriveAndGuardProblemsAreAttention() {
    for (Kind k : Kind.values()) assertEquals(k == RECONNECT_DRIVE || k == THREE_PRESSES_OFF, isAttention(k));
}
```

- [ ] **Step 2:** Run `bash ./gradlew :app:testDebugUnitTest --tests '*StatusModelTest'`. Expected: FAIL (class missing).
- [ ] **Step 3:** Implement `StatusModel` (pure Java, no Android import).
- [ ] **Step 4:** Add the strings:

| key | English | Italian |
|---|---|---|
| `status_drive_ok` | Drive connected | Drive collegato |
| `status_waiting` (plurals one/other) | %d piece waiting on this phone / %d pieces waiting on this phone | %d pezzo in attesa sul telefono / %d pezzi in attesa sul telefono |
| `status_offline` | Offline | Senza rete |
| `status_connect_drive` | Connect Drive | Collega Drive |
| `status_reconnect_drive` | Reconnect Drive | Ricollega Drive |
| `status_three_presses_off` | Three presses off | Tre pressioni spente |
| `status_waiting_explained` | Recordings wait on this phone and go to Drive as soon as there is a network. | Le registrazioni aspettano sul telefono e vanno su Drive appena c\'è rete. |
| `status_audio_only` | Audio only | Solo audio |

- [ ] **Step 5:** Run the tests. Expected: PASS.
- [ ] **Step 6:** Commit: `git commit -m "Add the status chip's rules"`

### Task 4: The record button and hold to stop

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/ui/HoldToStop.java`, `app/src/main/java/net/ark3us/saferec/ui/RecordButton.java`
- Test: `app/src/test/java/net/ark3us/saferec/ui/HoldToStopTest.java`
- Modify: strings (EN/IT)

**Interfaces:**
- Produces:
  - `HoldToStop` (pure): `static final long HOLD_MS = 1000`; `void down(long nowMs)`; `void cancel()`; `float progress(long nowMs)` (0 when not held, else `min(1, (now - downAt) / HOLD_MS)`); `boolean consumeStop(long nowMs)` (true once per hold, when held for `HOLD_MS` or more).
  - `RecordButton extends View`: `interface Listener { void onStartRequested(); void onStopConfirmed(); void onStopHintNeeded(); }`; `void setListener(Listener)`; `void setRecording(boolean recording)`; `void setBusy(boolean busy)` (drawn at 40% alpha, ignores touches, while a command is on its way).
- Consumes: dimens `record_visual`, `record_touch`, colors `rec_red`, `on_glass`.

- [ ] **Step 1: Write the failing tests**

```java
@Test public void releasedEarlyNeverStops() { h.down(0); assertFalse(h.consumeStop(999)); h.cancel(); assertFalse(h.consumeStop(5000)); }
@Test public void heldOneSecondStopsOnce() { h.down(0); assertTrue(h.consumeStop(1000)); assertFalse(h.consumeStop(1500)); }
@Test public void progressFillsAndResets() { h.down(100); assertEquals(0.5f, h.progress(600), 1e-6); h.cancel(); assertEquals(0f, h.progress(600), 0f); }
@Test public void aNewHoldStartsFromZero() { h.down(0); h.consumeStop(1000); h.cancel(); h.down(2000); assertFalse(h.consumeStop(2999)); assertTrue(h.consumeStop(3000)); }
```

- [ ] **Step 2:** Run `--tests '*HoldToStopTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement `HoldToStop`. Run the tests. Expected: PASS.
- [ ] **Step 4:** Implement `RecordButton`:
  - The view measures `record_touch` square and draws centered at `record_visual`: a 4dp `on_glass` ring; inside, ready = a `rec_red` circle (diameter 70% of the visual), recording = a `rec_red` rounded square (40%, corner 6dp). While held, a 4dp `on_glass` arc over the ring from 12 o'clock, sweep `360 * progress`.
  - Ready: `ACTION_UP` inside → `onStartRequested()` (with `performClick()`).
  - Recording: `ACTION_DOWN` → `down(now)` and redraw each frame (`postInvalidateOnAnimation`) while held; when `consumeStop(now)` → `onStopConfirmed()`. `ACTION_UP` before 1 s or `ACTION_CANCEL` or the pointer leaving the view's bounds → `cancel()`, the arc animates back in 150 ms, and on `ACTION_UP` → `onStopHintNeeded()`. A second pointer cancels.
  - `onDetachedFromWindow()` and `onWindowFocusChanged(false)` cancel the hold.
  - Accessibility: content description `record_start` when ready, `record_recording_description` when recording; while recording add `ViewCompat.addAccessibilityAction(this, getString(R.string.record_stop_action), (v, a) -> { listener.onStopConfirmed(); return true; })` and remove it when ready. A TalkBack double tap while recording calls `onStopHintNeeded()`, never stops.
- [ ] **Step 5:** Strings:

| key | English | Italian |
|---|---|---|
| `record_start` | Start recording | Avvia la registrazione |
| `record_recording_description` | Recording. Hold to stop | Registrazione in corso. Tieni premuto per fermare |
| `record_hold_to_stop` | Hold to stop | Tieni premuto per fermare |
| `record_stop_action` | Stop recording | Ferma la registrazione |

- [ ] **Step 6:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes. Commit: `git commit -m "Add the record button with hold to stop"`

### Task 5: Drive connection without surprises

**Files:**
- Modify: `app/src/main/java/net/ark3us/saferec/PermissionsManager.java`
- Create: `app/src/main/java/net/ark3us/saferec/net/DriveConnection.java`
- Modify: `app/src/main/java/net/ark3us/saferec/net/GoogleDriveClient.java` (one method), `app/src/main/java/net/ark3us/saferec/misc/Settings.java` (one key)

**Interfaces:**
- Produces:
  - `PermissionsManager.requestRecordingPermissions(@Nullable Runnable onResult)`: asks for `CAMERA`, `RECORD_AUDIO` and, on API 33+, `POST_NOTIFICATIONS`; runs `onResult` after the answer, or at once when all are granted. Never asks for Drive.
  - `static boolean PermissionsManager.hasRecordingPermissions(Context)` (camera and microphone; moved from `MainActivity`).
  - `interface PermissionsManager.DriveCallback { void onResult(@Nullable String accessToken, boolean needsUser); }`; `void connectDrive(boolean interactive, DriveCallback)`: `interactive=false` never launches a UI and reports `needsUser=true` when Play services want a resolution; `interactive=true` launches it.
  - `requestAllPermissions` and `Callback` stay until Task 8, rebuilt as a thin wrapper (`requestRecordingPermissions`, then `connectDrive(true, …)`), so the build stays green. Task 8 deletes them.
  - `DriveConnection.Result accept(Context, String token)` (worker thread): `GoogleDriveClient.checkAuthentication()`; `VALID` or `UNKNOWN` → `Settings.setAccessToken`, `ServiceState.setDriveAuthProblem(false)`, and on `VALID` `Settings.setDriveAccount(client.accountEmail())` (ignoring its failure); `INVALID` → `ServiceState.setDriveAuthProblem(true)`, token not stored. `enum Result { VALID, UNKNOWN, INVALID }`.
  - `@Nullable String GoogleDriveClient.accountEmail() throws IOException`: `about().get().setFields("user(emailAddress)")`.
  - `Settings.getDriveAccount(Context)` / `setDriveAccount(Context, @Nullable String)`, key `driveAccount`, default `null`. The email stays on the phone.
- Rule (used by Task 8): the silent check `connectDrive(false, …)` runs only when a token is stored **and** the default network is validated. Play services also ask for interaction when they cannot reach Google, so a check made offline would show "Reconnect Drive" for nothing (`AGENTS.md` rule 3).

- [ ] **Step 1:** Split `PermissionsManager` as above, keeping both `registerForActivityResult` calls in the constructor.
- [ ] **Step 2:** Add `DriveConnection`, `accountEmail()` and the setting.
- [ ] **Step 3:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes; the app still asks for Drive at launch, through the wrapper, until Task 8.
- [ ] **Step 4:** Commit: `git commit -m "Split recording permissions from the Drive connection"`

### Task 6: A camera double-press setup that checks itself

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/ui/CameraGestureSetup.java`
- Modify: `app/src/main/java/net/ark3us/saferec/CameraGesture.java`
- Test: `app/src/test/java/net/ark3us/saferec/ui/CameraGestureSetupTest.java`
- Modify: strings (EN/IT)

**Interfaces:**
- Produces:
  - `static boolean CameraGesture.isDefaultFor(Context, Intent)`: `resolveActivity(intent, MATCH_DEFAULT_ONLY)` names the alias `net.ark3us.saferec.CameraGestureAlias`.
  - `static boolean[] CameraGesture.storedTries(Context)`: `isDefaultFor` for each of `tryIntents()`.
  - `static int CameraGestureSetup.nextTry(boolean[] stored)`: the index of the first `false`, or `-1`.
  - `static void CameraGestureSetup.begin(Activity)`: when `nextTry(storedTries) == -1` nothing to do; otherwise the explanation dialog, then the try.
  - `static void CameraGestureSetup.onResume(Activity)`: when a try is pending (a static field, as `cameraGestureNextTry` today), checks it: stored → next try's dialog, or `camera_gesture_ready` when none is left; not stored → `camera_gesture_not_saved` with "Try again". `MainActivity` calls it in `onResume()` from this task on (a try that reaches SafeRec lands there), and `SettingsActivity` does too from Task 7.
  - `CameraGesture.isAvailable()` logs its fail-open (`Log.w`).

- [ ] **Step 1: Write the failing tests**

```java
@Test public void firstMissingTryComesNext() { assertEquals(0, nextTry(new boolean[]{false, false})); assertEquals(1, nextTry(new boolean[]{true, false})); }
@Test public void storedTriesAreSkipped() { assertEquals(-1, nextTry(new boolean[]{true, true})); }
@Test public void aLockedOnlyGapIsFoundToo() { assertEquals(0, nextTry(new boolean[]{false, true})); }
```

- [ ] **Step 2:** Run `--tests '*CameraGestureSetupTest'`. Expected: FAIL. Implement, run. Expected: PASS.
- [ ] **Step 3:** Move the dialog and try logic out of `MainActivity` (`showCameraGestureDialog`, `tryCameraGesture`, `cameraGestureNextTry`) into `CameraGestureSetup`.
- [ ] **Step 4:** Strings (replace `camera_gesture_tries` and `camera_gesture_try_locked`):

| key | English | Italian |
|---|---|---|
| `camera_gesture_tries` | Two tries: one for the unlocked phone, one for the locked phone. Each try starts a short recording: hold the stop button to stop it. | Due prove: una a telefono sbloccato, una a telefono bloccato. Ogni prova avvia una breve registrazione: tieni premuto il pulsante di stop per fermarla. |
| `camera_gesture_try_locked` | Saved for the unlocked phone. Now the locked phone: choose SafeRec, then Always. | Salvato per il telefono sbloccato. Ora il telefono bloccato: scegli SafeRec, poi Sempre. |
| `camera_gesture_not_saved` | Android did not save SafeRec as the default. If it suggested another app, you tapped SafeRec just once: try again, and now choose SafeRec, then Always. | Android non ha salvato SafeRec come predefinita. Se proponeva un\'altra app, hai toccato SafeRec una volta sola: riprova, e ora scegli SafeRec, poi Sempre. |
| `camera_gesture_try_again` | Try again | Riprova |
| `camera_gesture_ready` | Done: a double press of the power key now records, locked or not. Android vibrates once for the gesture, then SafeRec twice. | Fatto: ora una doppia pressione del tasto di accensione registra, bloccato o no. Android vibra una volta per il gesto, poi SafeRec due volte. |

- [ ] **Step 5:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes. Commit: `git commit -m "Check the camera double-press setup after each try"`

### Task 7: The settings screen

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/SettingsActivity.java`, `app/src/main/java/net/ark3us/saferec/ui/SettingsRows.java`, `app/src/main/java/net/ark3us/saferec/ui/GuardSetup.java`, `app/src/main/res/layout/activity_settings.xml`, `app/src/main/res/layout/item_setting_header.xml`
- Modify: `app/src/main/res/layout/item_setting_row.xml` (add `setting_summary` under the title, `setting_icon` at the start), `AndroidManifest.xml` (activity, theme `Theme.SafeRec.Surface`, not exported), `MainActivity.java` (`btn_settings`), strings (EN/IT)
- Delete: `app/src/main/res/layout/bottom_sheet_settings.xml`

**Interfaces:**
- Consumes: Task 5 `connectDrive`, `DriveConnection`, `Settings.getDriveAccount`; Task 6 `CameraGestureSetup`.
- Produces:
  - `SettingsRows.header(ViewGroup, String)`, `SettingsRows.row(ViewGroup, int iconRes, String title, @Nullable String summary, View.OnClickListener)`, `SettingsRows.toggle(ViewGroup, int iconRes, String title, @Nullable String summary, boolean checked, CompoundButton.OnCheckedChangeListener)`.
  - `static void GuardSetup.openNextStep(Activity)`: `NEEDS_OVERLAY` → `Guard.overlaySettingsIntent`; `NEEDS_BATTERY_EXEMPTION` → the battery dialog below; any other status → nothing. It references `Guard`: callers wrap it in `if (BuildConfig.POWER_GESTURE)`. Task 9 reuses it.
  - `MainActivity.EXTRA_SHOW_GUIDE = "net.ark3us.saferec.extra.SHOW_GUIDE"`, handled in `onCreate` and `onNewIntent`. Until Task 9 it shows today's tutorial; Task 9 shows the first-run card instead.

`render()` clears the container and adds every group; `onResume()` calls it, so every status is read fresh (Finding: the guard row went stale). Groups, in order:

1. **Quick start** (`ms_bolt`)
   - *Three presses of the power key* (`BuildConfig.POWER_GESTURE` only): a toggle checked = `Guard.isEnabled`. Toggling on → `Guard.setEnabled(true)` then `GuardSetup.openNextStep(this)`; off → `Guard.setEnabled(false)`. A tap on the row with a step missing calls `openNextStep` and never turns the guard off; with no step missing it flips the toggle. Summary by status: `DISABLED` `three_presses_summary_off`; `NEEDS_OVERLAY` `three_presses_step_overlay`; `NEEDS_BATTERY_EXEMPTION` `three_presses_step_battery`; `STOPPED` `three_presses_starting` (and `Guard.ensureRunning`, then `render()` again after 1 s); `ACTIVE` `three_presses_on`. Summaries of a missing step are tinted `attention_orange`.
   - The battery step: a dialog `three_presses_battery_help` with "Open settings" → `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the package (the app's own page, where Android 12+ shows "App battery usage → Unrestricted"); below API 31 → `Guard.batterySettingsIntent()`. Task 12 verifies on the S10+ which choice makes `isIgnoringBatteryOptimizations()` true and corrects the text if needed.
   - *Double press of the power key opens SafeRec* (only when `CameraGesture.isAvailable`): toggle = `CameraGesture.isEnabled`; on → `setEnabled(true)` then `CameraGestureSetup.begin(this)`; summary `camera_gesture_summary_ready` when both tries are stored, `camera_gesture_summary_setup` (orange, tap → `begin`) when enabled but not, `camera_gesture_summary_off` when off.
   - *Quick start records* (Video / Audio only) and *Quick start camera* (Back / Front): today's dialogs.
   - *Vibrations*: toggle, summary `vibrations_summary`.
   - *Quick settings tile*: summary `tile_summary`; tap → on API 33+ `StatusBarManager.requestAddTileService(new ComponentName(this, SafeRecTileService.class), getString(R.string.tile_name_start), Icon.createWithResource(this, R.drawable.ic_stat_shield_record), getMainExecutor(), result -> …)`: `TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED` or `…_TILE_ADDED` → Toast `tile_added`; an error code → the dialog `tile_steps`; below API 33 → the dialog `tile_steps`.
2. **Evidence** (`ms_shield_fill`): *Certified time* toggle (`Settings.isTimestampingEnabled`), summary `timestamping_summary`.
3. **Video** (`ms_videocam`): *Quality* (today's dialog), *Camera* (Back / Front, `Settings.getUseFrontCamera`).
4. **Google Drive** (`ms_cloud_done`): *Google Drive* row: summary `drive_connected_as` with the account, or `drive_connected` without one, `drive_not_connected` (tap → `connectDrive(true)` then `DriveConnection.accept`), `drive_reconnect` (orange, same tap) when `getDriveAuthProblem()`. *Share with a person* (`ms_person_add`): today's `shareRootFolder()` flow, moved here.
5. **Advanced** (`ms_tune`): *Piece size*: summary `piece_size_auto` or `piece_size_mb`; today's two dialogs, titled `piece_size_title` and `piece_size_dialog`, with the choices `piece_size_auto` and `piece_size_custom`, and `android.R.string.ok`/`cancel`.
6. **Help** (`ms_help`): *Guide*: starts `MainActivity` with `EXTRA_SHOW_GUIDE` and `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`.

Strings (keep `power_gesture`, `camera_gesture`, `quick_start_*`, `vibrations`, `video_quality`, `quality_*`, `share_*` keys):

| key | English | Italian |
|---|---|---|
| `settings_group_quick_start` | Quick start | Avvio rapido |
| `settings_group_evidence` | Evidence | Prove |
| `settings_group_video` | Video | Video |
| `settings_group_drive` | Google Drive | Google Drive |
| `settings_group_advanced` | Advanced | Avanzate |
| `settings_group_help` | Help | Aiuto |
| `three_presses_summary_off` | Record with the phone locked: press the power key three times. | Registra a telefono bloccato: premi tre volte il tasto di accensione. |
| `three_presses_step_overlay` | Step 1 of 2: allow \"Display over other apps\" | Passo 1 di 2: consenti \"Mostra sopra altre app\" |
| `three_presses_step_battery` | Step 2 of 2: set battery use to \"Unrestricted\" | Passo 2 di 2: imposta l\'uso della batteria su \"Senza limitazioni\" |
| `three_presses_starting` | Starting… | Avvio… |
| `three_presses_on` | On: press the power key three times, even with the phone locked | Attivo: premi tre volte il tasto di accensione, anche a telefono bloccato |
| `three_presses_battery_help` | Android may close SafeRec to save battery, and the three presses would stop working. On the next page, open \"App battery usage\" and choose \"Unrestricted\". | Android può chiudere SafeRec per risparmiare batteria, e le tre pressioni smetterebbero di funzionare. Nella pagina seguente apri \"Utilizzo batteria app\" e scegli \"Senza limitazioni\". |
| `open_settings` | Open settings | Apri impostazioni |
| `camera_gesture_summary_off` | Off: the double press opens the camera | Spento: la doppia pressione apre la fotocamera |
| `camera_gesture_summary_setup` | Not set up yet: tap to try it | Non ancora pronto: tocca per provarlo |
| `camera_gesture_summary_ready` | On, locked or not | Attivo, bloccato o no |
| `vibrations_summary` | Two short pulses when recording starts, one long when it stops | Due impulsi brevi quando parte, uno lungo quando si ferma |
| `tile_title` | Quick settings tile | Riquadro nelle impostazioni rapide |
| `tile_summary` | Starts recording from the lock screen, without unlocking. Tap to add it. | Avvia la registrazione dalla schermata di blocco, senza sbloccare. Tocca per aggiungerlo. |
| `tile_added` | The tile is in quick settings | Il riquadro è nelle impostazioni rapide |
| `tile_steps` | Swipe down twice from the top of the screen, tap the pencil, find \"Start SafeRec\" and drag it among your tiles. | Scorri due volte dall\'alto, tocca la matita, trova \"Avvia SafeRec\" e trascinalo tra i tuoi riquadri. |
| `timestamping_title` | Certified time | Ora certificata |
| `timestamping_summary` | Each piece gets a time stamp from Sectigo, an independent authority: proof it existed, unchanged, at that time. Only the file\'s fingerprint is sent. | Ogni pezzo riceve una marca temporale da Sectigo, un\'autorità indipendente: la prova che esisteva, identico, in quel momento. Viene inviata solo l\'impronta del file. |
| `video_camera` | Camera | Fotocamera |
| `drive_title` | Google Drive | Google Drive |
| `drive_connected_as` | Connected as %s | Collegato come %s |
| `drive_connected` | Connected | Collegato |
| `drive_not_connected` | Not connected: recordings wait on this phone. Tap to connect. | Non collegato: le registrazioni aspettano sul telefono. Tocca per collegarlo. |
| `drive_reconnect` | Needs reconnecting: tap to sign in again | Va ricollegato: tocca per accedere di nuovo |
| `piece_size_title` | Piece size | Dimensione dei pezzi |
| `piece_size_auto` | Auto: a new piece every 10 seconds | Automatica: un nuovo pezzo ogni 10 secondi |
| `piece_size_mb` | %d MB per piece | %d MB per pezzo |
| `piece_size_dialog` | Size of each piece (MB) | Dimensione di ogni pezzo (MB) |
| `piece_size_custom` | Choose a size… | Scegli una dimensione… |
| `guide_title` | Guide | Guida |

- [ ] **Step 1:** Write the layouts and `SettingsRows`.
- [ ] **Step 2:** Write `SettingsActivity` (a `MaterialToolbar` with `ms_arrow_back`, a `ScrollView` over a vertical container). Keep the guard's code inside `if (BuildConfig.POWER_GESTURE)`.
- [ ] **Step 3:** Point `btn_settings` to it, and call `CameraGestureSetup.onResume(this)` in its `onResume()`. Delete the old sheet and its code from `MainActivity`, and `btn_share_root` from the top bar (sharing now lives in settings).
- [ ] **Step 4:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` and `bash ./gradlew :app:assembleDebug -Psaferec.powerGesture=false` pass.
- [ ] **Step 5:** Commit: `git commit -m "Move settings to a screen with plain-language groups"`

### Task 8: The viewfinder

**Files:**
- Rewrite: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/net/ark3us/saferec/MainActivity.java`
- Modify: strings (EN/IT)

**Interfaces:**
- Consumes: Task 1 styles and icons, Task 2 `ServiceState` getters, Task 3 `StatusModel`, Task 4 `RecordButton`, Task 5 `PermissionsManager`/`DriveConnection`, Task 7 `SettingsActivity`.
- Produces: the view ids below (Task 9 adds `first_run_card`).

Layout (`FrameLayout` root `main`, edge to edge; insets padding on `top_bar` and `bottom_controls` only):
```
video_preview (TextureView, full screen)          audio_only_surface (surface_dark, centered ms_graphic_eq 48dp + audio_only_label)
top_bar: status_chip (GlassChip) ······ btn_switch_camera  btn_recordings  btn_settings (GlassButton, ms_*)
permissions_banner (glass, under top_bar: text + "Allow" button), gone by default
recording_info (centered under top_bar): recording_pill (recording_pill_bg: white dot + Chronometer recording_time, tabular digits)
                                          safe_chip (GlassChip, ms_shield_fill tinted safe_green)
first_run_card (include, Task 9), anchored above bottom_controls
bottom_scrim (bottom_scrim drawable, scrim_height, bottom)
bottom_controls: mode_toggle (MaterialButtonToggleGroup: mode_video ms_videocam, mode_audio ms_mic; single selection, required)
                 btn_record (RecordButton, centered)   btn_gallery (56dp, glass_bg, ms_photo_library)
                 hold_hint ("Hold to stop", under btn_record, visible while recording)
```

Behavior:
- **Ready:** chip, three top buttons, `mode_toggle`, `btn_record` ready, `btn_gallery`. **Recording:** `recording_pill` (Chronometer base = `SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sessionStart)`), `safe_chip` with plural `notif_safe_chunks`, `btn_switch_camera`, `btn_record` recording, `hold_hint`; `mode_toggle`, `btn_gallery`, `btn_recordings` and `btn_settings` gone; the chip only when not `HIDDEN`.
- **Audio only** (`mode_audio` selected while ready, or `getAudioOnly()` true while recording): `audio_only_surface` visible with `status_audio_only`, preview stopped; `btn_switch_camera` hidden while recording audio only.
- **Status chip:** `StatusModel.kindOf(recording, token != null, getDriveAuthProblem(), online, pendingFiles, threePressesOff)`, where `pendingFiles` is `getPendingFiles()` or, on `onResume()`, a direct count of `GoogleDriveFileUploader.outboxDir(this).list()`, and `threePressesOff = BuildConfig.POWER_GESTURE && Guard.isEnabled(this) && Guard.status(this) != ACTIVE`. Icon and tint per kind: `DRIVE_OK` `ms_cloud_done` `safe_green`; `WAITING` `ms_cloud_upload` (online) / `ms_cloud_off` (offline) `on_glass`; `OFFLINE` `ms_cloud_off` `on_glass`; `CONNECT_DRIVE` `ms_add_to_drive` `on_glass`; `RECONNECT_DRIVE` `ms_sync_problem` `attention_orange`; `THREE_PRESSES_OFF` `ms_power_settings_new` `attention_orange`. `GuardService` reports running only once it has started, so the chip renders again 1 s after `ensureRunning()`, or it would flash "Three presses off" at every launch. Taps: connect kinds → `connectDrive(true, …)` then `DriveConnection.accept` on the executor, then `CMD_UPLOAD_PENDING` on `VALID`, and on failure a Snackbar `drive_connect_failed`; `THREE_PRESSES_OFF` → `SettingsActivity`; `WAITING`/`OFFLINE` → Snackbar `status_waiting_explained`; `DRIVE_OK` → nothing.
- **Network:** `ConnectivityManager.registerDefaultNetworkCallback` in `onStart()`, unregistered in `onStop()`; online = the default network has `NET_CAPABILITY_VALIDATED`. Each change re-renders the chip; going online with a token runs the silent check once.
- **On `onResume()`:** silent Drive check (Task 5 rule); `needsUser=true` → `setDriveAuthProblem(true)`; a token → `DriveConnection.accept` on the executor, then `CMD_UPLOAD_PENDING` on `VALID`.
- **Launch:** `checkPermissionsAndDrive()` goes, and with it the Drive request at launch. `onCreate` shows today's tutorial while `!Settings.isTutorialShown` (Task 9 replaces it), and posts `handleLaunchCommand()` when camera and microphone are granted, otherwise after `requestRecordingPermissions(...)`, as today's `launchIfPermitted`.
- **Record button:** `onStartRequested()` → with camera and microphone granted, `CMD_START` (as today); otherwise `requestRecordingPermissions(...)` and then `CMD_START` if granted, else show `permissions_banner`. `onStopConfirmed()` → `CMD_STOP`. `onStopHintNeeded()` → `hold_hint` pulses (scale 1 → 1.15 → 1, 300 ms) and is announced (`announceForAccessibility`). While a start or stop is on its way, `setBusy(true)` until the status changes.
- **Errors:** `ERROR_CAMERA` → Snackbar `error_camera_lost` anchored to `bottom_controls`; `ERROR_RECORDING` → Snackbar `error_recording_failed` anchored likewise; `ERROR_AUTH` only drives the chip. No other error UI. Every Snackbar uses `setAnchorView(R.id.bottom_controls)`.
- **Kept as they are:** preview lifecycle, `handleLaunchCommand()`, the guard's `ensureRunning()` on resume, `startSafeRecService()`/`startServiceCommand()`, `btn_switch_camera` behavior, `Settings.setOnlyAudio` for the mode.
- **Removed:** `error_container`, `btn_toggle_camera`, `PermissionsManager.requestAllPermissions` and `Callback` (Task 5's wrapper). The settings sheet and `btn_share_root` are gone since Task 7.
- `btn_recordings` and `btn_gallery` open `RecordingsActivity`; `btn_settings` opens `SettingsActivity`.

Strings:

| key | English | Italian |
|---|---|---|
| `mode_video` | Video | Video |
| `mode_audio` | Audio | Audio |
| `open_recordings` | Recordings | Registrazioni |
| `permissions_banner` | Camera and microphone are needed to record. | Per registrare servono fotocamera e microfono. |
| `permissions_allow` | Allow | Consenti |
| `drive_connect_failed` | Could not connect Google Drive. Recordings stay on this phone. | Impossibile collegare Google Drive. Le registrazioni restano sul telefono. |

- [ ] **Step 1:** Write the layout with the ids above.
- [ ] **Step 2:** Rewire `MainActivity`. The permanently-denied case: when `shouldShowRequestPermissionRationale` is false after a denial, "Allow" opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the package.
- [ ] **Step 3:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes. Install on the S10+ and check by eye: ready, recording (start from the button), hold 1 s stops, a short tap shows the hint, airplane mode shows the waiting/offline chip, nothing covers the button.
- [ ] **Step 4:** Commit: `git commit -m "Make the main screen a viewfinder that never blocks recording"`

### Task 9: First run that never delays a recording

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/ui/FirstRunSteps.java`, `app/src/main/java/net/ark3us/saferec/ui/FirstRun.java`, `app/src/main/res/layout/card_first_run.xml`
- Test: `app/src/test/java/net/ark3us/saferec/ui/FirstRunStepsTest.java`
- Modify: `MainActivity.java`, `activity_main.xml` (include the card), `misc/Settings.java`, strings (EN/IT)

**Interfaces:**
- Produces:
  - `enum FirstRunSteps.Step { ABOUT, PERMISSIONS, THREE_PRESSES }`; `static List<Step> FirstRunSteps.steps(boolean powerGesture, boolean permissionsGranted)`: `ABOUT`, then `PERMISSIONS` unless granted, then `THREE_PRESSES` when `powerGesture`.
  - `FirstRun(MainActivity, View card, PermissionsManager)`: `void show()` (from the first step), `void hide()`, `boolean isShowing()`.
  - `Settings.isFirstRunDone(Context)` / `setFirstRunDone(Context, boolean)`, key `firstRunDone`, default: the old `tutorialShown` value (so updated users never see it again).
- The card sits above `bottom_controls` (glass, `gutter` margins): title, text, a primary button, "Skip", step dots. It never covers `btn_record`, and the record button works while it shows. `MainActivity` shows it when `!isFirstRunDone`, and on `EXTRA_SHOW_GUIDE`. Finishing or skipping the last step sets `firstRunDone`.

| step | title (EN / IT) | text (EN / IT) | primary button |
|---|---|---|---|
| `ABOUT` | Evidence that survives / Prove che sopravvivono | SafeRec records video and sound and sends it to your Google Drive in 10-second pieces, so what you recorded is safe even if the phone is broken or taken. It records without a network too: pieces wait on the phone. / SafeRec registra video e audio e li manda sul tuo Google Drive a pezzi di 10 secondi: ciò che hai registrato è al sicuro anche se il telefono viene rotto o preso. Registra anche senza rete: i pezzi aspettano sul telefono. | Next / Avanti |
| `PERMISSIONS` | Camera and microphone / Fotocamera e microfono | SafeRec needs them to record, and notifications to show that it is recording. / Servono per registrare, e le notifiche per mostrare che sta registrando. | Allow / Consenti → `requestRecordingPermissions`, then the next step |
| `THREE_PRESSES` | Record without unlocking / Registra senza sbloccare | Press the power key three times, even with the phone locked in a pocket. Two short vibrations confirm. It needs two permissions, asked next. / Premi tre volte il tasto di accensione, anche col telefono bloccato in tasca. Due vibrazioni brevi confermano. Servono due permessi, chiesti subito dopo. | Turn on / Attiva → `Guard.setEnabled(true)`, then `GuardSetup.openNextStep` (Task 7) |

Keys: `first_run_about_title`, `first_run_about_text`, `first_run_permissions_title`, `first_run_permissions_text`, `first_run_three_presses_title`, `first_run_three_presses_text`, `first_run_next`, `first_run_turn_on`, `first_run_skip` (Skip / Salta), `first_run_done` (Done / Fatto).

- [ ] **Step 1: Write the failing tests**

```java
@Test public void allThreeStepsOnAFreshPhone() { assertEquals(List.of(ABOUT, PERMISSIONS, THREE_PRESSES), steps(true, false)); }
@Test public void noThreePressesStepWithoutTheGuard() { assertEquals(List.of(ABOUT, PERMISSIONS), steps(false, false)); }
@Test public void grantedPermissionsAreSkipped() { assertEquals(List.of(ABOUT, THREE_PRESSES), steps(true, true)); }
```

- [ ] **Step 2:** Run `--tests '*FirstRunStepsTest'`. Expected: FAIL. Implement `FirstRunSteps` with an `ArrayList`: `List.of` compiles but needs API 30 at runtime, and minSdk is 26 (the tests may use it, they run on the JVM). Expected: PASS.
- [ ] **Step 3:** Implement the card and `FirstRun`; call it from `MainActivity`, on first launch and on `EXTRA_SHOW_GUIDE`. Remove `showTutorialOverlay` from `MainActivity` (the recordings screen keeps `TutorialOverlayView` until Plan 3).
- [ ] **Step 4:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug` passes. Commit: `git commit -m "Replace the tutorial with a first run that never delays recording"`

### Task 10: Open items from Plan 1

**Files:**
- Modify: `QuickStartActivity.java`, `services/Haptics.java`, `services/StartPolicy.java`, `services/SafeRecService.java`, `services/NotificationHelper.java`, `guard/.../Guard.java`, `guard/src/main/res/values*/strings.xml`, `app/build.gradle.kts`
- Test: `app/src/test/java/net/ark3us/saferec/services/StartPolicyTest.java`, `guard/src/test/java/net/ark3us/saferec/guard/TriggerRuleTest.java`

**Interfaces:**
- Produces: `public final class Haptics` with public `started`, `failed`, `stopped`; `static final long StartPolicy.REPLAY_GAP_MS = 1000`; `static boolean StartPolicy.replaysStarted(long nowMs, long lastStartedPulseMs)` (`lastStartedPulseMs == 0 || nowMs - lastStartedPulseMs >= REPLAY_GAP_MS`).

- [ ] **Step 1: Write the failing tests**

```java
// StartPolicyTest
@Test public void replayAfterTheGap() { assertTrue(StartPolicy.replaysStarted(5000, 4000)); }
@Test public void noReplayRightAfterAStart() { assertFalse(StartPolicy.replaysStarted(4999, 4000)); }
@Test public void replayWhenNothingVibratedYet() { assertTrue(StartPolicy.replaysStarted(10, 0)); }
// TriggerRuleTest
@Test public void theCooldownEndsAtExactlyFiveSeconds() {
    TriggerRule rule = new TriggerRule();
    rule.onScreenChange(0); rule.onScreenChange(400); assertTrue(rule.onScreenChange(800));
    rule.onScreenChange(4000); rule.onScreenChange(4500);
    assertFalse(rule.onScreenChange(5799)); // 4999 ms after the trigger
    assertTrue(rule.onScreenChange(5800));  // 5000 ms after
}
```

- [ ] **Step 2:** Run both test classes. Expected: the `StartPolicy` tests FAIL; the `TriggerRule` boundary test passes (it pins today's behavior).
- [ ] **Step 3:** Fix, one commit each (all are Findings from Plan 1):
  - `QuickStartActivity`: the caught start failure plays `Haptics.failed(this)`; the delayed `finish()` uses a field `Handler` whose callbacks are removed in `onDestroy()`; the camera actions use `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA` / `_SECURE`; a missing source logs `unknown`.
  - `SafeRecService`: remember `lastStartedPulseMs` (`SystemClock.elapsedRealtime()`) whenever `Haptics.started` plays; the ignored quick start replays only when `StartPolicy.replaysStarted(now, lastStartedPulseMs)`. `CMD_STOP` vibrates only when a recording was running.
  - `NotificationHelper`: below API 31 the action reads `notif_action_open` ("Open SafeRec" / "Apri SafeRec"); with no valid session start, no chronometer (`setUsesChronometer(false)`, `setShowWhen(false)`) instead of counting from now.
  - Guard: `guard_channel_name` becomes "Three presses of the power key" / "Tre pressioni del tasto di accensione"; `setEnabled()` and `ensureRunning()` are `synchronized` on `Guard.class`, and `ensureRunning()` re-checks `isEnabled` inside the lock.
  - `app/build.gradle.kts`: `saferec.powerGesture` accepts only `true` or `false`, otherwise `throw GradleException("saferec.powerGesture must be true or false, got '$it'")`.
- [ ] **Step 4:** Ruling recorded here: the safe count after the stop (two findings) stays as is, because the viewfinder shows "N pieces safe" only while recording, and the notification after the stop shows the files waiting. Add one line under "Findings from Plan 1" in the spec saying so.
- [ ] **Step 5:** `bash ./gradlew testDebugUnitTest :guard:testDebugUnitTest assembleDebug` passes. Commit each fix as it lands, e.g. `git commit -m "Vibrate the failure when the quick start cannot reach the service"`.

### Task 11: Texts

**Files:**
- Modify: `values/strings.xml`, `values-it/strings.xml`, any Java still using a removed key

- [ ] **Step 1:** Remove the keys no code uses any more: `google_drive_required`, `google_drive_required_msg`, `google_drive_failed`, `permissions_required`, `retry`, `close`, `show_tutorial`, `tutorial_general_*`, `tutorial_step_*`, `toggle_camera`, `share_root_folder`, `recording_indicator`, the `power_gesture_*` status labels replaced in Task 7 (keep `power_gesture`, the row title), `chunk_size`, `chunk_auto`, `chunk_custom`, `chunk_manual_title`, `enable_timestamping`. Check each with `grep -rn 'R.string.<key>\|@string/<key>' app/src` before removing.
- [ ] **Step 2:** Every key exists in both files: `bash ./gradlew :app:lintDebug` shows no `MissingTranslation` or `ExtraTranslation`. No `"OK"`/`"Cancel"` literal remains (`grep -rn '"OK"\|"Cancel"' app/src/main/java` is empty).
- [ ] **Step 3:** Read every remaining main-screen, settings and first-run string once against the spec's anti-goal "Text that promises something the app does not do". The tutorial's "public link" text is gone with Step 1.
- [ ] **Step 4:** Commit: `git commit -m "Remove the texts the redesign retired"`

### Task 12: Device checks and docs

**Files:**
- Modify: `AGENTS.md` (UI Layer diagram and Package Structure: `SettingsActivity`, `ui/` classes, `TutorialOverlayView` only in recordings; Testing: the new tests), `README.md` (screens, icon credit)
- Create (not committed): `tmp/ui-after/`, a script driving the S10+ over adb as `tmp/ui_tour.sh` did

- [ ] **Step 1:** Build with the switch off: `bash ./gradlew :app:assembleDebug -Psaferec.powerGesture=false`; install, open settings and the guide: no three-presses row or step, no crash. Reinstall the normal debug build.
- [ ] **Step 2:** Screenshots of every state into `tmp/ui-after/` (ready, recording, audio only, offline, Drive not connected, settings, first-run card), next to `tmp/ui-tour/` (before), and compare with the spec's problem table: problems 1, 3, 5, 6, 7, 8, 9 and 12 must be gone.
- [ ] **Step 3:** Never blocked: airplane mode, Drive token removed (`run-as` edit of `SafeRecPrefs.xml`, restored after), permissions revoked (`pm revoke`, re-granted after): in each case the button records (or asks, then records), and nothing covers it.
- [ ] **Step 4:** Hold to stop: `input swipe x y x y 400` does not stop (hint shows); `input swipe x y x y 1300` stops with one long vibration (`dumpsys vibrator_manager`).
- [ ] **Step 5:** Guard setup, asking the owner first because it changes the S10+ setup he keeps: revoke the overlay and the exemption, follow the settings row; check each summary updates on return, a tap never turns the guard off, and which battery choice makes `cmd deviceidle whitelist` list SafeRec. Fix `three_presses_battery_help` if the name differs. Restore the setup.
- [ ] **Step 6:** Camera double press: make Camera the last choice (choose it "Just once"), turn the option on, follow the tries: the app must report "not saved" and the next try must store "Always". Turn it off and on again: no try is asked (both stored). Turn it off at the end.
- [ ] **Step 7:** A recording started by three presses, then the app opened: the pill's time counts from the session start, the safe chip grows, audio-only quick starts show the audio surface. `CMD_START` refused for camera and microphone (Finding, pre-existing `dataSync`): start with the camera held by another app and note what the viewfinder shows.
- [ ] **Step 8:** Update `AGENTS.md` and `README.md`. Commit: `git commit -m "Document the viewfinder, settings and first run"`
- [ ] **Step 9:** Ask the owner before deleting `tmp/ui-after/`; keep `tmp/ui-tour/` for Plan 3.
