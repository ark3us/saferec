# SafeRec — Application Architecture

> **Package:** `net.ark3us.saferec`
> **Platform:** Android (Camera2 API, MediaCodec, MediaMuxer)
> **Purpose:** Background audio/video recording with chunked upload to Google Drive

---

## High-Level Architecture

```mermaid
graph TD
    subgraph UI["UI Layer"]
        MA["MainActivity"]
        RA["RecordingsActivity"]
        PA["PlaybackActivity"]
        PM["PermissionsManager"]
        TO["TutorialOverlayView"]
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
        FU["FileUploader (abstract)"]
        GDFU["GoogleDriveFileUploader"]
        GDC["GoogleDriveClient"]
        FD["FileDownloader (abstract)"]
        GDFD["GoogleDriveFileDownloader"]
        MF["MediaFile"]
        FTSA["FreeTSAClient"]
    end

    subgraph Data["Data / Config"]
        LD["LiveData (singleton)"]
        S["Settings"]
    end

    MA -- "startForegroundService(CMD_START/STOP)" --> SRS
    SRTS -- "launches MainActivity" --> MA
    SRS --> VSR
    SRS --> ASR
    SRS --> MS
    VSR --> TR
    TR --> MS
    TR -- "draws to" --> VSR["VideoStreamRecorder preview"]
    MS -- "on chunk complete" --> SRS
    SRS -- "timestamp/upload" --> FTSA
    SRS -- "upload" --> GDFU
    FTSA -- "construct TSQ" --> GDFU
    GDFU --> GDC
    MA -- "observes status" --> LD
    SRS -- "updates status" --> LD
    RA -- "list/download/delete/share" --> GDFD
    RA -- "merge chunks" --> MM
    SRS --> NH
```

---

## Package Structure

```
net.ark3us.saferec/
├── MainActivity.java          # Main UI, preview, start/stop, settings
├── RecordingsActivity.java    # Browse/download/merge/share recordings from Drive
├── RecordingsAdapter.java     # RecyclerView adapter for recordings list
├── PlaybackActivity.java      # Video playback (streaming from Drive)
├── PermissionsManager.java    # Runtime permissions + Google Drive OAuth
│
├── data/
│   └── LiveData.java          # Singleton observable for service status
│
├── media/
│   ├── VideoStreamRecorder.java  # Camera2 + MediaCodec video encoder (singleton)
│   ├── AudioStreamRecorder.java  # AudioRecord + MediaCodec AAC encoder
│   ├── MuxerSink.java            # MediaMuxer wrapper with chunking + upload
│   ├── MediaMerger.java          # Merges MP4 chunks into a single file
│   └── TimestampRenderer.java    # OpenGL ES 2.0 pipeline for burned-in timestamp overlay
│
├── misc/
│   └── Settings.java          # SharedPreferences wrapper
│
├── net/
│   ├── FileUploader.java         # Abstract uploader with thread pool
│   ├── GoogleDriveFileUploader.java  # Drive upload implementation
│   ├── GoogleDriveClient.java    # Low-level Drive API wrapper
│   ├── FileDownloader.java       # Abstract downloader
│   ├── GoogleDriveFileDownloader.java  # Drive download implementation
│   ├── MediaFile.java           # Chunk filename parser/generator
│   └── FreeTSAClient.java       # Timestamp Authority (TSA) client (Sectigo)
│
│
├── services/
│   ├── SafeRecService.java       # Foreground service orchestrating recording
│   ├── SafeRecTileService.java   # Quick Settings tile to start recording
│   └── NotificationHelper.java  # Notification channel + builder
│
└── ui/
    └── TutorialOverlayView.java  # First-run tutorial overlay
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
    SRS->>MS: new MuxerSink(baseDir, uploader, dataType, sessionId, chunkSize)
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
    SRS->>SRS: updateNotification(), LiveData → STATUS_STARTED
```

### 2. Chunking & Upload (during recording)

