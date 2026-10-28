# SafeRec — Application Architecture

> **Package:** `net.ark3us.saferec` (application id `net.ark3us.saferec.app`)
> **Distribution:** GitHub Releases, built and signed by GitHub Actions from a tag (see Release Build)
> **Platform:** Android (Camera2 API, MediaCodec, MediaMuxer)
> **Purpose:** Background audio/video recording with chunked upload to Google Drive

---

## High-Level Architecture

```mermaid
graph TD
    subgraph UI["UI Layer"]
        MA["MainActivity"]
        RA["RecordingsActivity"]
        SEA["SessionActivity (Media3 player, export, share, trash)"]
        SA["SettingsActivity"]
        QSA["QuickStartActivity"]
        PM["PermissionsManager"]
        UH["ui/ helpers: StatusModel, RecordButton, FirstRun, GuardSetup, SessionsAdapter, SessionPlaylist, ThumbnailLoader"]
        EE["EvidenceExport"]
    end

    subgraph Services["Service Layer"]
        SRS["SafeRecService"]
        SRTS["SafeRecTileService"]
        NH["NotificationHelper"]
    end

    subgraph Media["Media Layer"]
        VSR["VideoStreamRecorder"]
        ASR["AudioStreamRecorder"]
        MS["MuxerSink"]
        MM["MediaMerger"]
        TR["TimestampRenderer"]
    end

    subgraph Net["Network Layer"]
        FU["FileUploader (outbox, retry, dedupe)"]
        GDFU["GoogleDriveFileUploader (one per process)"]
        OW["OutboxWorker (WorkManager job)"]
        GDC["GoogleDriveClient"]
        DR["DriveRecordings"]
        MF["MediaFile"]
        TSA["TsaClient"]
    end

    subgraph Data["Data / Config"]
        SS["ServiceState (singleton)"]
        S["Settings"]
    end

    MA -- "startForegroundService(CMD_START), startService(others)" --> SRS
    SRTS -- "starts" --> QSA
    QSA -- "CMD_START + EXTRA_QUICK_START" --> SRS
    SRS --> VSR
    SRS --> ASR
    SRS --> MS
    VSR --> TR
    TR --> MS
    TR -- "draws to" --> VSR["VideoStreamRecorder preview"]
    MS -- "on chunk complete" --> SRS
    SRS -- "hash, timestamp" --> TSA
    SRS -- "upload chunks and .tsa" --> GDFU
    FU -- "Drive specifics" --> GDFU
    GDFU -- "schedules when a file waits" --> OW
    OW -- "drain() when the service is gone" --> GDFU
    GDFU --> GDC
    MA -- "observes status and errors" --> SS
    SRS -- "updates status, reports errors" --> SS
    RA -- "lists sessions" --> DR
    SEA -- "loads one session, streams pieces from Drive" --> DR
    SEA -- "export" --> EE
    EE -- "merged video" --> MM
    SRS -- "CMD_DELETE (trash)" --> DR
    DR --> GDC
    SEA -- "share the video" --> MM
    SEA -- "CMD_DELETE (trash)" --> SRS
    RA -- "opens" --> SEA
    SRS --> NH
```

---

## Reliability Rules

The app exists to save evidence when things go wrong. Keep these invariants:

1. **Record first, sync later.** Starting a recording never waits for the network, Drive or a valid token. Chunks wait on disk until an upload succeeds.
2. **Errors never stop a recording.** Upload, auth and TSA failures are reported as error events; only a total loss of camera *and* microphone ends a session.
3. **Network errors are not auth errors.** Only HTTP 401 triggers a token refresh. The background never clears the stored token: when a silent refresh needs user interaction, it reports `ERROR_AUTH` and keeps the files. Play services also ask for interaction when they cannot reach Google, and a cleared token stopped every later upload until the user reopened the app.
4. **Chunks never wait for the TSA.** Hash first, upload, then timestamp the hash.
5. **Degrade gracefully.** Camera lost → continue audio-only. Camera permission refused → record sound only: a start needs the microphone alone, from the app and from every quick start (`StartPolicy.recordsVideo()` in the service).
6. **The outbox holds ready chunks only.** A chunk is written in `recording/` and moved to `data_store/` once closed and hashed for the TSA, so the outbox can be uploaded at any time, even mid-recording. Hashing after the move let a rescan or the outbox job upload and delete a chunk before its hash was taken, leaving it without a timestamp.
7. **A failed lookup is not "not found".** Drive folders are created only when Drive answered that they do not exist, and only under the uploader's lock. Treating a timeout as "not found" created duplicate `SafeRec` folders and split sessions between them.
8. **The outbox outlives the service.** Every file waiting in the outbox has a WorkManager job behind it, and so does the chunk being recorded. A chunk recorded offline, or cut by a power cut, reaches Drive after a kill or a reboot without the user opening the app.

---

## Package Structure

```
net.ark3us.saferec/
├── MainActivity.java          # The viewfinder: preview, status chip, record button, first run
├── SettingsActivity.java      # Settings in plain-language groups, rebuilt on every resume
├── RecordingsActivity.java    # Session cards from Drive, with their status
├── SessionActivity.java       # One session: Media3 player, status, export, share, trash, technical details
├── EvidenceExport.java        # Builds, checks and shares a session's evidence package
├── QuickStartActivity.java    # The one entry of every quick start; the only screen over the lock screen
├── CameraGesture.java         # The optional camera double press (enables a disabled activity alias)
├── PermissionsManager.java    # Recording permissions, and Google Drive OAuth apart from them
│
├── data/
│   └── ServiceState.java      # Service status and one-shot errors, observed by the UI
│
├── media/
│   ├── VideoStreamRecorder.java  # Camera2 + MediaCodec video encoder (singleton)
│   ├── AudioStreamRecorder.java  # AudioRecord + MediaCodec AAC encoder
│   ├── MuxerSink.java            # MediaMuxer wrapper: chunks, one A/V clock, outbox hand-off
│   ├── ChunkPolicy.java          # When a chunk is full (10 s, or a manual size)
│   ├── WorkDir.java              # Where chunks are written; moves leftovers only while no recording uses it
│   ├── SampleJournal.java        # A chunk's sample log (formats, sizes, times), kept until it is closed
│   ├── ChunkIndex.java           # Finds the samples of a cut chunk from its log (pure Java)
│   ├── ChunkRepair.java          # Rebuilds a playable MP4 from a cut chunk and its log
│   ├── VideoOrientation.java     # Orientation math (snap, hint per camera facing)
│   ├── MediaMerger.java          # Merges MP4 chunks into a single file
│   └── TimestampRenderer.java    # OpenGL ES 2.0 pipeline for burned-in timestamp overlay
│
├── misc/
│   └── Settings.java          # SharedPreferences wrapper
│
├── net/
│   ├── FileUploader.java         # Outbox queue, retry backoff, dedupe, drain (abstract: tested with a fake)
│   ├── GoogleDriveFileUploader.java  # Drive upload, folder cache, token refresh (one per process)
│   ├── OutboxWorker.java         # WorkManager job: uploads the outbox when the service is gone
│   ├── OutboxBootReceiver.java   # After a boot with files left: the expedited outbox job
│   ├── DriveConnection.java      # Accepts a Drive authorization result (token, account)
│   ├── GoogleDriveClient.java    # Low-level Drive API wrapper
│   ├── DriveRecordings.java      # Recordings on Drive: list, download, share, trash
│   ├── MediaFile.java            # Chunk filename parser/generator
│   ├── SessionSummary.java       # One session as the screens show it: pieces, status, duration, every Drive file
│   ├── SessionManifest.java      # What a session recorded (chunks, sizes, SHA-256); gaps against Drive
│   ├── EvidencePackage.java      # The zip handed over: merged video, Drive files, README, report, verifier
│   ├── SessionCheck.java         # The verifier's checks, on the phone, before a session is handed over
│   └── TsaClient.java            # RFC 3161 Time Stamping Authority client (Sectigo)
│
│
├── services/
│   ├── SafeRecService.java       # Foreground service orchestrating recording
│   ├── SafeRecTileService.java   # Quick Settings tile: starts a recording through QuickStartActivity
│   ├── StartPolicy.java          # What CMD_START and CMD_SWITCH_CAMERA do (pure Java)
│   ├── SafeCount.java            # Which files are pieces: "N pieces safe", "N pieces waiting"
│   ├── Haptics.java              # Two short pulses on start, one long on stop, three short when nothing records
│   └── NotificationHelper.java  # Notification channel + builder
│
└── ui/
    ├── StatusModel.java          # Which status chip shows (pure Java)
    ├── StatusChipController.java # Draws the chip
    ├── RecordButton.java         # The record button; hold 0.5 s to stop
    ├── HoldToStop.java           # The hold, decided on the clock (pure Java)
    ├── RecordGesture.java        # A touch acts on the state it began in (pure Java)
    ├── NetworkWatcher.java       # Validated default network
    ├── DriveConnector.java       # Silent Drive check, only online with a token
    ├── PermissionsBanner.java    # Slim banner when a permission is missing; also the gate of every start from the app
    ├── PermissionsBannerModel.java # What the banner says, and whether a start asks first (pure Java)
    ├── FirstRun.java             # The three first-run cards over the viewfinder
    ├── CappedScrollView.java     # Scroll view capped at 45% of the screen: the first-run text never reaches the top bar
    ├── FirstRunSteps.java        # Which cards are still due (pure Java)
    ├── SettingsRows.java         # Row builders of the settings screen
    ├── GuardSetup.java           # Power key presses setup and count; the only class that may reference the guard
    ├── CameraGestureSetup.java   # Camera double press setup and its self-check
    ├── SessionsAdapter.java      # The session cards; the words shared with the session screen
    ├── SessionPlaylist.java      # What the player plays: the pieces in order, their placeholder lengths, where each starts (pure Java)
    ├── DriveTokenRefresh.java    # After a 401: one silent token refresh per screen, then the operation once more
    ├── ThumbnailLoader.java      # Drive thumbnails fetched with the token, cached in memory
    └── TrashedSessions.java      # Sessions just trashed, hidden from the list for 2 minutes (pure Java)

guard/                          # Separate Gradle module: the power key presses (see Quick Start)
└── net/ark3us/saferec/guard/
    ├── TriggerRule.java          # 3, 4 or 5 screen changes (4 by default) within 2.5 s + 1 s per change past three, 5 s cooldown (pure Java)
    ├── GuardService.java         # specialUse foreground service, listens to SCREEN_ON/OFF
    ├── Guard.java                # Status, settings intents, ensureRunning()
    ├── GuardBootReceiver.java    # BOOT_COMPLETED and MY_PACKAGE_REPLACED: starts the guard
    └── GuardWatchdog.java        # 15-minute WorkManager job that restarts a dead guard
```

---

## Recording Lifecycle (Detail)

### 1. Start Recording

```mermaid
sequenceDiagram
    participant MA as MainActivity
    participant SRS as SafeRecService
    participant VSR as VideoStreamRecorder
    participant ASR as AudioStreamRecorder
    participant MS as MuxerSink
    participant GDFU as GoogleDriveFileUploader

    MA->>SRS: startForegroundService(CMD_START)
    SRS->>SRS: startRecording()
    SRS->>MS: new MuxerSink(workDir, callback, dataType, sessionId, ChunkPolicy.fromSetting(MB))
    SRS->>VSR: start(context, muxer, useFront, surfaceTexture, callback)
    VSR->>VSR: initCamera() → syncQuality, ensureCameraThread, choosePreviewSize
    VSR->>VSR: setupEncoder() → MediaCodec (H.264), createInputSurface
    VSR->>VSR: openCamera() → CameraDevice.StateCallback.onOpened
    VSR->>VSR: startCaptureSession() → encoder + preview targets
    VSR-->>MS: encoder callback → writeVideoData(buffer, info)
    VSR-->>SRS: callback.onStarted(true)
    SRS->>ASR: start(muxer)
    ASR->>ASR: setupEncoder() → MediaCodec (AAC)
    ASR->>ASR: AudioRecord.startRecording()
    ASR->>ASR: processAudio() [on background thread]
    ASR-->>MS: writeAudioData(buffer, info)
    MS->>MS: addVideoTrack(format, rotation) / addAudioTrack(format)
    MS->>MS: start() → muxer.start(), flush pending packets
    SRS->>SRS: updateNotification(), ServiceState → STATUS_STARTED
```

### 2. Chunking & Upload (during recording)

```mermaid
sequenceDiagram
    participant VSR as VideoStreamRecorder
    participant MS as MuxerSink
    participant GDFU as GoogleDriveFileUploader
    participant GDC as GoogleDriveClient

    VSR-->>MS: writeVideoData(buffer, info)
    MS->>MS: (keyFrame && chunk full: 10 s or manual MB) OR device rotated?
    alt Chunk complete
        MS->>MS: rotate() → closeFile() → onFileSaved(chunk, still in recording/)
        MS-->>SRS: onFileSaved() callback
        alt Timestamping enabled
            SRS->>FTSA: sha256(chunkFile) (before it enters the outbox)
        end
        SRS->>SRS: moveToOutbox(chunk): recording/ → data_store/
        SRS->>GDFU: upload(mediaFile) (never waits for the TSA)
        alt Timestamping enabled
            SRS->>FTSA: timestampHash(hash, tsaFile) on tsaExecutor
            FTSA->>FTSA: HTTP POST to Sectigo
            alt Success
                SRS->>GDFU: upload(tsaFile)
            end
        end
        GDFU->>GDFU: uploadFile() on thread pool (max 4 concurrent)
        alt Failed with HTTP 401
            GDFU->>GDFU: attempt silent refresh (AuthorizationClient)
            alt Refresh success
                GDFU->>GDC: setAccessToken(newToken)
                GDFU->>GDFU: retry upload once
            else Resolution required
                GDFU->>LD: reportError(ERROR_AUTH)
            end
        else Any other failure (network down, timeout, Drive error)
            GDFU->>GDFU: keep token and file, rescan the outbox later
        else Success
            GDFU->>GDFU: file.delete()
        end
    end
```

Failures are classified from the error itself. An extra request to check the token would run later, could find the network back, and would say nothing about the failure. A 404 (a cached Drive folder was deleted) also clears the folder cache.

