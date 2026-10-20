# SafeRec UI redesign: start fast, stop on purpose, see that the evidence is safe

Date: 2026-10-20. Status: for the owner's approval.

## Intent

SafeRec records audio and video in moments of danger and uploads it to Google Drive in 10-second chunks, so the evidence survives even if the phone does not. The recording engine is solid: record first, sync later, repair after a crash, timestamps, an evidence package. The UI on top of it is not built for the moment it exists for.

A usability review of the current app (code and screenshots on a Galaxy S10+) ranked its problems. The owner's priorities, in order:

1. **Immediacy.** Recording must start in a difficult moment: phone locked, in a pocket, hands shaking, someone in front of you.
2. **Simplicity.** Few things on screen, nothing to think about.
3. **Clarity.** At a glance: is it recording, and is the evidence safe?
4. **Beautiful and modern.**

The redesign covers both how recording starts and stops and how every screen looks.

### What the review found (the problems to remove)

| # | Problem | Severity (0-4) |
|---|---|---|
| 1 | When Drive is unreachable or not connected, a full-screen error ("Google Drive Required: this app cannot function without…") covers the record button. The text is false: the app records offline. | 4 |
| 2 | The Quick Settings tile "Start SafeRec" does not record unless the hidden setting "Auto-start on Tile/Notification launch" is on, and it is off by default. The tile also requires unlocking. | 4 |
| 3 | First launch shows an 8-step tutorial before anything else. | 3 |
| 4 | No fast start: nothing from the lock screen, no shortcut, no notification actions. | 3 |
| 5 | While recording: a small "● REC", no elapsed time, nothing says the evidence is safe, no vibration. The foreground notification shows only after ~10 s (Android's deferral) and has no actions. | 3 |
| 6 | Stop is one tap, in the same place as start: easy in a pocket or a struggle, and easy for someone else. | 3 |
| 7 | Five unlabeled icons of equal weight on the main screen. "Share with a person" (grants a Google account access to the whole folder) sits next to frequent actions. The audio-only icon shows the action while its color shows the state. | 3 |
| 8 | False texts: the tutorial promises a "public link" (the app never creates one); the Drive error says the app cannot work without Drive. | 3 |
| 9 | Settings in jargon ("Chunk Size", "Enable Timestamping", "Show Tutorial" first); "OK"/"Cancel" hard-coded in English. | 2 |
| 10 | Recordings list is technical: "Chunk 0", sizes, a "TSA" label; no session duration or status; a ✨ icon for "merge". | 2 |
| 11 | Playback uses the old framework `MediaController`. | 1 |
| 12 | Mixed icon families; colors without a system; an empty black preview with no guidance. | 1 |

Keep: the big record button at the bottom center, the dark theme, recording that continues with the screen off, TalkBack descriptions, and the record-first architecture (the UI must finally reflect it).

## Goals

- From a locked phone with the screen off, a recording starts with **three presses of the power key**, without unlocking and without looking, in under a second.
- Recording never waits for, and is never hidden by, Drive, the network, a tutorial or an error.
- Stopping is deliberate. Nobody stops a recording from the lock screen.
- While recording, the screen, the notification and a vibration say "recording" and "N pieces safe on Drive".
- Every screen speaks the owner's language (English and Italian), with no jargon and no false statements.
- One coherent, modern visual language (the "Viewfinder" direction below).

## Non-goals

- Changing the recording, upload, repair or timestamping engine, except where a goal needs a hook (counters for the UI, an idempotent start).
- A Jetpack Compose rewrite. The app stays on Views/XML with Material Components.
- A light theme. Dark only.
- Home screen widgets, wearables, voice assistants, Bluetooth panic buttons.
- Shake to record: the S10+ accelerometer is not a wake-up sensor, so listening to it with the screen off keeps the CPU awake all day, and running, bags and falls start false recordings.
- The volume-keys accessibility shortcut: tested and rejected. It does not work with the screen off, and with the screen on (power, then hold both volume keys) the owner found it too cumbersome.

## Anti-goals (failure even if it "works")

- Anything that delays or blocks the start of a recording.
- A full-screen error, or any surface over the record button.
- Taking over the camera double press by default.
- A recording that someone can stop from the lock screen, or recordings, settings or sharing reachable without unlocking.
- Text that promises something the app does not do.
- A second start while recording that restarts the session and leaves a gap.

## Constraints

- minSdk 26, targetSdk 36. Material Components 1.13 (Material 3). XML layouts.
- SafeRec is published on Google Play. A `specialUse` foreground service needs a declaration in the Play Console and a review. Starting screens from the background with "Display over other apps", and any battery-optimization exemption, may draw scrutiny.
- The reliability rules in `AGENTS.md` hold (record first, errors never stop a recording, and so on).
- Strings in English and Italian. Code, comments, commits and docs in English.
- Any new library, or new code that uses reflection, needs the R8 checks in `AGENTS.md` (Release Build) again.
- Privacy: nothing new leaves the phone. The timestamp authority receives a SHA-256 hash only, as today.
- The owner's everyday phone is a Pixel. The test phone is a Galaxy S10+ on LineageOS 23 (Android 16, 7.6 GB RAM, often under memory pressure). Device tests run on the S10+; the Pixel is touched only with the owner's explicit consent, each time.

## Decided by the owner

### Main screen: the "Viewfinder" direction

Chosen among three mockups (A "calm night" with a big round button and a small preview, B "all button" with half the screen as the button, C "viewfinder"). C, as the familiar camera app:

```
  READY                                   RECORDING
 ┌──────────────────────────┐            ┌──────────────────────────┐
 │ (☁ Drive ✓)    (⟲)(☰)(⚙) │ ← glass    │                     (⟲)  │
 │                          │            │       ( ● 00:42 )        │ ← red pill, elapsed time
 │   full-screen preview    │            │  ( 🛡 4 pieces safe )     │
 │                          │            │                          │
 │░░░░░░░░░░░░░░░░░░░░░░░░░░│ ← scrim    │░░░░░░░░░░░░░░░░░░░░░░░░░░│
 │ [Video|Audio]  (⏺)   [🖼] │            │          ((⏹))           │ ← ring fills while held
 │                          │            │   hold to stop           │
 └──────────────────────────┘            └──────────────────────────┘
```

- Full-screen camera preview. Controls float on "glass" (translucent, blurred where the platform allows, a dark veil otherwise).
- Top left: the **status chip** (see Status, below). Top right: switch camera, recordings, settings. Only these: sharing moves to settings.
- Bottom, on a dark gradient scrim so it stands out on any image: a Video|Audio segmented control, the record button, the latest session's thumbnail (opens the recordings).
- The record button is bigger than today's 68 dp (about 88 dp), with a white ring, and its touch area is wider than the circle. C's weakness in the mockups was a button that blends into the image: the scrim, the size and the ring fix it.
- While recording: the red pill with the elapsed time, the "N pieces safe" chip, and the stop button. Video|Audio and the thumbnail disappear (fewer distractions; switching modes would restart the session). Switch camera stays: it restarts the session (known limitation 1 in `AGENTS.md`), as today.
- In audio-only mode, or with the camera lost, the preview area shows a dark surface and the chip says "audio only". Recording continues.

### Starting a recording

| Gesture | Mechanism | Default |
|---|---|---|
| Power key three times | The guard service (below) | On; offered in the first-run flow |
| Camera double press | SafeRec registered as a camera app, chosen by the owner | Off; optional, only where the phone allows |
| Quick Settings tile | Starts recording at once, also from the lock screen, without unlocking | Always |
| Long press on the app icon | A static app shortcut "Record now" | Always |
| Record button in the app | As today | Always |

Recording starts **without unlocking**.

- **What shows over the lock screen** *(designer's call)*: only the black trigger screen, the same for the three presses, the tile and the camera double press. It starts the recording, vibrates and disappears.
- `MainActivity` never shows over the lock screen. The viewfinder, stop, camera switch, recordings, settings and sharing all need the unlock, so whoever holds a locked phone sees neither the live camera nor the recordings.
- When the phone is unlocked, the tile, the shortcut and the double press open the viewfinder, already recording.

**What a quick start records** *(designer's call)*: quick starts use their own defaults in Settings → Quick start: video or audio, back or front camera. The defaults are video and the back camera, since in a pocket with the screen facing the body the back camera faces out. Choices made on the viewfinder affect only recordings started there. A blind start must be predictable, not "whatever was last picked on screen".

#### Three presses of the power key (verified in a spike)

No public API reports power key presses. Each press turns the screen on or off, and Android broadcasts `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`.

- A light foreground service, the **guard**, registers a runtime receiver for both broadcasts. Three screen changes within 2.5 s start a recording. Rhythm-based rules do not work: `SCREEN_OFF` arrives ~0.4 s after the press (after the screen-off animation), so presses 0.37 s apart were measured as 737 ms and 66 ms.
- A camera double press (two presses under 300 ms) opens the camera instead of turning the screen off, so it yields at most two changes and does not trigger the guard. Repeated fast bursts (panic) do trigger it, and SafeRec then won the camera from the system camera app: acceptable, arguably useful.
- The guard cannot create a camera/microphone foreground service from the background (Android throws `SecurityException`). It starts an activity instead, which shows over the lock screen, turns the screen on, and asks the recording service to start: a visible activity may create that service. Starting an activity from the background is allowed because the user granted "Display over other apps" (`SYSTEM_ALERT_WINDOW`, the `BAL_ALLOW_SAW` path in the logs).
- Measured on the S10+, locked with a PIN and the screen off: video recording 0.6 s after the third press; trigger activity visible 30-54 ms after the trigger; the phone stayed locked. With the owner's own presses (~0.4 s apart) it triggered every time.
- Costs, accepted by the owner: a permanent low-importance notification ("SafeRec ready: press the power key three times to record"), the special permission granted once, and a foreground service of type `specialUse` (`FOREGROUND_SERVICE_SPECIAL_USE`, with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`).
- **The guard can die.** On the S10+, the low-memory killer killed the app process twice in a minute ("device is not responding", with 0.5 GB of RAM free), and Android postponed the sticky restart by up to 165 s "for mem-pressure-event". Android 15+ does not let a service restarted in the background return to the foreground, so the guard would stay dead until the app is opened. The owner chose to make it come back:
  - The setup asks for the **battery-optimization exemption** next to the overlay permission, on the same "turn on three presses" screen. It opens the system settings page (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), not the direct dialog: the latter needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, the permission Play polices most. The exemption is on the list of exemptions to the background-start restrictions, so a restarted guard can return to the foreground.
  - A periodic watchdog (WorkManager, at its 15-minute minimum) restarts the guard if it is gone. It also restarts on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
  - When the guard is off (killed and not back yet, the exemption or the overlay permission revoked), the app says so plainly: the status chip shows "Three presses off" with a tap to fix it.
  - The guard is still never the only way in: the tile, the shortcut and the app always work.
  - The builder checks that Android 15+ lets `specialUse` start from `BOOT_COMPLETED` (its restrictions name other types, but it was not tested), and that the exemption does bring a killed guard back on the S10+.
- **Google Play** (the owner's decision): the three presses ship on Play, declared as a `specialUse` foreground service for personal safety (start recording with a power key gesture). They sit behind a build switch. If Google refuses the declaration, the Play build ships with the switch off and the rest of the redesign still goes out, while builds outside Play keep the feature. With the switch off, nothing about the guard appears: no setting, no first-run screen, no manifest entries (the `specialUse` service and its permission, and the overlay permission).
- **Other screen changes** *(designer's call)*. Nothing is filtered, not even during a phone call: an anti-goal forbids blocking a deliberate start, for example during a threatening call. Proximity screen-off during a call and ambient wake (lift or notification) should not emit `ACTION_SCREEN_ON`/`OFF`, since the device stays interactive or dozing. The builder verifies this on the S10+; if they do emit them, this decision comes back to the owner.
- The trigger screen is discreet: black, no text beyond a small "Recording" for screen readers, gone after ~1.5 s. Vibration confirms (below). The screen then times out as usual, or the owner presses the power key once more. Recording continues with the screen off, as it already does.

#### Camera double press (optional)

The double press launches the camera with `STILL_IMAGE_CAMERA` (unlocked) or `STILL_IMAGE_CAMERA_SECURE` (locked). SafeRec declares both intent filters on its over-the-lock-screen activity. With two camera apps, Android asks which one ("Open with… / Always"). When locked, that choice needs an unlock first, so nothing appears. This is almost certainly why the double press "did nothing" on the S10+ while the spike was installed. Once the owner picks SafeRec as "Always", the double press records, locked or not.

- Off by default: it takes the photo camera away from the double press.
- Settings offers it with a guided "try it now", and explains how to undo it (Settings → Apps → SafeRec → Open by default).
- On Pixel phones the gesture may be tied to Pixel Camera (`config_cameraGesturePackage`; the official menu offers only "Camera" or "Wallet"). Then the option is not offered. This must be verified on the Pixel.

### Stopping

- In the app: hold the stop button for about 1 s. A ring fills while held, and a vibration confirms. Releasing early does nothing. TalkBack users get an accessibility action "Stop recording".
- From the notification: a "Stop" action that requires authentication (`setAuthenticationRequired`, Android 12+; below that, the action opens the app, which needs the unlock anyway).
- A start command while already recording does nothing. The spike showed the opposite: its trigger screen sent a second start when the screen came back on, and the service restarted the session, leaving a gap (the "camera switch" path). Camera switching needs its own command.

### Feedback while recording

- **Screen:** the red pill with the elapsed time since the session started, and "N pieces safe on Drive" (chunks of this session already uploaded).
- **Notification:** shown at once (`setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`), with the elapsed time, the safe count, and the authenticated Stop action.
- **On the lock screen** *(designer's call)*, discretion first:
  - The guard's notification is hidden there (`VISIBILITY_SECRET`, silent, minimized). A lock screen that says "press the power key three times to record" tells an aggressor about SafeRec.
  - The recording notification shows a neutral public version ("SafeRec active"), with no elapsed time and no Stop.
  - Android's own camera and microphone indicator cannot be hidden, and is not hidden.
- **Vibration:** two short pulses when a recording starts, one long pulse when it stops. A setting turns vibrations off for when even a buzz could be heard. (A fuller "vibration language" is in `IDEAS.md`, out of scope.)

### Status, never blocking

The status chip has four states:

- 🟢 Drive connected;
- ⚪ offline, "N pieces waiting on this phone";
- 🟠 "Reconnect Drive" (`ERROR_AUTH`; one tap starts the authorization);
- ⚪ Drive not connected yet (one tap connects).

Camera lost shows "audio only". Errors appear in the chip or in a slim banner, never full screen, never over the record button.

### Other screens

- **Recordings list:** one card per session, with date and time, duration, a thumbnail, and a status badge:
  - ✅ complete: every chunk the manifest lists is on Drive;
  - ⏳ uploading: chunks still on this phone;
  - ⚠️ incomplete: chunks missing.

  The gap logic already exists (`SessionManifest`, `DriveRecordings`). **Sessions only on the phone** *(designer's call)*: they are not listed. Recording offline, or before Drive is connected, shows an explicit banner instead: "N pieces waiting on this phone", plus "Connect Drive" when it is not connected. The latest-session thumbnail shows the same waiting state. Playing local files, which the uploader deletes at any moment, is out of scope. Tapping a card opens the **session screen**: playback, "Export evidence package" as the primary action, then share and delete. Chunks, timestamps and repaired originals appear under "Technical details". The ✨ merge icon goes: merging happens inside playback and export.
- **Settings**, in plain-language groups:
  - *Quick start*: three presses, camera double press, how the tile works;
  - *Evidence*: timestamping, off by default as today *(designer's call)*, now explained in plain words. Turning it on for everyone would send one Sectigo request per 10 s chunk, against Sectigo's request of at least 15 s between automated requests (see "Hash chain for timestamps" in `IDEAS.md`). Also, `privacy_policy.html` does not mention Sectigo at all, which is a gap even today and is fixed outside this redesign;
  - *Video*: quality, default camera;
  - *Drive account*: connected as…, reconnect, share with a person (the share moves here from the main screen);
  - *Advanced*: chunk size;
  - *Help*: the guide. The emergency drill stays an idea in `IDEAS.md`, out of scope *(designer's call)*.

  "Auto-start on Tile/Notification launch" goes away: the tile always records.
- **First run:** "Record now" works from the first second. Recording needs only the camera and microphone permissions, plus notifications. Drive can be connected later, and chunks wait on the phone. The 8-step tutorial becomes three screens: what SafeRec does, the permissions, and turning on the three presses (the overlay permission and the battery exemption; the screen is absent when the build switch is off). The guide stays reachable from settings.
- **Texts:** fix the false ones (no public link; works without Drive), and translate the hard-coded "OK"/"Cancel".
- **Visual language:** dark theme, Material 3. One icon family (Material Symbols Rounded). Colors with one meaning each: 🔴 recording, 🟢 safe, 🟠 attention.

## Left to the builder

- **Playback.** A modern player with clear controls. Media3/ExoPlayer would give a session playlist (chunks in sequence) and an auth header for Drive streaming. `VideoView` with custom controls would add no dependency. Either is fine. A new library means the R8 checks again.
- Whether the guard is its own service or a mode of `SafeRecService`. Prefer one visible SafeRec notification at a time if that comes cheaply.
- Settings as a full screen or a bottom sheet.
- Thumbnails and durations: Drive's `thumbnailLink` and `videoMediaMetadata.durationMillis`, or computed on the phone.
- Exact sizes, spacing, motion and colors within the visual language above.
- How the "pieces safe" count reaches the UI (for example counters in `ServiceState`, fed by the uploader).
- On minSdk 26, `setShowWhenLocked`/`setTurnScreenOn` need API 27: use the window flags on 26.

## Verification

- Unit tests (JVM) for the pure rules: the guard's trigger rule (three changes within 2.5 s, a double press never triggers, a burst does), start idempotence, and the status chip's state mapping (including "Three presses off").
- A build with the switch off has no guard in its merged manifest.
- On the S10+, after a forced low-memory kill of the guard, it is back within the watchdog's period (or sooner with the sticky restart).
- On the S10+ (`ANDROID_SERIAL` pinned, as `AGENTS.md` requires):
  - three presses with the phone locked and the screen off;
  - the tile from the lock screen;
  - the authenticated Stop;
  - a second start doing nothing;
  - the notification shown at once;
  - errors never covering the record button (offline, Drive not connected);
  - first run recording before Drive is connected.
- On the Pixel, only with the owner's consent:
  - a single camera double press opens only the camera;
  - three presses start SafeRec;
  - whether the camera double-press option is possible.
- R8 (`r8test`) if a library is added.
- Before/after screenshots of every screen, compared with the review's list above.