```mermaid
sequenceDiagram
    participant VSR as VideoStreamRecorder
    participant MS as MuxerSink
    participant GDFU as GoogleDriveFileUploader

    VSR-->>MS: writeVideoData(buffer, info)
    MS->>MS: (bytesWritten >= partSize && keyFrame) OR device rotated?
    alt Chunk complete
        MS->>MS: rotate() → closeFile() + onFileSaved(completedChunkFile)
        MS-->>SRS: onFileSaved() callback
        alt Timestamping enabled
            SRS->>FTSA: timestampFile(chunkFile, tsaFile)
            FTSA->>FTSA: HTTP POST to Sectigo (Background Thread)
            FTSA-->>SRS: success/fail
            alt Success
                SRS->>GDFU: upload(tsaFile)
            end
        end
        SRS->>GDFU: upload(mediaFile)
        GDFU->>GDFU: uploadFile() on thread pool (max 4 concurrent)
        alt Upload failed (401/Auth)
            GDFU->>GDC: checkAuthentication()
            GDFU->>S: clearAccessToken()
            GDFU->>LD: updateStatus(STATUS_ERROR)
        else Success
            GDFU->>GDFU: file.delete()
        end
    end
```

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
    SRS->>SRS: LiveData → STATUS_STOPPED, updateNotification()
```

### 4. Camera Switch Mid-Recording

When the user switches front↔back during recording, [SafeRecService](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/services/SafeRecService.java#21-227) receives a new `CMD_START`:

1. [stopRecording()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/MainActivity.java#523-530) — tears down audio, video, and muxer (uploads final chunk)
2. [startRecording()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/services/SafeRecService.java#112-181) — creates a **new session/muxer**, re-opens camera with new ID
3. This is a full restart, not a hot swap — there's a brief gap in the recording

### 5. Preview Lifecycle (Independent of Recording)

| State | Action |
|-------|--------|
| **App opens (not recording)** | [startPreview()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#121-140) → opens camera, preview-only session (`TEMPLATE_PREVIEW`) |
| **App opens (recording in progress)** | [attachPreview()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#271-287) → rebuilds capture session with preview + encoder |
| **App goes to background (recording)** | [detachPreview()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#256-270) → rebuilds capture session without preview |
| **App goes to background (not recording)** | [stop()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#240-255) | closes camera entirely |
| **Recording stops (app visible)** | [stopRecording()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/MainActivity.java#523-530) | releases encoder, rebuilds preview-only session |

### 6. Playback Lifecycle

- `PlaybackActivity` uses `android:configChanges` for orientation/screen changes.
- Video layout is recalculated in `onConfigurationChanged()` using cached prepared dimensions to keep aspect ratio correct after rotation.



## Key Classes

### [VideoStreamRecorder](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#32-584) — [VideoStreamRecorder.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java)

**Singleton.** Manages Camera2 device, preview, and H.264 MediaCodec encoder.

| Field | Type | Notes |
|-------|------|-------|
| `muxer` | `volatile MuxerSink` | Set during recording, null otherwise |
| `isRecording` | `volatile boolean` | True when encoder + muxer are active |
| `encoder` | `MediaCodec` | H.264 encoder (Surface input) |
| `encoderSurface` | [Surface](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/MainActivity.java#165-168) | Input surface from encoder |
| `cameraDevice` | `CameraDevice` | Currently open camera |
| `captureSession` | `CameraCaptureSession` | Active session |
| `previewSurface` | [Surface](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/MainActivity.java#165-168) | From TextureView, null when no preview |
| `quality` | [Quality](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#37-71) | LOW (640×360), MEDIUM (854×480), HIGH (1280×720) |

Key methods:
- [initCamera(context, useFront)](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#290-304) — shared setup (sync quality, camera thread, preview size)
- [setupEncoder(context, cameraId)](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java#105-120) — creates & starts MediaCodec with async callback
- [startCaptureSession()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#504-554) — builds targets list (encoder + preview), sets repeating request
- `openCamera(...)` + request generation guard — ignores stale `onOpened` callbacks after stop/restart to avoid resurrecting dead sessions
- [releaseEncoder()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#305-321) — stops & releases encoder safely
- [configureTransform(textureView)](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#141-197) — corrects preview for device rotation

**Rotation formula:** [(sensorOrientation - deviceRotation + 360) % 360](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#240-255) — same for front and back cameras.

### [AudioStreamRecorder](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java#18-179) — [AudioStreamRecorder.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java)

Manages `AudioRecord` (mic input) and AAC MediaCodec encoder on a background thread.

- **Sample rate:** 44100 Hz, mono, PCM 16-bit
- **Bitrate:** 64 kbps AAC-LC
- **Stop sequence:** signals EOS to encoder, drains remaining output, then releases

Key methods:
- [processAudio()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java#121-158) — loop: read PCM → queue to encoder → [drainEncoder()](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java#159-178)
- [drainEncoder(bufferInfo)](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/AudioStreamRecorder.java#159-178) — pulls encoded AAC from encoder, writes to muxer

### [MuxerSink](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/MuxerSink.java#17-263) — [MuxerSink.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/MuxerSink.java)

Thread-safe (`synchronized`) wrapper around `MediaMuxer` with automatic chunking and upload.

| Concept | Implementation |
|---------|---------------|
| **Track readiness** | Waits for both audio+video tracks (or just audio if audio-only) before `muxer.start()` |
| **Pending packets** | Buffers up to 100 packets while tracks aren't ready |
| **Chunk rotation** | On video key-frame when `bytesWritten >= partSize`, or when device rotation changes |
| **PTS normalization** | 3-layer: session base → chunk base → final (always ≥ 0) |
| **File naming** | `{sessionId}_{dataType}_{timestamp}.{seq}` via [MediaFile](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/net/MediaFile.java#7-71) |

**Chunk size** is determined by:
1. Manual setting (`Settings.getChunkSizeMB`) if > 0
2. `Quality.getRecommendedChunkSize()` = [(bitRate / 8) * 10](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/VideoStreamRecorder.java#240-255) (~10s of video)
3. `DEFAULT_AUDIO_CHUNK` = 64 KB (audio-only mode)

### [FreeTSAClient](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/net/FreeTSAClient.java) — [FreeTSAClient.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/net/FreeTSAClient.java)

Utility to obtain cryptographically secure RFC 3161 timestamps.

- **Hash:** SHA-256
- **Provider:** `https://timestamp.sectigo.com` (Sectigo)
- **Library:** BouncyCastle (for TSQ/TSR handling)
- **Local Cache:** Saves `.tsa` files side-by-side with media chunks during background upload.