Token refreshes are shared: one at a time (`refreshLock`), at most one Play services request per minute.
- A token Drive already rejected, or a missing token, goes straight to the refresh instead of sending the chunk only to get another 401.
- A token the app stored meanwhile (the user opened it) is used without asking Play services.
- Before this, each failing chunk asked on its own (8 requests in 30 s), which can get the app throttled.

### Outbox & Retry

| Directory (under `files/`) | Holds |
|-----------|-------|
| `recording/` | The chunk being written |
| `data_store/` | The outbox: complete chunks and `.tsa` replies waiting for upload |

- `MuxerSink.closeFile()` hands a finished chunk over, still in `recording/`, and deletes empty ones. The service hashes it (TSA on), then renames it into the outbox (`moveToOutbox()`, same filesystem, atomic) and uploads it.
- `WorkDir.recover()` moves chunks left in `recording/` by a dead process (crash, kill, sudden power cut) into the outbox. Such a chunk has no MP4 index, so it is **repaired**, see Cut Chunk Repair below. `SafeRecService.onCreate()` (in the background: a repair can take seconds) and the outbox job call it.
- It moves nothing while a recording uses `recording/`. The service calls `WorkDir.acquire()` when a recording starts and `release()` once the post-processing of its last chunk is done (queued on the same executor). Moving the chunk being written would upload half a file, and moving a closed one before its post-processing would skip its TSA hash. One lock covers the counter and the moves, so a recording cannot start in the middle of a recovery. Under it the chunks are only renamed into `repair/`. The repairs run afterwards, under a lock of their own, so a recording start never waits for one.

### Session Manifest

Drive shows which chunks arrived, not which ones should have. `SessionManifest` keeps the list of what a session recorded: plain text a third party can read, one line per event, with Drive names, sizes and SHA-256.

```
started 0 1792426343499.0.mp4                       ← the chunk got its first sample
chunk 0 1792426343499.0.mp4 1107234 sha256=4fb5…    ← closed
repaired 1 ….1.mp4 359569 sha256=… original ….1.cut 359569 sha256=…
cut 2 ….2.mp4 12345 sha256=…                        ← recovered as it was (no usable log)
```