### [TimestampRenderer](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/TimestampRenderer.java) — [TimestampRenderer.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/TimestampRenderer.java)

OpenGL ES 2.0 pipeline that composites a burned-in date/time overlay onto camera frames before they reach the encoder. Always active during video recording.

- **Pipeline:** Camera → SurfaceTexture (OES texture) → GL render (camera quad + timestamp quad) → encoder input Surface → MediaCodec, and optionally to the preview Surface (TextureView)
- **GL thread:** Dedicated `HandlerThread` ("TimestampGL") for all EGL/GL operations
- **Shaders:** OES external sampler for the camera pass-through, regular `sampler2D` for the text bitmap overlay
- **Text rendering:** Android `Canvas` draws the current date/time into a `Bitmap`, uploaded as a GL texture; only regenerated when the wall-clock second changes
- **Orientation:** The overlay position is defined in "display NDC" and rotated by `+recordingRotation` (CCW in GL = inverse of player's CW rotation) to map to raw-frame NDC. This single rotation matrix handles both position and text orientation for all four orientations (0°/90°/180°/270°).
- **Preview/recording parity:** Uses the same raw camera frames as the encoder (no extra sensor-rotation transform) so the recorded video orientation matches what the player expects.
- **Presentation time:** Forwards the `SurfaceTexture` timestamp to EGL via `eglPresentationTimeANDROID` for correct A/V sync
- **Preview overlay:** When a preview surface is available at record start, it is passed to the renderer. The renderer draws the same frame (camera + timestamp) to both the encoder and the preview, so the on-screen preview matches the recorded video. Late attach/detach is supported via `attachPreviewOutput()` and `detachPreviewOutput()`.
- **Integration:** Created in `VideoStreamRecorder.setupEncoder()` for every video recording; its input surface replaces `encoderSurface` in the camera capture session targets. When the renderer has preview output, the preview surface is not added to the camera targets — the renderer feeds it instead.

### [MediaMerger](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/MediaMerger.java#14-169) — [MediaMerger.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/MediaMerger.java)

Merges multiple MP4 chunks into a single file. Used by [RecordingsActivity](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/RecordingsActivity.java#40-332) when user taps "Merge".

- Reads video/audio formats from the first chunk
- Strips `KEY_ROTATION` from format, applies rotation via `setOrientationHint` only
- Concatenates samples with PTS offset tracking per track
- **Limitation:** Single MP4 = single rotation value. Mixed-camera sessions or merged chunks with different device rotations will have wrong orientation on some segments.

### [SafeRecService](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/services/SafeRecService.java#21-227) — [SafeRecService.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/services/SafeRecService.java)

Foreground service orchestrating the recording. Commands via `Intent` extras:

| Command | Action |
|---------|--------|
| `CMD_START` | If already recording: stop + restart (camera switch). Otherwise: start new recording |
| `CMD_STOP` | Stop recording, reset session |
| `CMD_UPLOAD_PENDING` | Upload any leftover local chunks to Drive |

State is broadcast via [LiveData](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/data/LiveData.java#5-26) singleton: `STATUS_READY` → `STATUS_STARTED` → `STATUS_STOPPED` / `STATUS_ERROR`

### [Settings](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/misc/Settings.java#7-78) — [Settings.java](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/misc/Settings.java)

SharedPreferences wrapper. Keys:

| Setting | Type | Default |
|---------|------|---------|
| `accessToken` | String | null |
| [onlyAudio](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/misc/Settings.java#25-28) | boolean | false |
| `useFrontCamera` | boolean | false |
| `videoQuality` | String | "LOW" |
| `chunkSizeMB` | int | 0 (auto) |
| `autoStartOnLaunch` | boolean | false |
| `tutorialShown` | boolean | false |
| `timestampingEnabled` | boolean | false |

Security note:
- `SafeRecPrefs.xml` is excluded from Android cloud backup/device transfer/full backup rules to avoid backing up the Drive access token.


---

## Data Flow

### File Storage & Naming

```
/data/data/net.ark3us.saferec/files/data_store/
  └── {sessionId}_{video|audio}_{timestamp}.{seq}    ← local chunk files
```

`sessionId` = `System.currentTimeMillis()` at session start.

### Google Drive Structure

```
My Drive/
  └── SafeRec/
      └── {sessionId}/
          └── {video|audio}/
              └── {timestamp}.{seq}.mp4
```

Chunks are uploaded immediately on completion and **deleted locally** on successful upload.

---

## Threading Model

| Thread | Components |
|--------|-----------|
| **Main (UI)** | MainActivity, RecordingsActivity, service commands |
| **CameraBackground** (HandlerThread) | Camera2 callbacks, MediaCodec encoder callbacks (video) |
| **Audio processing** (Thread) | `AudioStreamRecorder.processAudio()` loop |
| **Upload pool** (4 threads) | `FileUploader.uploadExecutor` |
| **Upload scan** (1 thread) | `FileUploader.scanExecutor` for directory scanning |
| **Service executor** (1 thread) | `SafeRecService.executor` for pending uploads |

### Thread Safety Notes

- [MuxerSink](file:///home/ark3us/Filen/Dev/SafeRec/app/src/main/java/net/ark3us/saferec/media/MuxerSink.java#17-263): all public methods are `synchronized`
- `VideoStreamRecorder.muxer` and `.isRecording`: `volatile` for cross-thread visibility
- `VideoStreamRecorder.cameraOpenRequestCount`: `AtomicInteger` generation guard against stale async camera-open callbacks
- `AudioStreamRecorder.isRecording`: `volatile`, checked in processing loop
- `GoogleDriveFileUploader.sessionFolderIdCache`: `ConcurrentHashMap`
- `GoogleDriveFileUploader.cachedBaseFolderId`: `volatile` + double-checked locking

---

## Known Limitations

1. **Camera switch = recording gap.** Switching cameras mid-session does a full stop+restart, creating a new muxer/session segment.
2. **Mixed-rotation merge.** A single MP4 can only have one `setOrientationHint`. When chunks from different cameras (with different sensor orientations) or different device rotations are merged, some segments will display with wrong rotation.
3. **No video mirroring.** Front camera video is recorded un-mirrored (you see yourself as others see you, not as a mirror reflection). This is standard for video recording.
4. **Background Auth Failure.** If the access token expires during background upload, the app attempts a silent token refresh via Google Identity Services. If successful, the upload seamlessly resumes. If the refresh requires user interaction (e.g., token revoked or password changed), it clears the token and triggers `STATUS_ERROR`, prompting the user to manually re-authenticate.