- **When.** `started` comes from `MuxerSink` at a chunk's first sample, not when its file opens: an empty chunk is deleted at close and must not look lost. `chunk` comes from the post-processing, with the hash taken before the chunk enters the outbox (now always, not only with timestamping on). `repaired` and `cut` come from the recovery (`ChunkRepair.beforeOutbox()`).
- **Where.** The master stays on the phone (`files/manifests/`, never deleted: a recovery days later appends to it). After each line a full copy goes to the outbox, and Drive keeps one copy, `{sessionId}.0.manifest` (`text/plain`), replaced in place (`files.update`, allowed by `drive.file` on the app's own files; the id is cached, so an update is one request).
- **A copy replaced while it uploads.** The next line can replace the outbox copy during its upload. The upload sends the bytes read at its start, then deletes the local copy only if it did not change, and otherwise sends the newer one too.
- **Timestamp.** With timestamping on, the manifest is timestamped when a recording stops (after its last chunk's line) and after a recovery changed it, as `{sessionId}.0.manifest.tsa`. Not after every chunk: that would double the requests to the TSA. The token proves the list of chunks and their hashes at that time, so a chunk removed from Drive later shows. The master is hashed under the manifest's lock, and the TSA asked outside it, so new lines never wait for the network.
- **Gaps in the list.** `DriveRecordings` reads each session's manifest and compares it with the `.mp4` files on Drive. Each missing chunk is "still on this phone" (outbox, `recording/` or `repair/`) or "recorded but missing on Drive". `SessionSummary` turns that into the session card's status (see Recordings and the session screen), and the session screen explains it with the counts ("N pieces are still on this phone", "N pieces are missing on Drive"). A session with no chunk on Drive (all lost, or a first chunk not uploaded yet) is listed too, as long as its manifest is on Drive. A manifest that cannot be read (network, 401) or lists no chunk gives no gaps (`SessionManifest.gaps()` is null for an empty one), so the session is "Not checked", never "Complete", and `SessionSummary.manifestUnreadable` tells it apart from a session with no manifest at all. Gaps are computed from the manifest, not from consecutive sequence numbers, which can skip legitimately (an empty chunk).
- **Trash.** The session screen trashes every file of the session (`SessionSummary.allFileIds`, pieces, timestamps, cut originals, manifest) and hands its type folder (`folderIds`) to `CMD_DELETE`, which trashes a folder only when it holds nothing, not even trashed files. So the session's folders stay on Drive, holding the trashed files, and Drive's "Restore" puts them back where they were (the gallery button passes over such a folder). `CMD_DELETE` is asynchronous and Drive's search returns a just-trashed file for some seconds, so `TrashedSessions` hides the session from the list for 2 minutes.
- **Verified on a Galaxy S10+:**
  - A 25 s recording left one manifest on Drive with `started` and `chunk` lines for chunks 0-2, whose sizes and SHA-256 matched the downloaded chunks.
  - The manifest's timestamp held the SHA-256 of the manifest on Drive.
  - Once chunk 1 was trashed on Drive, the list showed "Recorded but missing on Drive: chunk 1" (the old session header; the card now shows Incomplete and the session screen says how many pieces are missing).
- **Verifying a session.** `scripts/verify_session.py` (run with `uv run`) checks a session folder downloaded from Drive.
  - The manifest's timestamp, with `openssl ts -verify -digest` against certifi's Mozilla roots. Sectigo's tokens carry their whole chain (Signer R37, CA R41, Root R46, cross-signed by USERTrust RSA), so no extra certificate is bundled.
  - Every listed chunk, its own `.tsa`, and a repaired chunk's `.cut` and `.cut.tsa`.
  - The manifest only grows. When it no longer matches its timestamp, the verifier looks for a prefix that does: lines added later (a recovery after the stop) are reported as not covered, while an edited line matches no prefix and fails.
  - Its tests (`scripts/test_verify_session.py`) run offline, with a test TSA made with openssl. They cover a complete session, a missing, altered, never-closed or repaired chunk, lines added after the timestamp, an edited manifest, a token for another file and an untrusted authority. Four rules are checked by mutation.
  - Verified on a real session from the S10+ (a chunk and a repaired one, real Sectigo tokens): COMPLETE. Flipping one bit of a chunk, or deleting the cut original, made it fail.
- **Evidence package.** "Export evidence package" on the session screen (`EvidenceExport.export()`) builds `SafeRec-<session>.zip` in `cache/merged_sharing/` and shares it:
  ```
  SafeRec-<session>/README.txt         plain language, in the app's language: what is inside, how to check it
  SafeRec-<session>/report.txt         start, duration, chunks with SHA-256 and timestamp times, manifest state, gaps
  SafeRec-<session>/merged.mp4         to watch (left out if the merge fails: the chunks are the evidence)
  SafeRec-<session>/evidence/<session>/<type>/   every file of the session, byte for byte as on Drive
  SafeRec-<session>/verify_session.py  so `uv run verify_session.py evidence` works inside the package
  ```
  - The verifier comes from `scripts/` at build time (`copyVerifier` in `app/build.gradle.kts`, a generated assets directory), so the app never ships a copy that drifted.
  - The report reads timestamp times with BouncyCastle (`TsaClient.read()`) and holds the phone's verdict (below).
  - Videos are stored in the zip, not deflated again.
  - A package of a long session weighs gigabytes and Android clears the cache only when space runs out. Opening the recordings screen deletes what is older than a day in `cache/merged_sharing/` and `cache/merging/` (`EvidencePackage.deleteOld()`): packages and merged videos already shared, and the downloads of an export or a merge cut short. A process that dies skips its own clean-up: a killed export once left 218 MB there. Not the files just shared: the receiving app may still be reading them.
  - **Checked on the phone first** (`SessionCheck`). The export runs the verifier's rules on the downloaded files: the manifest's timestamp (whole file or a prefix), every listed file with its size and SHA-256, a never-closed chunk, and every timestamp. A timestamp is valid when it certifies the file's SHA-256, its signature checks out (BouncyCastle `TimeStampToken.validate()`: a timestamping certificate, valid at that time) and its chain reaches a root Android ships (`CertPathBuilder`, PKIX, no revocation check, as `openssl ts -verify`). Roots the user added are left out: they could vouch for anything.
    - Same rules as `scripts/verify_session.py`, so both give the same verdict: keep them in step. `SessionManifest.entries()` parses the manifest as `parse_manifest()` does, and also feeds the gaps of the list.
    - A verified package is shared at once ("Verified on this phone"). Otherwise a dialog lists the problems before the share sheet. That is the one moment a missing chunk can still be fixed. A chunk still on this phone (outbox, `recording/`, `repair/`) adds "Upload now" (`CMD_UPLOAD_PENDING`) and asks to export again once uploaded; a chunk missing and not here either may have been uploaded in the last moments, so the dialog asks to export again. "Share anyway" leaves the choice to the user, and the verdict goes into `report.txt` either way.
    - A PKIX implementation that does not know the critical timestamping usage of the signer would reject every chain, so a `PKIXCertPathChecker` claims it. BouncyCastle checks it on the signer.
    - Verified on the S10+, with real Sectigo tokens and Android's roots:
      - a 7-chunk session: COMPLETE (7 files, 8 timestamps), shared at once, and the verifier said COMPLETE on the package;
      - exported while still recording: its open chunk was "still on this phone", and "Upload now" reached the service;
      - a chunk trashed on Drive: NOT VERIFIED in the dialog and in `report.txt`, "Share anyway" shared it, and the verifier failed it the same way.
    - Exporting while recording also showed chunks missing that had reached Drive after the list was loaded. The export now loads the session again first (see Recordings and the session screen), so only a chunk uploaded during the export itself can still be missing.
  - Android collapses runs of spaces in string resources: the README and the report's column header are quoted in `strings.xml` to keep their layout.
  - Verified on the S10+. A session recorded with timestamping on was exported from the old list (the package icon of its header; the export now starts from the session screen, same code). In the zip, following its README, the verifier said COMPLETE (manifest, 3 chunks, real Sectigo tokens). The merged video played 22 s, and the report was aligned.
- **Lessons from the test.** Drive's search (`files.list` with `trashed=false`) still returned a just-trashed file for a few seconds. And a JSON body passed through `adb shell` lost its double quotes: Drive ignored the body and answered with the file, which looked like a success.

### Cut Chunk Repair

MediaMuxer writes the MP4 index (`moov`) only when a chunk is closed. A crash, a kill or a power cut leaves a chunk whose samples are all in its `mdat` but cannot be found, and no player opens it (`moov atom not found`).

- **The sample log.** While a chunk is written, `MuxerSink` writes a log next to it (`SampleJournal`, `<chunk>.idx`). It holds the track formats with their codec configuration (SPS/PPS, AAC config), the orientation and, for every sample, its track, size in the file, time and flags. That is ~1.2 KB/s, flushed every 16 samples, and deleted once the chunk is closed. A failing log never stops a recording: the chunk is then just not repairable.
- **Finding the samples** (`ChunkIndex`). MPEG4Writer lays out ftyp, a reserved free box, then mdat with runs of samples of one track, each track in its recording order. A video sample starts with its own length (size minus 4), so at each position the next logged video sample either matches, or the next logged audio sample comes. Checked first on a complete chunk: from its sample sizes alone it rebuilt its index exactly (715 of 715). The log records video sizes as MPEG4Writer stores them: it replaces a leading 4-byte start code with the length, or adds the length when there is none.
- **Safe failure.** The walk stops at the first sample the file does not hold whole. It also stops when the log ran out before the file (not flushed when the power went) and the next bytes look like a video sample, rather than taking it for the next logged audio. If no logged video sample is found, the writer laid samples out differently and nothing is repaired. A chunk with its index is never repaired.
- **Rebuilding** (`ChunkRepair`). The located samples go, unchanged, into a new MP4 with MediaMuxer, with the logged formats, orientation and times. Video gets its start code back, since MediaMuxer writes the length itself.
- **Both reach Drive.** The playable copy goes under the chunk's name, and the cut file goes untouched as `<timestamp>.<seq>.cut`, typed `application/x-saferec-cut`. With `application/octet-stream` Drive looked at the content and filed it as video, whatever the metadata said. The recordings list lists `.mp4` files only. It hides the `.cut` and its `.cut.tsa`, marks the chunk "repaired after a cut, original kept", and trashes them all together.
- **Timestamps.** A cut chunk never got its TSA timestamp, since the hash is taken when a chunk closes. With timestamping on, the recovery hashes the files while they are still in `repair/` (`Repairer.beforeOutbox()`), before an upload can delete them:
  - the file that goes as the chunk (the repaired copy, or the original when it could not be repaired) gets `<base>.tsa`, paired with the chunk as usual;
  - a repaired chunk's original gets `<base>.cut.tsa`.

  They prove the bytes existed, unchanged, no later than the recovery. The tokens travel with their chunk into the outbox, and a token left in `repair/` by a recovery cut short goes as it is. Requests are quiet (no toast from background work) and, as for every chunk, not retried. Verified: both tokens on Drive held the SHA-256 of the file they certify, timed at the recovery.
- **Compound names.** `MediaFile` takes everything after `timestamp.sequence` as the extension, so `x.1.cut.tsa` is a `cut.tsa`. Taking only the first part made it a `cut`, uploaded under the original's very name.
- **What is lost:** the samples MediaMuxer still held in memory when the process died (~0.5 s), and on a real power loss whatever had not reached the flash yet.
- **Verified on a Galaxy S10+:** a force stop 5 s into a chunk left 472 KB and a 6.5 KB log. The repaired copy played 4.2 s (126 video, 178 audio samples, 0 decode errors with ffmpeg, rotation kept). The `.cut` on Drive had the same SHA-256 as the cut file on the phone, and the list showed the label.
- There is one `GoogleDriveFileUploader` per process (`getInstance()`), shared by the recording, `CMD_UPLOAD_PENDING` and the outbox job. The `queuedFiles` dedupe, the folder lock and the token refresh therefore cover all of them: two uploaders would each create the same Drive folders, or upload the same chunk twice.
- **No upload twice.** An upload can reach Drive while its answer never comes back: the process dies, or a timeout. The file then stays in the outbox and goes again, and Drive used to hold the chunk twice (seen three times with a force stop during an upload). Merging the session repeated it.
  - A chunk or `.tsa` that just entered the outbox is queued with `uploadNew()`: its first upload cannot be a repeat, so it goes straight.
  - Every other attempt is a retry, or a file found by a scan (maybe left mid-upload by a dead process). Those first ask Drive for that name in the session folder (`findFile()`). The same MD5 means the upload already happened: the local file is just deleted ("Already on Drive"). Another MD5 is uploaded anyway: evidence is never dropped on a guess.
  - Only the caller that queues the file decides it is new (a flag on the queuing, no shared set). If a scan queued it first, that upload checks, at the cost of one request.
  - Verified with the force-stop test that had duplicated chunk 0 three times out of three: the retry found it on Drive, and the session held each file once.
- Each upload uses the token the app stored last, unless Drive already rejected it. The uploader lives as long as the process, across services, and the user may have reconnected Drive meanwhile.

Who retries depends on whether the service is alive:

| Service | Retries | Survives |
|---------|---------|----------|
| Alive (recording, or `dataSync` with files waiting) | `FileUploader` retry loop (`setRetryLoop(true)` in `onCreate`) plus the network callback | Nothing: it ends with the service |
| Gone (killed, `onTimeout()`, phone rebooted) | The WorkManager job `outbox` (`OutboxWorker`) | Process death and reboot |

- When an upload fails while the service lives, its file stays in the outbox, and `FileUploader` schedules one outbox rescan: 10 s, doubling up to 5 min. `onDestroy()` turns the loop off: rescans in a process without a service are not run reliably.
- A successful upload rescans the outbox immediately and resets the backoff. So does the network coming back: the default network changing, gaining a transport, or turning `VALIDATED`. A new Wi-Fi network shows up before its DNS works, so the first retry fails and the `VALIDATED` one goes through (outbox drained in 6 s instead of 23 s).
- A VPN stays the default network and stays `VALIDATED` while the phone has no network under it, so `onAvailable` never fires. Its transports do change, from `WIFI|VPN` to `VPN` and back (measured on a Galaxy S10+), and that change is the trigger.

**The outbox job** (`OutboxWorker`):
- Scheduled with `ExistingWorkPolicy.KEEP` whenever a file is queued or its upload fails (`onFilePending()`), when a recording starts, and when the service starts with files waiting. While a job waits or runs, scheduling again does nothing, so calling it per chunk is cheap.
- It needs a network (`NetworkType.CONNECTED`) and runs 5 min after it was scheduled: while the service lives, the service does the work. A failed run comes back after 5 min, doubling up to WorkManager's 5 h cap.
- When the service is known to be gone, an **expedited** job (`scheduleNow()`, unique name `outbox-now`, `REPLACE` so an earlier run waiting in its backoff cannot delay it) goes as soon as there is a network: from `onDestroy()` when files wait (the `dataSync` time limit, or Android stopping the service), and from `OutboxBootReceiver` after a boot with files left. Android may hold the delayed job of an app in the background for a long time: on a Galaxy S10+ (Android 16) it stayed "ready", every constraint met, for 35 min without starting, with this build and the one before. The expedited job is not held back and gets the network. Out of quota it runs as a regular job; below Android 12 it is a regular job, since WorkManager would need a foreground notification for it.
- Several runs at once (the delayed and the expedited job) are harmless: they share the uploader, so each file is uploaded once.
- It first adopts the chunks left in `recording/` (`WorkDir.recover()`, with `ChunkRepair`), so a chunk cut by a sudden power cut is repaired and uploaded after the boot without opening the app.
- A run that finds a recording in progress queues a successor (`APPEND_OR_REPLACE`: with `KEEP`, scheduling does nothing while the job itself runs). Without it, a run that succeeded mid-recording left no job queued until the next chunk, and a power cut in that gap left the open chunk to the next app launch.
- It calls `drain()`, which uploads the outbox and blocks until it is empty (success), an upload failed or the job is stopped (retry later). Uploads already running for the recording are waited for, not started twice. Chunks recorded meanwhile are uploaded in the same run: success means an empty outbox.
- Verified on a Galaxy S10+ (Android 16):
  - After `onTimeout()` stopped the service, the expedited job uploaded the 2 waiting chunks 7 s after the network came back. Before it, the delayed job took 41-254 s, or did not start.
  - In a 15-minute run with recordings, the job also ran while the service was uploading, with no duplicate. A run that failed (network cut) came back 5 min later.
  - Sudden power cut, by hand (it needs an unlock): recorded 15 s offline, then `adb reboot` 5 s into the second chunk. It sent no `ACTION_SHUTDOWN`, so the open chunk stayed in `recording/` without its index (`moov atom not found`). After the unlock, with the network back and the app never opened, `OutboxBootReceiver` queued the expedited job. That job adopted the cut chunk and uploaded both 12 s after the network returned. With only the delayed job, nothing ran in 11 min.
  - A force stop in the middle of a recording left the open chunk too. Android 15+ sends `BOOT_COMPLETED` to an app leaving the stopped state, WorkManager rescheduled, and the job adopted and uploaded it.
- Before this job, chunks left offline waited for the user to open the app again whenever the service was gone. That happened after a reboot, after the process was killed (a sticky restart only lasts until Android stops background services), or after the Android 15 `dataSync` cap.

### 3. Stop Recording

```mermaid
sequenceDiagram
    participant MA as MainActivity
    participant SRS as SafeRecService
    participant ASR as AudioStreamRecorder
    participant VSR as VideoStreamRecorder
    participant MS as MuxerSink

    MA->>SRS: startForegroundService(CMD_STOP)
    SRS->>SRS: stopRecording()
    SRS->>VSR: stopRecording()
    VSR->>VSR: releaseEncoder() → encoder.stop(), encoder.release()
    alt Preview surface attached
        VSR->>VSR: startCaptureSession() (preview-only, TEMPLATE_PREVIEW)
    else No preview
        VSR->>VSR: stop() → close session, camera, thread
    end
    SRS->>ASR: stop()
    ASR->>ASR: isRecording = false → processAudio loop exits
    ASR->>ASR: signal EOS → drainEncoder() → flush remaining frames
    ASR->>ASR: audioRecord.stop/release, encoder.stop/release
    SRS->>MS: stop()
    MS->>MS: closeFile() → muxer.stop(), muxer.release(), upload last chunk
    SRS->>SRS: ServiceState → STATUS_STOPPED, updateNotification()
```

### 4. Camera Switch Mid-Recording

When the user switches front↔back during recording, [SafeRecService](app/src/main/java/net/ark3us/saferec/services/SafeRecService.java) receives `CMD_SWITCH_CAMERA` (a `CMD_START` while recording is ignored):

1. [stopRecording()](app/src/main/java/net/ark3us/saferec/MainActivity.java) — tears down audio, video, and muxer (uploads final chunk)
2. [startRecording()](app/src/main/java/net/ark3us/saferec/services/SafeRecService.java) — creates a **new muxer** in the same session, re-opens the **other camera** than the running recording's, with the **same type** (video)
3. This is a full restart, not a hot swap — there's a brief gap in the recording

The service remembers the running recording's type (`currentDataType`) and camera (`recordingFront`), because a quick start records with its own settings, not the viewfinder's. Taking them from the viewfinder turned an audio-only quick start into video (in another folder of the session, with its own manifest) and could leave the camera unchanged. The service also stores the new camera as the viewfinder's (`useFrontCamera`), so the viewfinder shows what is recorded; `MainActivity` only sends the command while recording, and switches its own preview otherwise. An audio-only recording has no camera to switch: the command is ignored and logged.

### 5. Preview Lifecycle (Independent of Recording)

| State | Action |
|-------|--------|
| **App opens (not recording)** | [startPreview()](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) → opens camera, preview-only session (`TEMPLATE_PREVIEW`) |
| **App opens (recording in progress)** | [attachPreview()](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) → rebuilds capture session with preview + encoder |
| **App goes to background (recording)** | [detachPreview()](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) → rebuilds capture session without preview |
| **App goes to background (not recording)** | [stop()](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) | closes camera entirely |
| **Recording stops (app visible)** | [stopRecording()](app/src/main/java/net/ark3us/saferec/MainActivity.java) | releases encoder, rebuilds preview-only session |

### 6. Playback Lifecycle

`SessionActivity` plays a session with Media3 (ExoPlayer and `PlayerView`).

- **One player per screen, one video per session.** The pieces on Drive, in recording order (`SessionPlaylist.fileIds()`, `SessionPlaylist.url()`: `files/<id>?alt=media`), are the children of one `ConcatenatingMediaSource2` (Media3 1.11.1): one window, so the time bar and seeking cover the whole session and the pieces play back to back. Each piece stays its own `ProgressiveMediaSource`, so each keeps its own orientation. A piece with no file id is skipped. Nothing is merged or downloaded first.
- **Placeholder lengths.** A progressive child must have a length before it is read (the builder refuses one without): Drive's duration of the piece, else a usual chunk (10 s, `SessionPlaylist.placeholderDurationsMs()`). The player replaces it with the real length once it has read the piece. A piece starts at the sum of the earlier pieces' lengths (`SessionPlaylist.startOffsetMs()`, or the period's position in the player's timeline, which holds the real lengths it knows): a technical row's tap seeks there.
- **The token is never in the URL.** A `ResolvingDataSource` adds the screen's current token as an `Authorization: Bearer` header to each request, so a log or a crash report never holds it, and a retry or the next piece after a refresh carries the new token.
- **Paused when built.** The player starts at the saved position with `playWhenReady` off: a recording is never played by surprise.
- **Released in `onStop`, rebuilt in `onStart`** at the saved position in the session (one number, not piece and position), so the player holds no decoder or connection while the screen is hidden.
- **Sound.** The player takes the audio focus (it pauses for a call or another app's sound) and pauses when earphones are unplugged (`setHandleAudioBecomingNoisy`): evidence audio never moves to the loudspeaker by surprise.
- **Fullscreen** (the player's button): only the player is left, the system bars hide until the user swipes them in, and back leaves fullscreen first. In landscape a 16:9 player at full width is taller than the screen (≈ 488 dp against ≈ 310 dp on an S10+), with its controls below the fold, so turning the phone sideways while the player shows enters fullscreen by itself, and turning it back leaves it. A fullscreen chosen with the button stays.
- **Errors** show a bar with Retry in the player (it leaves fullscreen first, since the message sits in the body, and scrolls the bar into view): same position, current token. A piece that answers 404 or 410 (deleted for good since the load; a piece in the Drive trash still plays, since Drive serves it to the app that made it) is skipped: playback goes on from the next piece's start; the last one shows the error. On an HTTP 401 the screen refreshes the token (see Recordings and the session screen) and the player tries again; a refresh that ends while the player is released does nothing (`onStart` rebuilds it). Anything else is the plain error.
- **Rotation.** `android:configChanges` (orientation, screen size, layout) keeps the activity and the player alive; fullscreen is a layout state, not a new screen.

## Key Classes

### [VideoStreamRecorder](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) — [VideoStreamRecorder.java](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java)

**Singleton.** Manages Camera2 device, preview, and H.264 MediaCodec encoder.

| Field | Type | Notes |
|-------|------|-------|
| `muxer` | `volatile MuxerSink` | Set during recording, null otherwise |
| `isRecording` | `volatile boolean` | True when encoder + muxer are active |
| `encoder` | `MediaCodec` | H.264 encoder (Surface input) |
| `encoderSurface` | [Surface](app/src/main/java/net/ark3us/saferec/MainActivity.java) | Input surface from encoder |
| `cameraDevice` | `CameraDevice` | Currently open camera |
| `captureSession` | `CameraCaptureSession` | Active session |
| `previewSurface` | [Surface](app/src/main/java/net/ark3us/saferec/MainActivity.java) | From TextureView, null when no preview |
| `quality` | [Quality](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) | LOW (640×360), MEDIUM (854×480), HIGH (1280×720) |

Key methods:
- [initCamera(context, useFront)](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) — shared setup (sync quality, camera thread, preview size)
- [setupEncoder(context, cameraId)](app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java) — creates & starts MediaCodec with async callback
- [startCaptureSession()](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) — builds targets list (encoder + preview), sets repeating request
- `openCamera(...)` + request generation guard — ignores stale `onOpened` callbacks after stop/restart to avoid resurrecting dead sessions
- [releaseEncoder()](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) — stops & releases encoder safely
- [configureTransform(textureView)](app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java) — corrects preview for device rotation

**Orientation** ([VideoOrientation](app/src/main/java/net/ark3us/saferec/media/VideoOrientation.java), pure Java, unit tested):

- The device orientation comes from an `OrientationEventListener` (accelerometer). It works with the screen off (phone in a pocket) and with auto-rotate disabled, unlike the display rotation used before.
- It is snapped to 0/90/180/270 with a 15° hysteresis past the 45° boundaries, so a phone held at ~45° does not split the recording into tiny chunks.
- A phone lying flat reports nothing: the last orientation is kept. Until the first reading, the display rotation is the starting guess.
- **Hint** = `(sensor + device) % 360` for the back camera, `(sensor - device) % 360` for the front one. The front camera faces the other way. Using the back formula for both turned front videos upside down in landscape.
- On a change, the timestamp overlay turns at once. The encoder is asked for a key frame (`PARAMETER_KEY_REQUEST_SYNC_FRAME`), and `MuxerSink` starts the chunk with the new hint at that key frame. A chunk split elsewhere would start with frames that cannot be decoded.
- Verified hand-held on a Galaxy S10+ with the front camera: portrait, landscape, the other landscape and back to portrait all played upright. Every chunk started with a key frame, 23-52 ms after the turn.
- Turning from one landscape to the other passes through portrait, which leaves a short chunk (~0.5 s). A duration split just before a turn leaves one too. Both are harmless. A debounce would remove them, at the cost of recording the first part of every real turn sideways.

### [AudioStreamRecorder](app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java) — [AudioStreamRecorder.java](app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java)

Manages `AudioRecord` (mic input) and AAC MediaCodec encoder on a background thread.

- **Sample rate:** 44100 Hz, mono, PCM 16-bit
- **Bitrate:** 64 kbps AAC-LC
- **Timestamps:** `CLOCK_BOOTTIME` of the first captured frame (`AudioRecord.getTimestamp`, falling back to the read time), then the sample count: never jittery, never backwards.
- **No dropped PCM:** when the encoder has no free input buffer, the loop drains output and waits. Dropping a buffer used to shorten the audio timeline and shift all later audio against the video.
- **Stop sequence:** signals EOS to encoder, drains remaining output, then releases

Key methods:
- [processAudio()](app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java) — loop: read PCM → queue to encoder → [drainEncoder()](app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java)
- [drainEncoder(bufferInfo)](app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java) — pulls encoded AAC from encoder, writes to muxer

### [MuxerSink](app/src/main/java/net/ark3us/saferec/media/MuxerSink.java) — [MuxerSink.java](app/src/main/java/net/ark3us/saferec/media/MuxerSink.java)

Thread-safe (`synchronized`) wrapper around `MediaMuxer` with automatic chunking and upload.

| Concept | Implementation |
|---------|---------------|
| **Track readiness** | Waits for both audio+video tracks (or just audio if audio-only) before `muxer.start()` |
| **Pending packets** | Buffers up to 100 packets while tracks aren't ready |
| **Chunk rotation** | When the chunk is full (duration or size limit): on a video key-frame, or on any audio packet when there is no video. Also at the first key frame after the device orientation changes (one MP4 holds one orientation hint) |
| **Camera lost** | `setExpectedVideo(false)`: following chunks carry audio only (still under the `video` folder) |
| **One clock** | Video timestamps are brought to `CLOCK_BOOTTIME`, the audio clock. The camera uses `BOOTTIME` or `MONOTONIC` depending on the device, and the closer clock is picked on the first frame. |
| **PTS normalization** | One session base for both tracks (first packet written) → chunk base = first packet of the chunk minus `CHUNK_LEAD_US` (1 s) → final. The lead keeps packets of the other track captured just before the chunk's first packet, instead of dropping them. The MP4 writer moves the earliest sample to 0, and the tracks keep their real offset (edit list). |
| **File naming** | `{sessionId}_{dataType}_{timestamp}.{seq}` via [MediaFile](app/src/main/java/net/ark3us/saferec/net/MediaFile.java) |
| **Sample log** | `<chunk>.idx` next to the open chunk, so a cut chunk can be repaired (see Cut Chunk Repair) |

**Chunk limit** is determined by:
1. Manual setting (`Settings.getChunkSizeMB`) if > 0: split by size, no duration limit
2. Otherwise (auto): split by duration, `ChunkPolicy.DEFAULT_DURATION_MS` = 10 s (wall clock), for both video and audio-only

Duration, not bytes, is what bounds data loss: a static or dark scene compresses well and would otherwise stretch a size-based chunk.

### [TsaClient](app/src/main/java/net/ark3us/saferec/net/TsaClient.java)

Utility to obtain cryptographically secure RFC 3161 timestamps.

- **Hash:** SHA-256
- **Provider:** `https://timestamp.sectigo.com` (Sectigo)
- **Library:** BouncyCastle (for TSQ/TSR handling)
- **Local Cache:** Saves `.tsa` files side-by-side with media chunks during background upload.
- **Off the critical path:** `sha256()` runs before the chunk upload; `timestampHash()` only needs the hash and runs later on `SafeRecService.tsaExecutor`, so a slow TSA never delays uploads. Failed timestamps are not retried.


### [TimestampRenderer](app/src/main/java/net/ark3us/saferec/media/TimestampRenderer.java) — [TimestampRenderer.java](app/src/main/java/net/ark3us/saferec/media/TimestampRenderer.java)

OpenGL ES 2.0 pipeline that composites a burned-in date/time overlay onto camera frames before they reach the encoder. Always active during video recording.

- **Pipeline:** Camera → SurfaceTexture (OES texture) → GL render (camera quad + timestamp quad) → encoder input Surface → MediaCodec
- **GL thread:** Dedicated `HandlerThread` ("TimestampGL") for all EGL/GL operations
- **Shaders:** OES external sampler for the camera pass-through, regular `sampler2D` for the text bitmap overlay
- **Text rendering:** Android `Canvas` draws the current date/time into a `Bitmap`, uploaded as a GL texture; only regenerated when the wall-clock second changes
- **Orientation:** The overlay position is defined in "display NDC" and rotated by `+recordingRotation` (CCW in GL = inverse of player's CW rotation) to map to raw-frame NDC. This single rotation matrix handles both position and text orientation for all four orientations (0°/90°/180°/270°).
- **Preview/recording parity:** Uses the same raw camera frames as the encoder (no extra sensor-rotation transform) so the recorded video orientation matches what the player expects.
- **Presentation time:** Forwards the `SurfaceTexture` timestamp to EGL via `eglPresentationTimeANDROID` for correct A/V sync
- **Preview:** The renderer only feeds the encoder. The preview Surface (TextureView) is a separate camera target, so the on-screen preview does not show the overlay.
- **Integration:** Created in `VideoStreamRecorder.setupEncoder()` for every video recording; its input surface replaces `encoderSurface` in the camera capture session targets.

### [MediaMerger](app/src/main/java/net/ark3us/saferec/media/MediaMerger.java) — [MediaMerger.java](app/src/main/java/net/ark3us/saferec/media/MediaMerger.java)

Merges multiple MP4 chunks into a single file. Used by the session screen's "Share the video" ([SessionActivity](app/src/main/java/net/ark3us/saferec/SessionActivity.java)) and by the evidence package ([EvidenceExport](app/src/main/java/net/ark3us/saferec/EvidenceExport.java)). Playback does not merge: it plays the pieces as one timeline (`ConcatenatingMediaSource2`).

- Reads video/audio formats from the first chunk
- Strips `KEY_ROTATION` from format, applies rotation via `setOrientationHint` only
- Concatenates samples with PTS offset tracking per track. Each track continues where it ended in the previous file, measured from its own first sample. Only in the first file is the gap between the tracks kept, because there it is the real offset between microphone and camera start.
- **Limitation:** Single MP4 = single rotation value. Mixed-camera sessions or merged chunks with different device rotations will have wrong orientation on some segments.

### [SafeRecService](app/src/main/java/net/ark3us/saferec/services/SafeRecService.java) — [SafeRecService.java](app/src/main/java/net/ark3us/saferec/services/SafeRecService.java)

Foreground service orchestrating the recording. Commands via `Intent` extras:

| Command | Action |
|---------|--------|
| `CMD_START` | (`startForegroundService`) Starts a recording. **Ignored while recording**, so a second quick start never cuts the session (`StartPolicy`). With `EXTRA_TOGGLE` (the power key presses) it stops the recording instead, with the long pulse. With `EXTRA_QUICK_START` (set by `QuickStartActivity`) it records with the quick-start settings, not the viewfinder's choices. It always enters the foreground; while recording, with the running recording's type, so an ignored start never drops the camera type |
| `CMD_SWITCH_CAMERA` | (`startService`) While recording video: stop + restart with the other camera and the same type (a new muxer, see Camera Switch). Ignored when not recording, or when recording audio only. It never enters the foreground on its own |
| `CMD_STOP` | Stop recording, reset session |
| `CMD_UPLOAD_PENDING` | Upload the outbox now (safe while recording) |
| `CMD_DELETE` | Move Drive files (`EXTRA_FILE_IDS`) to the Drive trash, never delete them for good. Folders (`EXTRA_FOLDER_IDS`) are trashed only when they hold nothing, not even trashed files. Deleting a folder for good would also delete the trashed files in it, and keeping it lets Drive's "Restore" put files back where they were. |

State is broadcast via the [ServiceState](app/src/main/java/net/ark3us/saferec/data/ServiceState.java) singleton: `STATUS_READY` → `STATUS_STARTED` → `STATUS_STOPPED`.

Errors are a separate one-shot channel (`reportError()` / `getError()` / `clearError()`): `ERROR_AUTH` (Drive needs re-authentication), `ERROR_CAMERA` (camera lost, recording continues audio-only), `ERROR_RECORDING` (nothing can be recorded). Errors never change the recording status and never stop a recording.

Camera/encoder callbacks are posted to the main thread (`onVideoResult`) and ignored if they belong to a previous session.

**Foreground lifecycle** (`refreshForeground()`, always on the main thread, posted after each state change):

| State | Foreground | Notification |
|-------|------------|--------------|
| Recording video | `camera\|microphone` | Recording in progress |
| Recording audio only | `microphone` | Recording in progress |
| Not recording; files in the outbox, uploads, TSA or trashing running | `dataSync` | What is running, or "N files waiting to be uploaded" |
| Nothing left | leaves the foreground, `stopSelfResult(lastStartId)` | none |

- **The notification** (`NotificationHelper`):
  - It is shown at once (`FOREGROUND_SERVICE_IMMEDIATE`, API 31+), not after Android's ~10 s deferral.
  - While recording it holds a chronometer counting from the session's start, and the count of safe pieces ("N pieces safe on Drive", a plural string): the chunks of the current session that reached Drive (`SafeCount`, `FileUploader.UploadListener.onFileUploaded`, `ServiceState.getSafeChunks()`).
  - The channel is silent (`SafeRecServiceSilentChannel`, `IMPORTANCE_LOW`, no sound, no vibration): a covert start must not sound, nor add a vibration that blurs SafeRec's own start and stop pulses. The first channel (`SafeRecServiceChannel`, default importance) is deleted in `createNotificationChannel()`, since Android cannot change a channel's sound afterwards.
  - Its Stop action needs the phone unlocked (`setAuthenticationRequired(true)`, API 31+). Below API 31 the action opens the app, which needs the unlock anyway.
  - Visibility is secret (`VISIBILITY_SECRET`): on a secure lock screen it is hidden whatever the user's lock-screen setting, so whoever holds a locked phone does not learn that SafeRec records. It used to be private with a public version ("SafeRec · Active"), but SystemUI redacts a private notification only when the user chose "hide sensitive content": under the common default "show all content" it showed in full, chronometer and safe count included. Android's own camera and microphone indicator still shows: it cannot be hidden.
- Haptics (`Haptics`): two short pulses when a recording starts, one long pulse when it stops, none on a camera switch. The `vibrations` setting turns them all off.
  - A start vibrates only once something records (`confirmStart()`): when the microphone starts, or, if it failed, when the camera does. It used to vibrate right after the start command, also when nothing could record (an audio-only start without the microphone, or neither microphone nor camera: `QuickStartActivity` does not check the permissions).
  - A start that records nothing gives three short pulses instead (`Haptics.failed()`), so a blind start never confirms a recording that is not there.
  - A quick start that finds a recording running is ignored (the power key presses excepted: they stop it), but vibrates two short pulses again: a first confirmation that went unnoticed must not push the owner to unlock the phone and check. Not while the running start still waits for its own confirmation. An ignored start from the app vibrates nothing.
- Only `CMD_START` is sent with `startForegroundService()`, and the service enters the foreground before anything else. Other commands use `startService()`: with `startForegroundService()` the service would have to enter the foreground even with nothing to do, or crash.
- Work counters (`activeTimestamping`, `activeDeletions`) go up when the work is submitted, not when its task starts. Otherwise the service could stop in between and drop the work.
- Sticky restart after the process was killed: Android forbids entering the foreground from the background, so the service never tries. It resumes the outbox as a plain background service for as long as Android lets it run. Verified on a Galaxy S10+: the waiting chunk was uploaded after the kill, with no crash.
- Android 15+ caps `dataSync` at 6 h a day: `onTimeout()` stops the service, and the outbox job takes over.

**Phone switching off** (`onShutdown()`): power key, reboot, or an empty battery, which Android shuts down cleanly at ~1%.
- Android sends `ACTION_SHUTDOWN` only to receivers registered at runtime: the service registers one in `onCreate()`. Android waits up to 10 s in all for them.
- While recording, it calls `stopRecording()` on the main thread, so the open chunk gets its MP4 index. It then waits up to 3 s for the post-processing (hash, move to the outbox) and up to 2 s for WorkManager to store the outbox job (`OutboxWorker.scheduleAndWait()`). After the next boot, `OutboxBootReceiver` sees the waiting files and the expedited job uploads them.
- Before this, the power cut left the open chunk without its index (it needed repair tools to play), and in `recording/`, where only the next service start moved it to the outbox.
- Verified on a Galaxy S10+ (Android 16) with `adb shell svc power reboot`, which goes through the framework shutdown like the power menu. `adb reboot` does not: it sends no `ACTION_SHUTDOWN` (checked), so it stands for a sudden power cut.
  - The shutdown came 5 s into the second chunk while offline. The recording was closed, with 2 files in the outbox and the job stored, 0.45 s after the broadcast.
  - After the boot, `recording/` was empty and the last chunk played (6.2 s, audio and video).
  - Once the network was back, the job uploaded both chunks without the app being opened.

### [Settings](app/src/main/java/net/ark3us/saferec/misc/Settings.java) — [Settings.java](app/src/main/java/net/ark3us/saferec/misc/Settings.java)

SharedPreferences wrapper. Keys:

| Setting | Type | Default |
|---------|------|---------|
| `accessToken` | String | null |
| [onlyAudio](app/src/main/java/net/ark3us/saferec/misc/Settings.java) | boolean | false |
| `useFrontCamera` | boolean | false |
| `videoQuality` | String | "LOW" |
| `chunkSizeMB` | int | 0 (auto = 10 s chunks) |
| `quickStartAudioOnly` | boolean | false (a quick start records video) |
| `quickStartFrontCamera` | boolean | false (a quick start uses the back camera) |
| `vibrations` | boolean | true |
| `tutorialShown` | boolean | false (old; seeds `firstRunDone`) |
| `firstRunDone` | boolean | the old `tutorialShown` |
| `driveAccount` | String | null (email of the connected account, on the phone only) |
| `shareEmail` | String | "" (last address recordings were shared with) |
| `timestampingEnabled` | boolean | false |

The guard keeps its own settings (`saferec_guard`: `enabled`, and `presses`, 4 by default), so its module needs nothing from the app.

The old `autoStartOnLaunch` setting is gone: the tile, the shortcut and the power key always record at once.

Security note:
- `SafeRecPrefs.xml` is excluded from Android cloud backup/device transfer/full backup rules to avoid backing up the Drive access token.


---

## Quick Start

Recording must start with the phone locked, in a pocket, without looking. Every way in goes through one screen, `QuickStartActivity`.

```
 power key x4 ──► GuardService ──┐
 tile (locked too) ──────────────┤
 shortcut "Record now" ──────────┼──► QuickStartActivity ──► CMD_START + EXTRA_QUICK_START ──► SafeRecService
 camera double press (optional) ─┘     (black, over the lock screen)
```

- **`QuickStartActivity`** shows over the lock screen and turns the screen on: `android:showWhenLocked` and `android:turnScreenOn` in the manifest (the camera alias inherits them), plus `setShowWhenLocked()` / `setTurnScreenOn()` at runtime and the window flags on API 26. The manifest attributes are what the tile needs: on Android 14+ it starts the activity through a `PendingIntent`, and SystemUI decides whether to ask for the unlock from the manifest's flags, before the activity runs. With the runtime calls alone, the tile asked for the PIN. It is black, with a "Recording" description for screen readers only, and never shows the camera or any recording. It sends `CMD_START` with `EXTRA_QUICK_START` once: a recreated activity (the screen coming back on) sends nothing, but still finishes like the first one, so its black screen never stays up. A rotation does not recreate it (`configChanges`; a fixed `screenOrientation` is ignored from Android 16 and lint flags it). The start is wrapped in a try/catch: the activity runs in the recording's process, and an unexpected refusal must not crash a recording in progress. Locked, it finishes after 1.5 s. Unlocked, it opens `MainActivity`, already recording. `MainActivity` itself never shows over the lock screen.
- **What it records.** The quick-start settings (`quickStartAudioOnly`, `quickStartFrontCamera`: video and back camera by default), not what was last picked on the viewfinder. Without the camera permission it records sound only, in the foreground as microphone alone: the camera type is refused without its permission.
- **Tile and shortcut.** The tile (`SafeRecTileService`) and the static shortcut "Record now" start `QuickStartActivity` with source `tile` / `shortcut`. The tile works from the lock screen without unlocking. Source `power` comes from the guard and `camera` from the double press.
- **Stopping is deliberate.** Nobody stops a recording from the lock screen: the notification's Stop needs authentication (see the notification above). One exception, by the owner's choice: the power key presses toggle (see Power key presses).
- **Commands from other apps.** `MainActivity` is exported and runs the `command` extra it is launched with. Release builds accept only `CMD_START` there and log any other command as ignored: any installed app could otherwise stop a recording. Debug builds (`BuildConfig.DEBUG`) accept every command, because `scripts/device-tests.sh` stops recordings that way.
- **Verified on a Galaxy S10+** (Android 16, locked with a PIN):
  - The tile from the lock screen recorded without asking for the PIN, video in 0.6 s. A second tap was ignored and vibrated two pulses again.
  - The shortcut "Record now" (long press on the icon) recorded and opened `MainActivity`.
  - The notification was posted 0.22 s after the start, hidden on the lock screen, with no sound or vibration of its own.
  - The lock screen's quick settings list "Active apps", but opening it asked for the PIN: nothing there stops a recording.
  - On a Pixel with Android 17 (release build, by hand, locked): the tile from the lock screen recorded without asking for the PIN, microphone 0.22 s and camera 0.27 s after the tap, with two pulses; the trigger screen finished after 1.5 s and the phone stayed locked. The Pixel's lock screen corner shortcuts take only Google's list (no API adds an app), so the tile is the lock screen shortcut there.
  - The shrunk release (`r8test`): three presses recorded, the notification showed its chronometer and safe count, and its Stop (phone unlocked) stopped the recording. No crash.

## Viewfinder and settings

`MainActivity` is a camera-style viewfinder. Errors never cover the record button: Snackbars sit above the controls, a slim banner reports missing permissions, and there is no full-screen error.

```
 [status chip]                [switch camera] [recordings] [settings]
 (preview)
 [Video|Audio]     ( record: hold 0.5 s to stop )     [gallery]
 recording: red pill with elapsed time, "N pieces safe" chip
```

- **Status chip** (`StatusModel`): `CONNECT_DRIVE`, `RECONNECT_DRIVE`, `THREE_PRESSES_OFF`, `WAITING`, `OFFLINE`, `DRIVE_OK`, `HIDDEN`. Recording first, sync later: the chip reports, it never blocks.
- **Stopping** is a 0.5 s hold on the record button (`HoldToStop`): a tap only shows "Hold to stop". It was 1 s; the owner found that too long, and half a second still sits well above a tap (~100-200 ms). A touch acts on the state it began in (`RecordGesture`): releasing a hold once restarted the recording.
- **Drive.** `DriveConnector` checks silently, only online with a token, and never opens the account picker by itself. The viewfinder runs the check at most once a minute, and at once when the network came back (while online, or at the resume that follows a stop spent offline). When the outbox holds any file (a lone manifest copy or time stamp counts) a valid check sends `CMD_UPLOAD_PENDING`. `ServiceState` publishes the session start, audio only, the pieces waiting and a sticky Drive-auth flag.
- **Pieces waiting** counts the chunks (`.mp4`) in the outbox only (`SafeCount.pieces()`, used by the service and by `MainActivity` on resume). A chunk's `.tsa`, the manifest copy and a cut original travel with them but are not pieces: counting every file showed "2 pieces waiting" 17 s into an offline recording. The service keeps the raw file count for its own work and its notification ("N files waiting to be uploaded").
- **Permissions.** A start needs the microphone only, in every mode (`PermissionsManager.canRecord()`). Video mode asks for the camera with it, in one request, once per screen (`PermissionsBannerModel.mustAsk()`): the recording starts once the microphone is granted, with video if the camera was granted too. A refused camera records sound only, decided by the service, so a quick start does the same. While the camera stays refused in Video mode, the banner reads "No camera permission: recording sound only." and its Allow asks for the camera (the app's settings once Android no longer shows the dialog). A refused microphone shows the banner as before. Recording permissions are separate from Drive (`requestRecordingPermissions`, `connectDrive(interactive, ...)`).
- **Settings** live in `SettingsActivity`: Quick start, Evidence, Video, Google Drive, Advanced, Help. The three presses setup (`GuardSetup`) sends the battery step to the app's own page ("App battery usage, Unrestricted" on Android 12+, "Don't optimize" below) and continues by itself after the overlay page. Each step's dialog is titled with its step ("Step 2 of 2: …"): with one title for both, an owner who had granted the overlay took the battery dialog for the overlay asked again. On a Pixel with Android 17, "Unrestricted" shows only after tapping the words "Allow background usage" (not its switch), and the battery text says so. The tile row adds the tile with `StatusBarManager.requestAddTileService` on Android 13+.
- **First run**: three cards above the controls (what SafeRec does, permissions, three presses). The record button works throughout. The same cards are Settings, Help, Guide (`EXTRA_SHOW_GUIDE`). The card fits between the top bar (and the permissions banner) and the controls: its top margin follows the top container and its text sits in a `CappedScrollView` (at most 45% of the screen), so a long text (large font, translation, landscape) scrolls instead of reaching the top bar. A start waiting for the permission dialog survives a recreation (a rotation): `PermissionsBanner` saves it with the refusals.
- **Build switch off.** `GuardSetup` is the only class that references the guard; `scripts/check-guard-refs.sh` checks it.
- **Verified on a Galaxy S10+** (Android 16, debug build):
  - Ready and recording as specified. A 1.8 s hold stopped once with one long vibration and never restarted. The safe chip appeared at the first piece (~17 s).
  - Idle offline: "Offline". Recording offline: "N pieces waiting on this phone", the recording went on. Back online, the chip turned "Drive connected" once the outbox was empty.
  - No Drive token: "Connect Drive", no picker, the recording started at once. The uploader's silent refresh restored a token (the account had authorized SafeRec before).
  - Camera and microphone revoked: a tap on record showed the system dialogs, and the recording started by itself once allowed.
  - Three presses while locked, app opened 20 s later: the pill read 00:20 (counted from the session start). An audio-only quick start showed the dark "Audio only" surface and no camera switch.
  - `-Psaferec.powerGesture=false`: no three presses row or first-run step. It first crashed (`NoClassDefFoundError: Guard$Status`, from the synthetic `FirstRun$1` holding every enum switch map). Fixed, and re-tested with no crash.
  - Installing the switch-off build over the normal one resets the "Display over other apps" grant (the manifest no longer declares it); the chip then correctly showed "Three presses off".

### Power key presses

No public API reports power key presses. Each press turns the screen on or off, and Android broadcasts `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`.

- **The guard** (`GuardService`, module `guard/`) is a foreground service of type `specialUse` that registers a runtime receiver for both broadcasts. Its notification is permanent, minimum importance and secret on the lock screen.
- **The rule** (`TriggerRule`): N screen changes within 2.5 s plus 1 s per change past three trigger, then nothing for 5 s. N is the user's pick, 3, 4 or 5 (Settings, Quick start, "Presses needed"), 4 by default, read on every change. It counts changes in a window and does not measure the rhythm, because `SCREEN_OFF` arrives ~0.4 s after its press (after the screen-off animation): presses 0.37 s apart were measured as 737 ms and 66 ms.
- **Why an activity.** A service cannot enter the foreground with the camera or microphone type from the background (`SecurityException`). A visible activity can start that service, so the guard starts `QuickStartActivity` by component name, with source `power`.
- **Why it is allowed.** The user grants "Display over other apps" (`SYSTEM_ALERT_WINDOW`). With it, Android lets the guard start an activity from the background (`BAL_ALLOW_SAW` in the system log). Without it, Android does not throw: the guard logs `trigger refused: no overlay permission`.
- **Logs.** `Guard running`, `Guard stopped`, `Guard cannot start`.
- **Measured in a spike** (Galaxy S10+, Android 16, locked with a PIN, screen off): video recording 0.6 s after the third press, the trigger screen visible 30-54 ms after the trigger, the phone stayed locked.
- **Measured with the app** (same phone, presses by hand): it recorded with the trigger screen 30 ms after the trigger, and a second round during the recording was ignored (two pulses again). Two slow presses did nothing, and a camera double press (two presses 34 ms apart) opened the camera without triggering.
- **On a Pixel with Android 17** (presses sent over adb, 0.45 s apart, phone locked): the same. Microphone 0.17 s and camera 0.27 s after the trigger, the phone stayed locked, and a second round was ignored with two pulses. Its lock screen showed no SafeRec notification or icon. Presses by hand on a Pixel are not verified.
- **Screen changes that are not presses.** Nothing is filtered, not even during a phone call: a deliberate start must never be blocked. A camera double press (two presses under 300 ms) gives at most two changes and does not trigger the guard.
- **Why four by default.** With three, the owner's Pixel (Android 17) started recordings by itself. Its "Lift to check phone" lights the screen when the phone is picked up (`WAKE_REASON_LIFT` in the system log), so a lift, then off and on again, made three changes in 1.5 s. The broadcast carries no wake reason, and an app cannot read the system log, so no rule can tell that lift from a press. The owner's deliberate presses were 0.53 and 0.81 s apart, the same rhythm as that false start: a shorter window would have refused the real presses too. A fourth change is needed at four (the false start is a test case), and four deliberate presses at that rhythm take 2.8 s, the last off included. Still left: a lift, off and on, then off again within 3.5 s of the lift makes four (a test pins it).
- **No app can tell a lift from a press.** At four, a test on the Pixel still started one: a press off, the screen lit by itself 0.26 s later, off again, then lifted, four changes in 3.3 s from two presses. Android knows the reason (`WAKE_REASON_LIFT` against `WAKE_REASON_POWER_BUTTON`) and keeps it: `PowerManager.getLastWakeup()` exists only in `PowerManagerInternal`, inside the system server, `getLastSleepReason()` needs `DEVICE_POWER`, the power key never reaches an accessibility service, and from Android 13 the log needs the user's consent every 60 s, in the foreground only. The presses were switched off in 1.18 for this, and back on in 1.19 with the cure at the source: the setup tells the owner to turn off "Lift to check phone" and "Tap to check phone" (a Settings row under the presses, which opens the display settings, and a sentence on the first-run card). An app cannot read whether they are on: `doze_pick_up_gesture` is unset on the Pixel, so the device's default applies.
- **A toggle.** The same presses again stop the recording, with the long pulse: `QuickStartActivity` adds `EXTRA_TOGGLE` to its start when the source is `power`, and `StartPolicy` turns that start into a stop while recording. The owner asked for it to end a false start without unlocking. The tile, the shortcut and the camera press still only start (a second one confirms with two pulses). The costs: whoever holds the phone and presses the key that many times quickly stops the recording (the pieces already on Drive stay), and a second round pressed "to be sure" stops instead of confirming: the long pulse tells. The 5 s cooldown keeps a stop at least 5 s after the start.
- **Emergency SOS.** Android starts it at five presses, each under 500 ms after the last (`GestureLauncherService`); a gap of 500 ms or more starts the count again. So five presses in SafeRec can also call emergency services, and the choice says so. At four, a fifth quick press still can: SafeRec's pulses come 0.6-0.9 s after the fourth press, too late to stop a fifth one under 500 ms. Four presses, then stop.
- **The slowest rhythm.** At an even count from a dark screen the last change is an off, late by the screen-off animation: four presses need to be under about 0.9 s apart (3 × 0.9 s + 0.67 s ≤ 3.5 s). At three and five the last change is an on: up to 1.25 s and 1.1 s apart.
- **Costs.** A permanent notification, the overlay permission granted once, and a `specialUse` foreground service.

### Keeping the guard alive

On the S10+ the low-memory killer killed the app twice in a minute, with 0.5 GB free, and Android postponed the sticky restart by up to 165 s. Android 15+ does not let a service restarted in the background return to the foreground, so a killed guard would stay dead until the app opens.

- **Battery exemption.** The setup sends the user to the system page of battery optimization (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), not the direct dialog: that needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Play checks closely. An exempt app may return to the foreground from the background.
- **Watchdog.** `GuardWatchdog` is a periodic WorkManager job (`guard-watchdog`, 15 minutes, the minimum) that calls `Guard.ensureRunning()`. `ensureRunning()` also re-arms the job, which a restored or reset WorkManager loses.
- **Boot and update.** `GuardBootReceiver` restarts the guard on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- **Status** (`Guard.status()`): `DISABLED`, `NEEDS_OVERLAY`, `NEEDS_BATTERY_EXEMPTION`, `STOPPED`, `ACTIVE`, in the order the user has to fix them. The app says so when the power key presses are off.
- **Verified on a Galaxy S10+** (Android 16, with the battery exemption):
  - After a clean reboot the guard stayed off until the first unlock. Then `BOOT_COMPLETED` started it as a `specialUse` foreground service, 3.4 s after the unlock.
  - After an update (`adb install -r`), `MY_PACKAGE_REPLACED` started it in 0.26 s.
  - After a crash (`am crash`), the sticky restart brought it back in 1.5 s.
- Not verified: the guard during a phone call.

### Build switch

`saferec.powerGesture` in `gradle.properties` (default `true`; `false` in 1.18 only). With `-Psaferec.powerGesture=false` the `guard` module is `compileOnly`: nothing of it is packaged, so the APK has no guard manifest entries (the `specialUse` service and its permission, the overlay permission, the boot receiver), and the app shows no setting or first-run step for it (`BuildConfig.POWER_GESTURE`). It is in standby: no Release ships it since 1.19 (1.18 did), but CI runs `scripts/check-guard-refs.sh` on every push, so it keeps working if a store ever refuses the `specialUse` service.
- **Upgrading from a build with the guard.** Android drops the guard's service and receiver with the manifest, and resets the overlay grant. Two things would stay: the guard's watchdog job, which would wake the app every 15 minutes to fail on a missing class, and its notification channel. `MainActivity` cancels and deletes them at its start in this build (`clearGuardLeftovers()`, by name).

- App code touches the guard only inside `if (BuildConfig.POWER_GESTURE)`, which javac drops in that build. Two things leak out anyway: a lambda's body, and enum switch maps. One synthetic class (`FirstRun$1`) builds the maps of every enum switch of its class, so any of them loads `Guard.Status`: the first launch of the off build crashed with `NoClassDefFoundError`.
- So every switch over a guard enum lives in `ui/GuardSetup` (`GuardSetup.state()` gives ints to switch on), the only class allowed to name the guard. `scripts/check-guard-refs.sh` compiles the off build and fails on any other class that references the guard.

### Camera double press (optional)

The double press of the power key opens the default camera app with `STILL_IMAGE_CAMERA` (unlocked) or `STILL_IMAGE_CAMERA_SECURE` (locked).

- `CameraGestureAlias` is an activity alias for `QuickStartActivity` with both filters, **disabled** in the manifest. `CameraGesture.setEnabled()` turns it on when the user chooses so. A permanent declaration would show a chooser on phones with a second camera app, and on the lock screen that choice needs an unlock first.
- Android stores the "Always" choice per action, so each intent needs its own. "Try it now" therefore goes in two steps (`CameraGesture.tryIntents()`): `STILL_IMAGE_CAMERA` first, then, when the user comes back, a second dialog sends `STILL_IMAGE_CAMERA_SECURE`. With the first try only, the setup covered the unlocked phone: the double press of a locked phone still asked which app to use, a choice that needs the unlock, so nothing recorded. The try waiting for the user is a static field, `CameraGestureSetup.pendingTry`: a try that reaches SafeRec recreates `MainActivity` (`QuickStartActivity` opens it with `CLEAR_TOP`). Both `MainActivity` and `SettingsActivity` call `CameraGestureSetup.onResume()`, so the check runs on whichever screen the user comes back to.
- The manifest's `<queries>` lists both camera actions. Without it, package visibility (Android 11+) hid the camera app, `resolveActivity` always answered SafeRec's alias and the self-check said "On" with nothing stored (found on the S10+).
- Once the user picks SafeRec as "Always" for both, the double press records, locked or not. Undo: Settings, Apps, SafeRec, Open by default.
- The dialog says that trying it starts a short recording, which the user then stops (a second try while it runs is an ignored quick start), and that the lock screen's camera shortcut may start SafeRec too: it sends the same intents.
- The option reads "Double press of the power key opens SafeRec": "camera double press" did not say which key.
- It is offered only when SystemUI's `config_cameraGesturePackage` does not pin another camera app (`CameraGesture.isAvailable()`, `allowsOtherApps()`). If SystemUI cannot be read, it is offered, and "try it now" shows whether it works.
- **Verified on a Galaxy S10+** (Android 16, camera app Aperture):
  - Turning the option on enabled the alias. Each try showed Android's chooser, and "SafeRec" + "Always" stored that action.
  - Locked, screen off, the double press recorded 0.48 s after the press, and the phone stayed locked.
  - Turned off, both actions went back to the camera app, and the double press opened it.
  - Android plays its own short vibration for the camera gesture, ~0.45 s before SafeRec's two pulses.
- **The setup checks itself** (`CameraGestureSetup`). Android stores "Always" once per action, and only the user can choose it.
  - After each try, `CameraGesture.isDefaultFor()` asks `resolveActivity(intent, MATCH_DEFAULT_ONLY)` whether the action now opens the alias. It needs the camera actions in the manifest's `<queries>`, or other camera apps are invisible.
  - Tries already stored are skipped (the "Always" choices survive turning the option off), so turning it back on can need no try at all.
  - A try that is not stored gets "Try again". When all are stored the setup says it is done.
- **The last-choice trap.** When Android keeps another app as its last choice (not an "Always"), the chooser reads "Complete action using Camera" on every try and lists SafeRec under "Use a different app". Tapping SafeRec opens it just once and never moves the suggestion (3 tries on the phone). The camera app's "Open by default, Clear default preferences" removes only "Always" entries, so the user cannot clear it.
  - The way out: after two "not saved" results in a row for the same try, the option turns itself off (the double press goes back to the camera, no chooser behind the lock screen) and the dialog points to the power key presses (or the tile, in a build without the guard).
- Verified on a Galaxy S10+ (Android 16).
- On a Pixel with Android 17, SystemUI pins the gesture to Google Camera (`config_cameraGesturePackage`), so the option is not offered there: the row was absent.

## Recordings and the session screen

The list shows sessions, not chunks; one tap opens the session with its video and what can be done with it.

```
 RecordingsActivity                         SessionActivity
 +-------------------------------+          +-------------------------------+
 | 2 pieces waiting [Upload now] |  tap     | [ player: the pieces as one ] |
 | [thumb] Oct 20 10:14          | ───────► | status line + explanation     |
 |         1:32 · Video          |          | [Export evidence package]     |
 |         Complete              |          | [Share the video]             |
 | [thumb] Oct 19 21:02 ...      |          | [Move to trash]               |
 +-------------------------------+          | Technical details v           |
                                            +-------------------------------+
```

- **Session cards** (`SessionsAdapter`, `SessionSummary`). One card per session and type (video and audio of one session are two cards), newest first. The status comes from the manifest only: no readable manifest is "Not checked", any chunk the manifest lists that is on neither Drive nor this phone is "Incomplete", chunks still on this phone are "Uploading", otherwise "Complete". A card never says "Complete" without a manifest saying so.
- **Duration** is the first piece's start to the last piece's end. Drive's own duration of the last piece is used when it has one, else a usual chunk (10 s).
- **Thumbnail.** The first piece that has Drive's `thumbnailLink`, fetched with the token (`ThumbnailLoader`, 2 threads, in-memory cache of 1/16 of the heap) and shown instead of a placeholder icon. A failed picture keeps the placeholder: it is never an error. Audio sessions always show the placeholder.
- **Waiting banner.** With chunks in the outbox, a banner above the list shows "N pieces waiting on this phone" with "Upload now" (`CMD_UPLOAD_PENDING`), or "Connect Drive" when there is no token. The count is `SafeCount.pieces()`, as on the viewfinder.
- **List states.** No token: a message with "Connect Drive". Nothing recorded: "No recordings on Drive yet." Load failed: a message with "Retry" (a screen reader cannot pull), and pull to retry. The screen never closes itself. Each load has a number and only the latest draws. It reloads on resume, so a trash made on the session screen shows (`TrashedSessions` hides it meanwhile).
- **A token Drive refused** (HTTP 401, `DriveTokenRefresh`, on the list and on the session screen). A network error is not one: it stays the plain error. On a 401 the screen asks Play services for a new token silently (`connectDrive(false)`, then `DriveConnection.accept`), at most once per screen, and retries the failed operation once with it: the list load, the session load, the player, the export, "Share the video", "Share time stamp". `DriveRecordings.setAccessToken()` gives the new token to the screen's Drive client. A token stored meanwhile (another operation's refresh, or the user reconnected) is used without asking Play services. When Play services want the user, the screen shows its not-connected state with "Connect Drive". Before this, the session screen kept its first token until reopened, and the list showed a 401 as a network problem.
- **One session at a time.** Multi-select is gone, by the owner's choice: every action lives on the session screen.
- **The session screen** (`SessionActivity`). It loads one session by id (`DriveRecordings.session()`: its folders and files are asked for by name, so the whole list is never read). Top to bottom: the player, the status line with its explanation, "Export evidence package" (first, since it is the main purpose), "Share the video" ("Share the audio" for an audio session, shared as `audio/mp4`), "Move to trash" and "Technical details". "Not checked" says why: no list of pieces on Drive (an older version), or a list that could not be read ("Try again later"). The three actions run one at a time: the running one shows a spinner and the others are disabled.
  - **Export evidence package** loads the session again first (`DriveRecordings.session()`) and shows it, then runs `EvidenceExport.export()` on that fresh list: a piece uploaded since the screen opened is in the package. The package and its phone check are described in the Session Manifest section.
  - **Share the video** loads the session again too, then downloads the pieces, merges them (`MediaMerger`) and opens the share sheet. The cache copies are deleted afterwards.
  - **Move to trash** asks first ("Drive keeps it for 30 days"), then sends `CMD_DELETE` for every file of the session and its folders (see Trash above) and closes the screen.
  - **Technical details** (collapsed, announced as expanded or collapsed to screen readers): one row per piece with its size, a shield when it has a time stamp, and "Repaired after a cut, original kept" when it has a `.cut`. A tap plays from that piece. A long press on a stamped row offers "Share time stamp" (the `.tsa` file). A last row stands for the manifest, with its own shield, only when the session has one. Each row is one screen reader stop.
- **Its own load states.** Session gone from Drive: a message. Load failed: a message and "Retry". No token: a message and "Connect Drive" (consent, a check with Drive, then the load). The player and the status are hidden in all three.
- **Gallery button** (`MainActivity`). The viewfinder's button shows the newest session's thumbnail, from `DriveRecordings.latestSession()`: Drive sorts the session folders and sends the newest five, so the list is never read. A folder whose name is not a session id, or which holds no session (its files trashed: the folders stay), is passed over for the next. It loads at most once a minute, only online with a token, prefers the video folder, and keeps the plain icon on any failure (the viewfinder never reports it; the next resume tries again at once). A badge counts the pieces waiting on the phone, and the content description says so.
- **The tutorial overlay of the old list is gone** with the list it explained.

Verified on a Galaxy S10+ (Android 16):
- **List states.** Offline: the banner "3 pieces waiting on this phone" with Upload now, and "Could not load the recordings…" with Retry. Back online, Retry loaded the cards (status, duration, type, Drive thumbnails on sessions some minutes old, the video/audio icon on new ones).
- **A recording started while the list was open.** Three presses with the phone locked: after the unlock and a refresh its card read Uploading, and its screen "1 piece is still on this phone…".
- **Export while recording.** The export reloaded the session first (19 files instead of the 13 it opened with). The open chunk was uploaded during the export, so the dialog said the file is not on this phone either and to export again: correct for that timing.
- **Playback of a 4:38 session** (about 28 pieces) as one timeline: it played across piece boundaries with no stall, a tap at 45% of the bar landed at 2:07 and played, a technical row tap started at that piece (Piece 2 at 0:20), rotating to landscape went fullscreen by itself and back to portrait left it, Home and back restored the position paused.
- **A piece trashed on Drive while the screen was open still played**: Drive serves a trashed file to the app that made it, so the 404 skip covers files deleted for good. After a refresh the card and the screen read Incomplete, "1 piece is missing on Drive.", and the timeline left the piece out.
- **Token.** An invalid stored token with the session screen recreated after a process death gave a 401, "New token from Play services: VALID", and the session loaded. The viewfinder's gallery load hit the same 401 and the silent Drive check replaced the token.
- **Trash from the session screen.** Confirmation, 28 files to the Drive trash in 21 s, the session gone from the list at once and after a refresh. Its folders stay (they hold trashed files), as the `CMD_DELETE` rule says.
- **Share.** "Share the video" merged the pieces and opened the share sheet; an audio session reads "Share the audio".
- **Start path.** With the microphone revoked, a tap on record, the screen recreated under the permission dialog (`cmd uimode night`), then Allow: exactly one start. Denied: no start, the banner "The microphone is needed to record." and a usable button. An external START (`--es command START`) with the microphone revoked showed the dialog, and Allow started exactly once.
- **`r8test`**: the list, playback across pieces and an evidence export (COMPLETE, shared at once) ran with no crash. Then the debug build went back.
- **Fixed after this run:**
  - A session recorded while the list was open was inserted above the viewport, so the list looked unchanged: a load that finds the list at the top now scrolls to the top.
  - The back arrows of the list and the session screen had no description: they read "Back" like Settings.
  - The export spinner was a barely visible dot: it is sized by hand (20 dp) and sits before the label.
  - Landscape fullscreen left a gray strip on the cutout side: fullscreen drops the system-bar and cutout padding.
  - Technical rows show seconds (the pieces of one minute read the same), in the phone's locale.
  - An audio session showed a black player: it shows the `ms_graphic_eq` artwork, dimmed.
  - The list took 14-20 s with 34 sessions, since `DriveRecordings.markGaps` read the manifests one by one: four threads read them now, with the same results (an unreadable manifest still leaves its session unchecked). The session screen and the gallery share it.
  - Checked again on the S10+: a session recorded with three presses while the list was open showed at the top after a refresh; both back arrows read "Back"; the export spinner shows (black on the disabled button, low contrast); landscape fullscreen runs edge to edge; an audio session shows the artwork. Not checked again: the seconds in the rows, and the list time with many sessions (only 7 were left on Drive).
- **Not verified**: unplugging earphones during playback (cannot be simulated over adb), a rotation in the middle of a recording played upright piece by piece (needs hands), TalkBack's spoken output (content descriptions checked with uiautomator only).

---

## Data Flow

### File Storage & Naming

```
/data/data/net.ark3us.saferec.app/files/
  ├── recording/                                      ← chunk being written, with its sample log (.idx)
  ├── repair/                                         ← cut chunks taken from recording/ for their repair
  ├── manifests/{sessionId}_{video|audio}.txt         ← session manifest masters, kept
  └── data_store/                                     ← outbox, ready files only
      ├── {sessionId}_{video|audio}_{timestamp}.{seq}.mp4 / .tsa / .cut / .cut.tsa
      └── {sessionId}_{video|audio}_{sessionId}.0.manifest / .manifest.tsa  ← copies to upload
```

`sessionId` = `System.currentTimeMillis()` at session start.

### Google Drive Structure

```
My Drive/
  └── SafeRec/
      └── {sessionId}/
          └── {video|audio}/
              ├── {timestamp}.{seq}.mp4        ← chunk (or the repaired copy of a cut one)
              ├── {timestamp}.{seq}.tsa        ← its RFC 3161 timestamp (timestamping on)
              ├── {timestamp}.{seq}.cut        ← a repaired chunk's untouched original
              ├── {timestamp}.{seq}.cut.tsa    ← the original's timestamp, taken at recovery
              ├── {sessionId}.0.manifest       ← session manifest, one copy, updated in place
              └── {sessionId}.0.manifest.tsa   ← its timestamp, at stop and after a recovery
```

Chunks are uploaded immediately on completion and **deleted locally** on successful upload. When duplicate `SafeRec` folders exist (made by older builds), uploads go to the oldest and the recordings list reads all of them.

---

## Threading Model

| Thread | Components |
|--------|-----------|
| **Main (UI)** | MainActivity, RecordingsActivity, SessionActivity, service commands |
| **Thumbnails** (2 threads) | `ThumbnailLoader`: Drive thumbnails, one loader per screen |
| **Token check** (1 thread) | `DriveTokenRefresh`: Drive's check of a refreshed token, one per screen, so it never waits behind an export |
| **Media3 playback** (ExoPlayer's own thread) | The session player: reads the pieces from Drive and decodes them |
| **CameraBackground** (HandlerThread) | Camera2 callbacks, MediaCodec encoder callbacks (video) |
| **Audio processing** (Thread) | `AudioStreamRecorder.processAudio()` loop |
| **Upload pool** (4 threads) | `FileUploader.uploadExecutor` |
| **Upload scan** (1 thread) | `FileUploader.scanExecutor`: outbox scans and the scheduled retry rescan |
| **WorkManager** (its own pool) | `OutboxWorker.doWork()`: blocks in `drain()` while the upload pool does the uploads |
| **Network callback** (system thread) | `SafeRecService.networkCallback`: `retryNow()` when the default network changes, gains a transport or turns `VALIDATED` |
| **Service executor** (1 thread) | `SafeRecService.executor` for pending uploads |

### Thread Safety Notes

- [MuxerSink](app/src/main/java/net/ark3us/saferec/media/MuxerSink.java): all public methods are `synchronized`
- `VideoStreamRecorder.muxer` and `.isRecording`: `volatile` for cross-thread visibility
- `VideoStreamRecorder.cameraOpenRequestCount`: `AtomicInteger` generation guard against stale async camera-open callbacks
- `AudioStreamRecorder.isRecording`: `volatile`, checked in processing loop
- `GoogleDriveFileUploader.sessionFolderIdCache`: `ConcurrentHashMap`
- `GoogleDriveFileUploader.cachedBaseFolderId`: `volatile` + double-checked locking
- `GoogleDriveFileUploader.refreshLock`: one token refresh at a time. `GoogleDriveClient.accessToken`/`driveService` are `volatile`, because a refresh replaces them while uploads run
- `FileUploader.queuedFiles`: concurrent set; a file is queued at most once, so a pending-files scan cannot upload a chunk that is already in flight (that used to create duplicates on Drive)
- `WorkDir`: one per process; its lock covers the recording counter and the recovery moves
- `GoogleDriveFileUploader.getInstance()`: `static synchronized`, one uploader per process, so the dedupe and the folder lock above also hold between the service and the outbox job
- `FileUploader.listener` and `.retryLoop`: `volatile`, set by the service on the main thread and read by the upload threads

---

## Release Build

R8 shrinks and optimizes the release (`isMinifyEnabled`, `isShrinkResources` in `app/build.gradle.kts`). The APK went from 16.8 MB to 3.9 MB.

- **Rules.** The libraries bring their own. google-api-client fills its model classes (Drive `File`, `FileList`, `Permission`, error replies) by reflection on `@Key` fields: those rules ship with play-services-auth. WorkManager creates workers by class name, and Room and androidx.startup are covered too. `app/proguard-rules.pro` adds two things only:
  - line numbers in stack traces;
  - the app's own class names (`-keepnames`). The source is public, so renaming hides nothing, and it would turn the log tags (`Class.getSimpleName()`) into single letters. Unused code is still removed.
- **Each Release carries its `mapping.txt`** (`SafeRec-<version>-mapping.txt`, R8's `app/build/outputs/mapping/release/mapping.txt`). Without it, library frames in a crash report cannot be read.
- **`r8test` build type.** It is the release, shrunk the same way, but signed with the debug key. Because it shares the debug signature:
  - it installs over the debug app (`adb install -r`) and keeps its data;
  - the Google consent still works, since OAuth knows the debug SHA-1. A release-signed build would need the debug app uninstalled first.

  It is not debuggable, so `run-as` and `scripts/device-tests.sh` do not work with it. Change the settings with the debug build before installing it, and put the debug build back afterwards (`adb install -r -t`).
- **Verified with `r8test` on a Galaxy S10+**, with no crash:
  - the Play services authorization and the Drive token check;
  - a recording uploaded with its TSA timestamps (BouncyCastle);
  - the session cards, playback of a session as one timeline across its pieces, and the evidence export from the session screen (verified as in Recordings and the session screen);
  - the settings screen;
  - the outbox job;
  - an evidence package and its phone check: signatures and certificate chains (BouncyCastle, `CertPathBuilder`) said COMPLETE, as in the debug build.

  Check these again after adding a library or anything that uses reflection.
- **Media3** (ExoPlayer and its UI, 1.11.1) plays the sessions. It ships its own consumer rules, so `proguard-rules.pro` has nothing for it. Both R8 builds above (normal and guard off) passed with no warning about `androidx.media3`. The `r8test` APK is 4.7 MB with Media3.
  - Checked on the phone with `r8test`: a session plays across its pieces, and an evidence package exports, with no crash.
- **The build without the guard** (the switch in standby):
  - `bash scripts/check-guard-refs.sh` runs in CI and before every release (`scripts/ci-checks.sh`): no class but `ui/GuardSetup` references the guard, so that build cannot crash on a missing guard class;
  - after touching R8 rules or the guard, also run `bash ./gradlew :app:minifyR8testWithR8 -Psaferec.powerGesture=false`: R8 accepts the build without the guard module and strips `GuardSetup`.

  Both build into `app/build/` with the switch off: build the normal variant again afterwards.

### Distribution on GitHub

SafeRec left Google Play. A tag `v<versionName>` becomes a GitHub Release with `SafeRec-<version>.apk`, `SafeRec-<version>-mapping.txt` and `SHA256SUMS`.

```
 scripts/release.sh 1.17                          GitHub Actions
 ───────────────────────                          ──────────────
 ci-checks.sh, signed build, certificate  ──►     release.yml, tag v1.17
 "Version 1.17" + tag v1.17 + push                 build    (no secrets): versionName == tag, tag on main,
                                                            ci-checks.sh, unsigned assembleRelease
                                                   publish  (environment "release"): apksigner, certificate,
                                                            SHA256SUMS, draft Release, then published
```

- **Application id `net.ark3us.saferec.app`.** Android developer verification binds a package name to the SHA-256 of its signing certificate; from 2027 (Brazil, Indonesia, Singapore and Thailand since 2026-09-30) a certified phone installs only registered apps. `net.ark3us.saferec` was published on Play with Play App Signing, so the only key Android knew for it was Google's, which cannot be downloaded: the Android Developer Console answered "already in use on Android" with no eligible key. The new id is registered there with the release key. `namespace` and the Java packages stay `net.ark3us.saferec`. `shortcuts.xml` holds the id by hand (`ApplicationIdTest` checks it).
- **The release key** is `keystore.jks` (alias `cert`, not in git), the former Play upload key. SHA-256 `0A:54:1D:9A:8D:FC:3C:06:EE:9E:C2:31:BD:D3:DE:30:7A:D9:32:8C:FA:82:AA:0B:A5:3A:D6:B2:47:EC:52:BC` (Developer Console, `scripts/check-apk-cert.sh`), SHA-1 `EF:74:3F:7A:22:DE:D3:19:8D:55:70:D1:90:55:86:92:1C:C3:5E:9E` (the release Android OAuth client; debug builds use the debug OAuth client of the same id). It cannot be replaced without registering a new id: keep a copy offline.
- **CI** (`.github/workflows/ci.yml`): `scripts/ci-checks.sh` on every push and pull request, with no secrets. It runs the unit tests, lint, `check-guard-refs.sh` and the Python tests of `scripts/`.
- **Gradle never sees the key on GitHub.** `-Psaferec.unsignedRelease=true` drops the release signing config; the `publish` job, which runs no Gradle, signs with `apksigner` (`--ks-pass env:`, the keystore decoded into `$RUNNER_TEMP` and deleted even on failure). The secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD` live only in the environment `release`, whose rule admits tags `v*`: a branch, a pull request or a fork never reads them. Locally Gradle signs from `local.properties` as before.
- **`scripts/release.sh <version>`** (`X.Y` or `X.Y.Z`, greater than the current one) refuses to run off `main`, behind `origin/main`, with anything staged, with local changes to `app/build.gradle.kts`, or when the tag exists. It runs `ci-checks.sh`, a signed `assembleRelease` and `check-apk-cert.sh` before touching anything, so a broken build or a wrong key never burns a version number. Then it commits only `app/build.gradle.kts` ("Version X"), tags `vX` (annotated) and pushes both atomically; a failed push undoes the commit and the tag locally.
- **A failed release run.** A passing glitch or a fixed secret: re-run the job on GitHub. A fix in the code: commit and push it, then `scripts/release.sh --retag` moves the tag of the current version to `HEAD`, only while GitHub holds no Release or draft for it (`gh release list`; a failing `gh` refuses too). A published Release never moves.
- **Publishing** (`scripts/publish-release.sh`). `publish` refuses a tag that already has a Release or a draft, so a re-run after publishing fails and adds nothing. `gh release create` (no `--draft`) keeps the Release a draft while it uploads, publishes it, and deletes its own draft when an upload fails. On a failure or a cancellation, `clean-drafts` deletes the tag's drafts **by id**: a tag can have a published Release and a draft at once, and `gh release view/edit/delete <tag>` may pick either, so a clean-up by tag name could delete the public Release. The v1.17 run predates this (it used `--draft` and then `edit --draft=false`): never re-run it.
- **Verified on the Galaxy S10+** (2026-10-21, the published `SafeRec-1.17.apk`):
  - `sha256sum -c SHA256SUMS` and `check-apk-cert.sh` passed on the downloaded assets; the publish job's log holds no password and no part of the keystore (searched for the real values);
  - it installed over a locally signed release build (same key) and kept its Drive token;
  - Drive sign-in with the release OAuth client worked once Google had activated the new clients, about an hour after they were created (`UNREGISTERED_ON_API_CONSOLE` until then, also after a reboot);
  - a 31 s recording uploaded its pieces and manifest, and the list showed it together with the sessions of the old package: `drive.file` grants access per Cloud project, and the uploader used the existing `SafeRec` folder (`Found existing folder`), no second one;
  - not verified: the debug build's Drive sign-in with the debug OAuth client (it failed before the activation and was not retried), `scripts/device-tests.sh` (`run-as` fails for every app on the phone's AxionOS build, the old id too), and an install from the browser under Android's developer verification (not enforced in Italy before 2027).
- **Release notes** are the commit subjects since the previous tag (`scripts/release-notes.sh`): GitHub's generated notes list merged pull requests, and changes reach `main` without them.
- **Actions** are pinned to full commit SHAs, with the tag in a comment: a tag of a third-party action can be moved. Update them by resolving the new tag with `gh api repos/<owner>/<repo>/commits/<tag> --jq .sha`.

---

## Known Limitations

1. **Camera switch = recording gap.** Switching cameras mid-session does a full stop+restart, creating a new muxer/session segment.
2. **Mixed-rotation merge.** A single MP4 can only have one `setOrientationHint`. When chunks from different cameras (with different sensor orientations) or different device rotations are merged, some segments will display with wrong rotation. This now applies to the merged video ("Share the video") and the evidence package's `merged.mp4` only: playback plays each piece with its own orientation.
3. **No video mirroring.** Front camera video is recorded un-mirrored (you see yourself as others see you, not as a mirror reflection). This is standard for video recording.
4. **Background Auth Failure.** If Drive rejects the access token (HTTP 401) during background upload, the app attempts a silent token refresh via Google Identity Services. If successful, the upload seamlessly resumes. If the refresh requires user interaction (e.g., token revoked or password changed), it reports `ERROR_AUTH`, prompting the user to re-authenticate, and keeps the token and the files. Later retries try the silent refresh again (at most once a minute), so uploads resume once Play services can issue a token. The recording keeps going.
5. **Slow failure behind a VPN.** With a VPN app keeping its tunnel up while the phone has no network, packets vanish and each Drive call fails only after its 20 s timeout instead of at once. Uploads that were already running when the network dropped fail when it returns, and are retried.
6. **Preview rebuild failure drops video.** `onConfigureFailed` during `attachPreview`/`detachPreview` is handled like a camera loss: the rest of the session is audio-only.
7. **A/V sync is as good as the capture timestamps.** Both tracks share `CLOCK_BOOTTIME`, but MediaMuxer does not signal the AAC encoder delay, so audio plays ~20-45 ms late. That is within lip-sync tolerance. Audio clock drift (sample rate vs system clock, typically <100 ppm) is not corrected within a session: <0.36 s per hour in the worst case.

   Measured with the `av_sync` vibration rig on a Galaxy S10+ (the rig adds its own constant, so compare values, not the absolute):
   - After a camera switch (cold camera start): +71..98 ms, versus +540..613 ms before the shared clock.
   - In a merged 50 s file: a slope of +0.9 ms/min, versus -53.5 ms/min before the fix.

   Before the shared clock, each track was normalized to its own first packet. Audio was off by the camera start delay (0.2 s with the preview open, ~1 s cold), and 1-3 audio frames were dropped at every chunk boundary, so merged files drifted further with every chunk.

8. **drive.file reaches only the app's own files.** If the `SafeRec` folder holds a file the app did not create (an older build with another OAuth client, or a file added by hand), Drive refuses any change to the folder's sharing with `appNotAuthorizedToChild`. That covers sharing it from the app and removing a public link that older builds created. Both must then be done in the Google Drive app.
9. **Sharing is per person.** The share button grants one Google account read access to the whole `SafeRec` folder, future sessions included, and Google emails that person. The app never creates "anyone with the link" access, and it cannot revoke a share: that is done in Drive.
10. **Android decides when the outbox job runs.** The delayed job of an app in the background can be held for over half an hour. The expedited one covers the cases the app sees coming (service stopped, boot), so this matters after a kill without notice: those chunks wait for that delayed job or the next app launch. In Doze jobs wait for a maintenance window, an app the user rarely opens gets fewer runs (App Standby buckets) and less expedited quota, and after repeated failed runs the job waits up to 5 h. A force stop cancels the jobs until the app leaves the stopped state. After a reboot nothing runs before the first unlock, because the outbox is in credential-encrypted storage. While the service lives, none of this applies.
11. **A sudden power cut still costs the last moment.** Without a clean shutdown (a battery that fails before Android's ~1% shutdown, a kernel crash), the open chunk is cut. It is repaired from its sample log and uploaded after the boot without opening the app, but the last ~0.5 s that MediaMuxer held in memory is lost. On a real power loss, so is what had not reached the flash yet. Without a usable log the cut chunk is uploaded as it is, and plays only after repair tools.
12. **The power key presses depend on the guard being alive.** The guard is off after a reboot until the first unlock (its boot receiver is not direct-boot aware and the app's data is credential-encrypted). The low-memory killer can kill it, and Android postpones its restart. The watchdog brings it back within 15 minutes, only with the battery exemption. The tile, the shortcut and the app always work.
13. **Updates are manual.** Without a store, nothing updates SafeRec by itself: users download each new APK from GitHub Releases, or use an app that watches them (Obtainium).

---

## Testing on a Device

Unit tests (JVM, no phone, under a second): `./gradlew testDebugUnitTest`. The session verifier's tests: `uv run --with pytest --with certifi pytest scripts/test_verify_session.py -q --basetemp=tmp/pytest`. Everything CI runs: `bash scripts/ci-checks.sh`.
- `ApplicationIdTest`: the application id is the registered one, and `shortcuts.xml` targets it.
- `scripts/test_check_apk_cert.py`: the certificate check with a fake `apksigner` printing the format of build-tools 36 (`Signer #1 certificate SHA-256 digest:`) and of 37 (`V3.0 Signer: certificate SHA-256 digest:`, one line per scheme): the release key alone passes; another key, a second signer or rotated key, no certificate, or an APK that does not verify fail. The first release run failed on GitHub because the check knew only the old format (it failed closed: no Release).
- `scripts/test_publish_release.py`: `publish-release.sh` with a fake `gh` holding the Releases: a new tag is published once (without `--draft`), a tag with a published Release or a draft is refused with nothing created, and `clean-drafts` deletes only that tag's drafts, by id.
- `scripts/test_release.py` (`uv run --with pytest pytest scripts/test_release.py -q --basetemp=tmp/pytest-release`): `release.sh`, `app-version.sh` and `release-notes.sh` in a throwaway clone with a bare origin, with stubs for Gradle, the checks and `gh`. It covers the bump, commit, annotated tag and push; malformed and not-greater versions; a failing check; every refused git state; unrelated local changes left out; a failed push undone; `--retag` with no Release, with one, with a failing `gh`, and with no tag; the notes of a first and of a later release.
- `VideoOrientationTest`: quadrant snapping with hysteresis, hint for back and front cameras.
- `ChunkPolicyTest`: 10 s auto chunks whatever the size, manual size only.
- `MediaFileTest`: chunk, `.tsa`, `.cut` and `.cut.tsa` names, Drive names, foreign names rejected.
- `TsaClientTest`: SHA-256 against the FIPS test vector.
- `WorkDirTest` covers:
  - leftovers moved and empty ones dropped, nothing moved while a recording uses the directory;
  - a camera switch (two recordings overlapping), an extra release;
  - a chunk with its log repaired with the original kept as `.cut`, a failed repair sending the original;
  - a repair cut short starting over, leftovers and stray logs dropped;
  - timestamps made before the outbox going along with their chunk, an unrepaired chunk timestamped as it is, a timestamp left in `repair/` sent as it is.
- `StartPolicyTest`: a start while recording is ignored unless it toggles (then it stops; a toggling start while idle starts), a camera switch restarts only while recording, unknown commands do nothing, a refused camera records sound only.
- `PermissionsBannerModelTest`: the banner's text for each mix of mode, grants and refusals, and when a start asks first (never for the camera in Audio mode, once per screen in Video mode).
- `SafeCountTest`: only the `.mp4` chunks of the current session count as safe, and only `.mp4` chunks count as pieces waiting (not `.tsa`, `.cut`, `.cut.tsa` or the manifest).
- `SessionSummaryTest`: the status of a session (complete, uploading, incomplete, not checked, also with an unreadable or empty manifest, told apart from no manifest), duration from the starts and the last piece (a usual chunk when Drive does not know it), the first thumbnail, the technical files of a piece, every file and folder id, a session with only a manifest, newest first, video and audio as two summaries.
- `SessionPlaylistTest`: the pieces in order, no playlist without pieces, placeholder lengths (Drive's, else 10 s), where a piece starts, the piece a failed request was for, the Drive media URL.
- `DriveTokenRefreshTest`: only a 401 is an auth error (not a 403, a 500 or a network error), found behind a wrapper.
- `SessionsAdapterTest`: the duration words (minutes and seconds, hours from one hour).
- `TrashedSessionsTest`: a trashed session hidden right away and for its type only, visible again after the window, one never hidden is visible.
- `StatusModelTest`: which chip shows for each mix of Drive, network, permissions and guard state.
- `HoldToStopTest`: the 0.5 s hold completes or cancels on the clock.
- `RecordGestureTest`: a touch acts on the state it began in (releasing a hold never restarts).
- `CameraGestureSetupTest`: the double press setup steps, including `isStuck`.
- `FirstRunStepsTest`: which first-run cards show and when they are done.
- `StartPolicyTest` also covers `replaysStarted` (no replayed "started" within 1 s); `TriggerRuleTest` has more window and cooldown cases.
- `scripts/check-guard-refs.sh`: compiles the debug classes with `-Psaferec.powerGesture=false` and fails if any class but `ui/GuardSetup` references the guard (a lambda body or an enum switch map loads `Guard$Status` and crashes the off build). Run it after touching code that references the guard.
- `CameraGestureTest`: the gesture is offered unless another camera app is pinned.
- Module `guard` (`bash ./gradlew :guard:testDebugUnitTest`): `TriggerRuleTest` (3, 4 and 5 changes within their window and at its end, the cooldown, the Pixel's lift then off and on ignored at four, the owner's measured presses triggering at four, a new count applied to the changes already seen), `GuardPressesTest` (3 to 5 kept, anything else is the default, 4) and `GuardStatusTest` (the order of the statuses).
- `SampleJournalTest`: round trip, a record cut short, a foreign file.
- `EvidencePackageTest`: the zip's layout (Drive's, under `evidence/`), videos stored and the rest deflated, files byte for byte, a package without merged video, the report's lines, day-old files deleted.
- `SessionCheckTest`: the verifier's cases, with a test authority made with BouncyCastle (a root and a timestamping certificate). It covers:
  - a complete session, a missing, altered or never-closed chunk, a repaired chunk with its original;
  - lines added after the manifest's timestamp, an edited manifest;
  - another file's timestamp, a tampered signature, an untrusted authority;
  - no timestamps at all, a chunk outside the manifest, no manifest.

  Nine rules are checked by mutation (hash, signature, chain, imprint, manifest prefix, later lines, never closed, original, any token).
- `SessionManifestTest`: line format, the last state of each chunk (a late `started` never undoing it), gaps split into on this phone and lost, no gap in a complete session, the manifest and its timestamp updated in place.
- `ChunkIndexTest`, on synthetic chunks laid out like MPEG4Writer's:
  - every sample of a cut chunk found in place;
  - a complete chunk left alone;
  - a log behind the file never placing a sample wrong;
  - video that does not match the log never taken as video.

  The `WorkDir` and `ChunkIndex` rules are checked by mutation.
- `FileUploaderTest`: the outbox logic with a fake uploader and millisecond backoff. It covers:
  - queuing once while uploading;
  - retry from the outbox, and no retry with the loop off;
  - a success releasing waiting files at once, and `retryNow()`;
  - `drain()`: empty outbox, a failure, waiting for an upload already running, chunks recorded meanwhile, stopping;
  - repeat uploads: a new file's first upload goes straight, a retry and a file found in the outbox ask the server first;
  - a finished upload reported to the listener.

  Breaking the dedupe, the success trigger, the loop switch, any `drain()` rule or the repeat check fails the matching test (checked by mutation).


`scripts/device-tests.sh` drives a connected phone (debug build) and checks logcat, the chunk store and chunk durations with ffprobe. Scenarios: `offline_start`, `audio_only`, `camera_stolen`, `replay`, `errors_dont_stop`, `network_drop`, `retry_after_outage`, `outbox_job`, `tsa`, `av_sync`, plus a whole-run crash check. It toggles airplane mode, records camera and mic, rewrites SafeRec settings, and restores everything on exit. It refuses to run if the outbox or `recording/` is not empty.
- `ANDROID_SERIAL` must name the test phone, or the script refuses to run. A manual test script, which did not pin the device, once ran on the owner's everyday phone plugged into the same cable. It installed SafeRec there (in the Private space too: `adb install` installs for every user), cleared the log buffer and toggled airplane mode. That copy stayed there, unused, until a later check found it. After testing on a phone that is not the test phone, uninstall with `adb uninstall` (every user) and check each user with `pm list packages --user N`.
- Dismissing Google's account picker does not keep a test off Drive. If the phone's account gave SafeRec access before, the silent refresh gets a token without asking, and the chunks upload. A test on such a phone leaves its sessions on that Drive: trash them afterwards.

- `av_sync` checks sync on the content.
  - It switches camera at 3 s (a cold camera start), then vibrates the phone with 80 ms pulses at seeded irregular times. Long pulses (250 ms) failed in dim light: long exposures show motion mostly when the motor starts and stops, and the two make rival correlation peaks ~0.25 s apart. The motor is one physical event seen by both tracks: the buzz reaches the microphone and the shake moves the image.
  - `scripts/av_lag.py` cross-correlates the buzz envelope with the motion on textured pixels, per chunk.
  - It records 50 s and measures every chunk after the switch with a clear correlation peak. It passes when the median lag is within ±250 ms and at most one chunk is more than 40 ms from it. One chunk can lock onto a stray movement or noise, which once failed a run that two reruns passed. It skips when the camera used after the switch sees too dark a scene (the phone must lie so that camera sees a lit room).
  - Its own FAIL on the pre-fix build (+540..570 ms) confirmed it catches the bug.
- `outbox_job` records offline and then ends the service the way Android does. It shortens the `dataSync` time limit to 20 s (`device_config put activity_manager data_sync_fgs_timeout_duration`, removed afterwards), so `onTimeout()` stops the service. The limit must be set before the stop: Android fixes it when `dataSync` starts. Back online, it waits for the job's own run (nothing forced; the expedited job from `onDestroy()` goes within seconds) and checks that the job uploaded every chunk with no service alive.
  - The process is not killed. `run-as kill` is forbidden by SELinux, and `am force-stop` cancels the job.
  - `am kill` is logged as a user request (`REASON_USER_REQUESTED`), and WorkManager takes that for a force stop. Its rescheduling finished the system job while the worker still ran. Android 16 then cut the network of the job-less background process after 5 s ("Software caused connection abort"). A real low-memory kill is logged differently.
- Reading JobScheduler: WorkManager jobs live in a namespace, so `dumpsys jobscheduler` lists them as `JOB androidx.work.systemjobscheduler:u0aNNN/ID` (no `#`). `cmd jobscheduler get-job-state -n androidx.work.systemjobscheduler net.ark3us.saferec.app ID` prints `waiting`, `ready` or `running`. The dump also shows the app's network, e.g. `blocked=REASON_APP_BACKGROUND`, which Android 16 applies to a background app without a running job. `am force-stop` cancels every job, so inspect before any cleanup that force-stops.
- Media scenarios also check that no frame is dropped at a chunk boundary (`Skipping … frame`), and that the first video frame is 0-1000 ms old on the shared clock (proof that both tracks use one clock).
- Checks read the host-side logcat capture (`full.log`), not the device buffer: failed uploads log long stack traces and the device buffer silently drops old lines.
- Drive scenarios (`errors_dont_stop`, `network_drop`, `retry_after_outage`, `tsa`) need a Google consent given once in the app. The OAuth client only accepts builds whose signing SHA-1 is registered in the Cloud Console, so a new dev machine's debug key must be added there first.
- Airplane mode is not an outage when a VPN app keeps its tunnel up. On Android 13+ the script also blocks SafeRec's own traffic: `cmd connectivity set-chain3-enabled` plus `set-package-networking-enabled`, restored on exit and reset at the next setup.
- The Recordings screen lists the account's real recordings. Multi-select is gone: a UI test opens only the session it recorded and trashes it from its own screen, never another card: a test once moved the owner's own recordings to the trash (restored, thanks to the trash).
- `run-as` processes have no network permission, so `run-as … nc` or `curl` says nothing about the app's connectivity. Compare `/proc/net/dev` counters instead.

Lesson learned: video encoders emit SPS/PPS as a `BUFFER_FLAG_CODEC_CONFIG` buffer with PTS 0. Writing it as a sample made 0 the video time base and silently dropped all audio after the first chunk. Codec config buffers must never reach `MuxerSink`.

Lesson learned: `findFolderId` used to return null both for "no such folder" and for a timeout, so a lookup during an outage created a second `SafeRec` folder. A token-refresh retry also resolved folders outside the uploader's lock, so two threads built the same session folders in parallel. Lookups now throw on errors, and only `getOrCacheSessionFolderId` creates folders.
