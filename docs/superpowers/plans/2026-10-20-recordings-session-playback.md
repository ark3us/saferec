# Recordings, Session Screen and Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the technical chunk list into one card per session that says at a glance whether the evidence is complete, open each session on a screen that plays it as one video and exports the evidence package first, show the latest session in the viewfinder's gallery button, and close the open items Plan 2 left.

**Architecture:**
- **One pure model of a session.** `net/SessionSummary` groups the Drive files of a session and decides its status (complete, uploading, incomplete, not checked), duration and thumbnail from the manifest's gaps. Screens only draw it.
- **The session is the unit.** The list shows sessions, the session screen acts on a session (play, export, share, trash), and pieces, timestamps and repaired originals live under "Technical details".
- **Playback with Media3 ExoPlayer**: the session's pieces as one playlist streamed from Drive with the token in a header, gapless and seekable, each piece with its own orientation. It is the only new library: the R8 checks run again (Task 9).
- **Thumbnails without a library**: Drive's `thumbnailLink`, fetched with the token, cached in memory.

**Tech Stack:** Java 11, Android SDK 26-36, Views/XML, Material Components 1.13, `androidx.media3:media3-exoplayer` and `media3-ui` 1.11.1, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-20-ui-redesign-design.md`: "Other screens" (Recordings list, session screen), "Left to the builder" (playback, thumbnails and durations), "Main screen" (the latest session's thumbnail), and the open items in "Findings from Plan 1" and the Plan 2 final review (Tasks 7 and 8).

## Global Constraints

- minSdk 26, targetSdk 36, compileSdk 36. Views/XML, Material Components 1.13. New dependencies: exactly `androidx.media3:media3-exoplayer:1.11.1` and `androidx.media3:media3-ui:1.11.1` (in `gradle/libs.versions.toml`), nothing else.
- The reliability rules in `AGENTS.md` hold. Nothing here changes recording or uploading.
- Spec (Recordings list): "one card per session, with date and time, duration, a thumbnail, and a status badge: ✅ complete: every chunk the manifest lists is on Drive; ⏳ uploading: chunks still on this phone; ⚠️ incomplete: chunks missing." "Sessions only on the phone: they are not listed. Recording offline, or before Drive is connected, shows an explicit banner instead: 'N pieces waiting on this phone', plus 'Connect Drive' when it is not connected. The latest-session thumbnail shows the same waiting state." "Tapping a card opens the session screen: playback, 'Export evidence package' as the primary action, then share and delete. Chunks, timestamps and repaired originals appear under 'Technical details'. The ✨ merge icon goes: merging happens inside playback and export."
- Never delete for good: "delete" moves to the Drive trash (`CMD_DELETE`), with the folder rules in `AGENTS.md`.
- Visual language as Plan 2: tokens in `res/values/colors.xml` and `dimens.xml` (`rec_red` recording only, `safe_green` safe only, `attention_orange` attention only), Material Symbols Rounded icons as `res/drawable/ms_<name>.xml` from the URL pattern of Plan 2, glass styles. Badge colors: complete `safe_green`, uploading `on_glass`, incomplete `attention_orange`, not checked `on_glass_dim`.
- Strings in English and Italian, every key in both, exact copy given in the tasks; no hard-coded "OK"/"Cancel"; no text that promises what the app does not do.
- `scripts/check-guard-refs.sh` stays green (only `ui/GuardSetup` may name guard types in the off build).
- Code, comments, commits and docs in English. Short clear commit messages, no `Co-Authored-By`. Never stage `gradlew`, never `git stash`. Run Gradle as `bash ./gradlew`. Never install on a phone in an implementer task: device checks are the controller's (Task 11).

## Review Focus

1. **A session with pieces still uploading or lost:** expected "Uploading" or "Incomplete" with how many, never "Complete"; a session without a manifest is "Not checked", never "Complete". Owner: Task 1 (`SessionSummaryTest`).
2. **No token, offline, or a list that fails to load:** expected an explanation and a way forward (Connect Drive, pull to retry), never a screen that closes by itself (today `RecordingsActivity` finishes without a token). Owner: Task 3, plus Task 11.
3. **Playback of a long session, a piece missing, the network dropping mid-play, a token expiring:** expected the player skips a missing piece, shows a clear error with retry, and never crashes the app. Owner: Task 4 (`SessionPlaylistTest` for the playlist from the summary), plus Task 11.
4. **Trashing a session:** expected a confirmation, every file of the session (pieces, timestamps, cut originals, manifest and its timestamp) and then its empty folders go to the trash, and the list updates. Owner: Task 1 (`SessionSummaryTest.allFilesIncludesTechnical`), Task 5.
5. **R8 with Media3:** expected the shrunk release plays a session and exports a package with no crash. Owner: Task 9.

---

### Task 1: The session model

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/net/SessionSummary.java`
- Modify: `app/src/main/java/net/ark3us/saferec/net/DriveRecordings.java` (fields of the media query; a grouping method)
- Test: `app/src/test/java/net/ark3us/saferec/net/SessionSummaryTest.java`

**Interfaces:**
- Produces:
  - `enum SessionSummary.Status { COMPLETE, UPLOADING, INCOMPLETE, NOT_CHECKED }`.
  - `final class SessionSummary.Piece { int seq; long startMillis; @Nullable Long durationMillis; String fileId; @Nullable String thumbnailLink; @Nullable String timestampFileId; @Nullable String cutFileId; @Nullable String cutTimestampFileId; long size; }`.
  - `SessionSummary` fields: `String sessionId`, `String dataType`, `long startMillis` (the session id), `List<Piece> pieces` (by `seq`), `Status status`, `List<Integer> onPhone`, `List<Integer> lost`, `@Nullable SessionManifest.Gaps gaps`, `List<DriveRecordings.RecordingItem> files` (every file of the session: pieces, timestamps, cut originals and their timestamps, manifest and its timestamp — what the export downloads), `List<String> allFileIds` (their ids), `List<String> folderIds` (the type folder and the session folder: trashed only when empty, as today).
  - `long durationMillis()`: last piece start − first piece start + (last piece duration, or `ChunkPolicy.DEFAULT_DURATION_MS` when unknown); 0 with no piece.
  - `@Nullable String thumbnailLink()`: the first piece's that has one.
  - `static List<SessionSummary> SessionSummary.group(List<DriveRecordings.RecordingItem> items)`: one summary per `sessionId|dataType`, newest session first; a session with a manifest but no piece on Drive is included (its status comes from the gaps).
  - Status rule: no manifest (or unreadable: `gaps == null`) → `NOT_CHECKED`; `gaps.lost` not empty → `INCOMPLETE`; `gaps.onPhone` not empty → `UPLOADING`; otherwise `COMPLETE`.
  - `DriveRecordings.listSessions(Callback<List<SessionSummary>>)`: `listRecordings` then `SessionSummary.group`. The media query also asks for `videoMediaMetadata(durationMillis)`.
  - `DriveRecordings.session(String sessionId, String dataType, Callback<SessionSummary>)`: only that session's folders and files (the session folder by name in every base folder, its type folder, its files, the gaps from its manifest); `null` when Drive has none. Tasks 2 and 4 use it, so neither lists every recording.

- [ ] **Step 1: Write the failing tests** (build `RecordingItem`s with `MediaFile.fromDriveData` and a Drive `File` whose id, name, size, thumbnail and video metadata are set):

```java
@Test public void completeWhenTheManifestHasNoGap() { assertEquals(COMPLETE, summary(withManifest(noGaps()), piece(0), piece(1)).status); }
@Test public void uploadingWhenPiecesWaitOnThisPhone() { assertEquals(UPLOADING, summary(withManifest(gaps(onPhone(2), lost())), piece(0), piece(1)).status); }
@Test public void incompleteWhenPiecesAreLost() { assertEquals(INCOMPLETE, summary(withManifest(gaps(onPhone(2), lost(1))), piece(0)).status); }
@Test public void notCheckedWithoutAManifest() { assertEquals(NOT_CHECKED, summary(null, piece(0)).status); }
@Test public void durationFromStartsAndTheLastPiece() { assertEquals(25_000, summary(withManifest(noGaps()), piece(0, 0, 10_000L), piece(1, 10_000, 10_000L), piece(2, 20_000, 5_000L)).durationMillis()); }
@Test public void unknownLastDurationCountsTenSeconds() { assertEquals(20_000, summary(null, piece(0, 0, null), piece(1, 10_000, null)).durationMillis()); }
@Test public void allFilesIncludesTechnical() { /* a piece with .tsa, .cut, .cut.tsa, plus manifest and .manifest.tsa → all six ids in allFileIds */ }
@Test public void aSessionWithOnlyAManifestIsListed() { /* manifest with gaps onPhone(0), no piece → one summary, UPLOADING, no pieces */ }
@Test public void newestSessionFirst() { /* two sessions → the higher session id first */ }
@Test public void videoAndAudioOfOneSessionAreTwoSummaries() { /* same sessionId, dataType video and audio → two */ }
```

- [ ] **Step 2:** `bash ./gradlew :app:testDebugUnitTest --tests '*SessionSummaryTest'`. Expected: FAIL (class missing).
- [ ] **Step 3:** Implement `SessionSummary` (pure; no Android types besides the Drive model and `MediaFile`) and `DriveRecordings.listSessions`. Run the tests. Expected: PASS.
- [ ] **Step 4:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`. Commit: `git commit -m "Add a session model with status, duration and files"`

### Task 2: Thumbnails and the gallery button

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/ui/ThumbnailLoader.java`
- Modify: `DriveRecordings.java` (`latestSession`), `MainActivity.java` and `activity_main.xml` (`btn_gallery`), strings (EN/IT)

**Interfaces:**
- Produces:
  - `ThumbnailLoader(Context)` (one per activity), `void load(ImageView target, @Nullable String thumbnailLink, String accessToken, @DrawableRes int placeholder)`: shows `placeholder` at once; fetches the link on a 2-thread executor with `Authorization: Bearer <token>` (`HttpURLConnection`, 10 s timeouts), decodes a `Bitmap` sampled to the view's size, caches it in an `LruCache` keyed by link (1/16 of the heap), and sets it only if the view still waits for that link (a tag). Any failure keeps the placeholder and logs once. `void shutdown()`.
  - `DriveRecordings.latestSession(Callback<SessionSummary>)`: the newest session folder (`orderBy("name desc")`, page size 1, session folder names are epoch millis of equal length), then `session(name, type)` for it — not the whole list. `null` when there is none.
- Gallery button: on `onResume` with a token and online, `latestSession` → `ThumbnailLoader.load(btn_gallery image, summary.thumbnailLink(), token, ms_photo_library)`; offline, without a token or without a session: `ms_photo_library`. A small badge on the button (`gallery_badge`: `ms_cloud_upload` 16dp + the count) shows when pieces wait on this phone (`ServiceState.getPendingPieces()` / the outbox count already used by the chip), hidden at 0. Content description `gallery_latest` with the session's date when known, else `open_recordings`.

| key | English | Italian |
|---|---|---|
| `gallery_latest` | Recordings, latest from %s | Registrazioni, l\'ultima del %s |

- [ ] **Step 1:** Implement `ThumbnailLoader` and `latestSession`; wire the gallery button.
- [ ] **Step 2:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`, `bash scripts/check-guard-refs.sh`. Commit: `git commit -m "Show the latest session in the gallery button"`

### Task 3: The recordings list

**Files:**
- Rewrite: `app/src/main/java/net/ark3us/saferec/RecordingsActivity.java`, `app/src/main/res/layout/activity_recordings.xml`
- Create: `app/src/main/java/net/ark3us/saferec/ui/SessionsAdapter.java`, `app/src/main/res/layout/item_session_card.xml`
- Modify: strings (EN/IT)

**Interfaces:**
- Consumes: Task 1 `listSessions`, `SessionSummary`; Task 2 `ThumbnailLoader`; Plan 2 `PermissionsManager.connectDrive`, `DriveConnection.accept`, `SafeCount.pieces`, `ServiceState`.
- Produces: a card tap calls `SessionActivity.start(context, summary.sessionId, summary.dataType)` (Task 4 defines it; until then a stub that logs).

Screen: a `MaterialToolbar` (`open_recordings`, back arrow `ms_arrow_back`); under it a glass banner `waiting_banner` (`ms_cloud_upload` + plural `status_waiting` + action "Upload now" `check_upload_now` → `CMD_UPLOAD_PENDING`, shown when pieces wait on this phone; with no token the action is `status_connect_drive` → `connectDrive(true)` then `DriveConnection.accept` and reload); a `SwipeRefreshLayout` over a `RecyclerView` of cards; an empty state.

Card (`item_session_card.xml`, `surface_card`, radius `glass_radius`): thumbnail 96×72dp (placeholder `ms_videocam` for video, `ms_graphic_eq` for audio), title = date and time (`DateFormat.getMediumDateFormat` + `getTimeFormat`, the phone's locale), subtitle = duration (`m:ss` or `h:mm:ss`) · `mode_video`/`mode_audio`, a status chip (icon + label, colors in Global Constraints). The whole card is one click target with a content description that reads title, duration, type and status.

States: no token → empty state `recordings_not_connected` with the connect action (never `finish()`); loading → progress; failure → `recordings_load_failed` (pull to retry); none → `recordings_empty`.

The old multi-select, merge and per-chunk rows go (Task 6 removes their files).

| key | English | Italian |
|---|---|---|
| `session_status_complete` | Complete | Completa |
| `session_status_uploading` | Uploading | In caricamento |
| `session_status_incomplete` | Incomplete | Incompleta |
| `session_status_not_checked` | Not checked | Non verificata |
| `recordings_empty` | No recordings on Drive yet. | Ancora nessuna registrazione su Drive. |
| `recordings_not_connected` | Connect Google Drive to see your recordings. Recordings made meanwhile wait on this phone. | Collega Google Drive per vedere le tue registrazioni. Quelle fatte nel frattempo aspettano sul telefono. |
| `recordings_load_failed` | Could not load the recordings. Pull down to try again. | Impossibile caricare le registrazioni. Trascina in basso per riprovare. |
| `session_card_description` | %1$s, %2$s, %3$s, %4$s | %1$s, %2$s, %3$s, %4$s |

- [ ] **Step 1:** Write the layouts, `SessionsAdapter` (DiffUtil on `sessionId|dataType`) and the activity.
- [ ] **Step 2:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`. Commit: `git commit -m "List recordings as session cards with their status"`

### Task 4: The session screen and its player

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (Media3 1.11.1: `media3-exoplayer`, `media3-ui`)
- Create: `app/src/main/java/net/ark3us/saferec/SessionActivity.java`, `app/src/main/res/layout/activity_session.xml`, `app/src/main/java/net/ark3us/saferec/ui/SessionPlaylist.java`
- Modify: `AndroidManifest.xml` (activity, not exported, `Theme.SafeRec.Surface`, `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`), strings (EN/IT)
- Test: `app/src/test/java/net/ark3us/saferec/ui/SessionPlaylistTest.java`

**Interfaces:**
- Consumes: Task 1 `SessionSummary` and `DriveRecordings.session(...)` (the extras are the session id and type; the activity loads that one session, so no large Parcelable and no full listing).
- Produces: `static void SessionActivity.start(Context, String sessionId, String dataType)`; `static List<String> SessionPlaylist.fileIds(SessionSummary)` (the pieces in `seq` order; a piece without a file id is skipped); `static String SessionPlaylist.url(String fileId)` (`https://www.googleapis.com/drive/v3/files/<id>?alt=media`).

Screen (top to bottom, a `NestedScrollView` under a toolbar with the date and time): the player (`androidx.media3.ui.PlayerView`, 16:9, black, controls on, fullscreen button: hides the rest and goes immersive; back leaves fullscreen first); the status line (the badge from Task 3 plus a sentence: `session_explain_complete`, plural `session_explain_uploading` with `onPhone.size()`, plural `session_explain_incomplete` with `lost.size()`, `session_explain_not_checked`); the primary button `export_evidence`; the secondary buttons `session_share_video` and `session_trash` (Task 5 wires them; disabled until then); a collapsible `session_technical` section (Task 5 fills it).

Player: one `ExoPlayer` per activity, `DefaultHttpDataSource.Factory().setDefaultRequestProperties(Map.of("Authorization", "Bearer " + token))` (build the map with `HashMap`: `Map.of` needs API 30), `ProgressiveMediaSource` per piece, `setMediaSources(list)`, `prepare()`, no autoplay. Errors (`onPlayerError`): a slim message `player_error` with a Retry button (`retry_short`) that re-prepares at the current item and position; a 401 asks `PermissionsManager.connectDrive(false)` for a fresh token once, then shows the message. Release the player in `onStop` (keep the position and item for `onStart`).

| key | English | Italian |
|---|---|---|
| `session_explain_complete` | Every piece is on Drive. | Tutti i pezzi sono su Drive. |
| `session_explain_uploading` (plurals) | %d piece is still on this phone and goes to Drive with the network. / %d pieces are still on this phone and go to Drive with the network. | %d pezzo è ancora sul telefono e andrà su Drive con la rete. / %d pezzi sono ancora sul telefono e andranno su Drive con la rete. |
| `session_explain_incomplete` (plurals) | %d piece is missing on Drive. / %d pieces are missing on Drive. | Manca %d pezzo su Drive. / Mancano %d pezzi su Drive. |
| `session_explain_not_checked` | Recorded by an older version of SafeRec: it has no list of its pieces to check. | Registrata da una versione precedente di SafeRec: non ha l\'elenco dei pezzi da verificare. |
| `session_share_video` | Share the video | Condividi il video |
| `session_trash` | Move to trash | Sposta nel cestino |
| `session_technical` | Technical details | Dettagli tecnici |
| `player_error` | Cannot play this recording right now. Check the network and try again. | Impossibile riprodurre la registrazione ora. Controlla la rete e riprova. |
| `retry_short` | Retry | Riprova |

- [ ] **Step 1: Write the failing tests**

```java
@Test public void piecesInOrder() { assertEquals(List.of("a0", "a1", "a2"), SessionPlaylist.fileIds(summaryWithPieces(2, 0, 1))); }
@Test public void aSessionWithoutPiecesHasNoPlaylist() { assertTrue(SessionPlaylist.fileIds(summaryWithPieces()).isEmpty()); }
@Test public void driveMediaUrl() { assertEquals("https://www.googleapis.com/drive/v3/files/x1?alt=media", SessionPlaylist.url("x1")); }
```

- [ ] **Step 2:** Run `--tests '*SessionPlaylistTest'`. Expected: FAIL. Implement. Expected: PASS.
- [ ] **Step 3:** Add Media3, the activity and the layout; replace Task 3's stub. A session with no piece on Drive shows the status and the explanation, no player.
- [ ] **Step 4:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`, `bash scripts/check-guard-refs.sh`. Commit: `git commit -m "Open a session on its own screen and play it as one video"`

### Task 5: Session actions and technical details

**Files:**
- Create: `app/src/main/java/net/ark3us/saferec/EvidenceExport.java` (the export moved out of `RecordingsActivity`, unchanged in behavior: download, merge, `SessionCheck`, report, zip, the verified/not-verified dialog with Upload now / Share anyway)
- Modify: `SessionActivity.java`, `activity_session.xml`, strings (EN/IT)

**Interfaces:**
- Produces: `EvidenceExport(AppCompatActivity, DriveRecordings, ExecutorService)` with `void export(SessionSummary summary)` (it downloads `summary.files` and uses `summary.gaps`, as today's `exportSession` does with the adapter's files and gaps).
- Actions:
  - **Export evidence package**: `EvidenceExport.export(...)`, a progress indicator on the button while it runs.
  - **Share the video**: download the pieces, `MediaMerger.merge` into `cache/merged_sharing/merged_<session>.mp4`, share `video/mp4` (today's `mergeSession`, moved).
  - **Move to trash**: a dialog `session_trash_confirm` (positive `session_trash`, negative `android.R.string.cancel`); then `CMD_DELETE` with `allFileIds` and `folderIds`, a toast with plural `deleting_recordings_bg`, `finish()`; the list reloads on resume.
  - **Technical details** (collapsed by default): one row per piece — `piece_row` (number and start time), size, `ms_shield_fill` tinted `safe_green` when it has a time stamp, `piece_repaired` when it has a cut original; a tap plays from that piece (`seekTo(index, 0)`); a long press or an overflow offers `session_share_timestamp` for a piece with a time stamp (today's `shareTsaFile`, moved). The manifest row: `session_manifest_row` with its time stamp state.

| key | English | Italian |
|---|---|---|
| `session_trash_confirm` | Move this recording to the Google Drive trash? Drive keeps it for 30 days. | Spostare questa registrazione nel cestino di Google Drive? Drive la conserva per 30 giorni. |
| `piece_row` | Piece %1$d · %2$s | Pezzo %1$d · %2$s |
| `piece_repaired` | Repaired after a cut, original kept | Riparato dopo un\'interruzione, originale conservato |
| `session_share_timestamp` | Share time stamp | Condividi la marca temporale |
| `session_manifest_row` | List of pieces (manifest) | Elenco dei pezzi (manifest) |

- [ ] **Step 1:** Move the export into `EvidenceExport` (no behavior change: the existing `EvidencePackageTest`, `SessionCheckTest` stay green).
- [ ] **Step 2:** Wire the three actions and the technical details.
- [ ] **Step 3:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`. Commit: `git commit -m "Export, share and trash a session from its screen"`

### Task 6: Remove what the session screen replaced

**Files:**
- Delete: `RecordingsAdapter.java`, `PlaybackActivity.java` (and its manifest entry), `ui/TutorialOverlayView.java`, `layout/item_recording.xml`, `layout/item_session_header.xml`, `layout/activity_playback.xml`, `menu/recordings_menu.xml`, `drawable/ic_merge.xml` if unused, `Theme.SafeRec.Playback` if unused
- Modify: `misc/Settings.java` (drop `isRecordingsTutorialShown`/`setRecordingsTutorialShown`), strings (EN/IT)

- [ ] **Step 1:** Delete the files; remove every key no code uses any more, checking each with `grep` in `app/src` and `guard/src` before removing (at least: `tutorial_recordings_*`, `show_tutorial`, `merge_chunks`, `chunk_name`, `chunk_repaired`, `manifest_on_phone`, `manifest_lost`, `tsa_label`, `selection_count`, `all_recordings`, `my_recordings`, `play_recording`, `refresh`, `share_recording`, `share_merged_recording`, `no_recordings_found`, `error_loading_recordings`, `error_missing_file`, `failed_play_video`, `session_expired`, `downloading_chunks`, `video`, `audio`), and the old colors Plan 2 kept for these screens (`primary`, `surface_elevated`, `record_red`, `control_icon`) if nothing uses them.
- [ ] **Step 2:** `bash ./gradlew :app:lintDebug`: 0 errors, no `UnusedResources`, `MissingTranslation` or `ExtraTranslation`. `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug`. Commit: `git commit -m "Remove the chunk list, the old player and the recordings tutorial"`

### Task 7: Accessibility pass

**Files:**
- Modify: `ui/RecordButton.java`, `activity_main.xml` / `MainActivity.java` (`hold_hint`), `ui/FirstRun.java` (step dots), `ui/SettingsRows.java`, `ui/SessionsAdapter.java`, strings (EN/IT)

- [ ] **Step 1:** `RecordButton.getAccessibilityClassName()` returns `android.widget.Button`.
- [ ] **Step 2:** Replace `announceForAccessibility` (deprecated in API 36) with a polite live region on `hold_hint` and on the permissions banner text (`ViewCompat.setAccessibilityLiveRegion(..., ACCESSIBILITY_LIVE_REGION_POLITE)`).
- [ ] **Step 3:** First-run dots: content description `first_run_step` (EN "Step %1$d of %2$d" / IT "Passo %1$d di %2$d").
- [ ] **Step 4:** Settings toggle rows are one TalkBack stop: the row carries the switch's checked state (`ViewCompat.setStateDescription` or an `AccessibilityDelegate` with `isCheckable`/`isChecked`), the switch itself is not focusable; a double tap acts as the row tap.
- [ ] **Step 5:** `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Commit: `git commit -m "Make the record button, settings rows and first run read well with TalkBack"`

### Task 8: Open items from Plan 2

**Files:**
- Modify: `ui/FirstRun.java` / `card_first_run.xml`, `ui/DriveConnector.java`, `MainActivity.java`, `ui/PermissionsBanner.java` / `PermissionsBannerModel.java`, `SettingsActivity.java`, `AndroidManifest.xml`, `net/DriveConnection.java`, strings (EN/IT), `AGENTS.md`

Each item is from the Plan 2 final review (its ledger is gone; the items are restated here):
- [ ] **Step 1 (M3):** the first-run card never grows over the top bar: its text scrolls inside a max height (`maxHeight` 45% of the screen, or a `NestedScrollView` with a capped height).
- [ ] **Step 2 (M7):** the silent Drive check on resume runs at most once a minute, or when the network comes back; `CMD_UPLOAD_PENDING` is sent only when pieces wait on this phone.
- [ ] **Step 3 (M8):** a start waiting for the permission dialog survives a recreation: a saved-state flag "start after grant", consumed when the permissions are granted.
- [ ] **Step 4:** in Video mode with the microphone refused and the camera granted, the banner says `permissions_banner_audio` (only the microphone is needed); test it in `PermissionsBannerModel`'s test if one exists, else add `PermissionsBannerModelTest` with that case.
- [ ] **Step 5:** `SettingsActivity` declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"` like `MainActivity`, so a rotation keeps dialogs and a consent in progress.
- [ ] **Step 6:** the tile row treats any result code other than added, already added or not added as an error (shows `tile_steps`).
- [ ] **Step 7:** an interactive Drive connect clears `Settings.driveAccount` before storing the new token, so "Connected as" never names a previous account.
- [ ] **Step 8:** `timestamping_summary`: EN "Each piece recorded while there is a network gets a time stamp from Sectigo, an independent authority: proof it existed, unchanged, at that time. Only the file's fingerprint is sent." / IT "Ogni pezzo registrato mentre c'è rete riceve una marca temporale da Sectigo, un'autorità indipendente: la prova che esisteva, identico, in quel momento. Viene inviata solo l'impronta del file."
- [ ] **Step 9:** `AGENTS.md` package tree: add `ui/PermissionsBannerModel` and say `PermissionsBanner` is also the gate of every start from the app.
- [ ] **Step 10:** `bash ./gradlew testDebugUnitTest :guard:testDebugUnitTest assembleDebug`, `bash ./gradlew :app:assembleDebug -Psaferec.powerGesture=false`, `bash scripts/check-guard-refs.sh`. One commit per step or small group.

### Task 9: R8 with Media3

**Files:**
- Modify: `app/proguard-rules.pro` only if the shrunk build needs it (Media3 ships its own consumer rules), `AGENTS.md` (Release Build)

- [ ] **Step 1:** `bash ./gradlew :app:assembleR8test` and `bash ./gradlew :app:minifyR8testWithR8 -Psaferec.powerGesture=false` succeed with no R8 warning about Media3.
- [ ] **Step 2:** Record in `AGENTS.md` (Release Build) that Media3 was added and what the controller verifies with `r8test` (Task 11). Commit: `git commit -m "Check the shrunk build with Media3"`

### Task 10: Texts

- [ ] **Step 1:** Every new key in both languages; `bash ./gradlew :app:lintDebug` with no `MissingTranslation`, `ExtraTranslation` or `UnusedResources`.
- [ ] **Step 2:** Read every string of the list, the session screen and the player against the anti-goal "Text that promises something the app does not do"; fix clear errors, list doubtful ones in the report.
- [ ] **Step 3:** Commit: `git commit -m "Check the recordings and session texts"`

### Task 11: Device checks and docs (controller)

- [ ] **Step 1:** On the S10+ (`ANDROID_SERIAL=<test phone serial>`, model `SM-G975F`): the list with a complete, an uploading (a session recorded offline) and, if one exists, an incomplete session; cards readable; thumbnails appear (Drive generates them some minutes after the upload: a placeholder meanwhile).
- [ ] **Step 2:** No token (as in Plan 2: prefs edited, restored) → the list explains and connects; offline → the waiting banner.
- [ ] **Step 3:** A session plays as one video across pieces (seek across a piece boundary), a rotation mid-session plays upright, fullscreen and back, airplane mode mid-play → the error with Retry, Retry after the network returns resumes.
- [ ] **Step 4:** Export from the session screen: verified package shared; trash a test session: every file and its folders in the Drive trash, the list updates.
- [ ] **Step 5:** `r8test` installed over the debug build: list, play, export, no crash; then the debug build back.
- [ ] **Step 6:** TalkBack (if installed) or `uiautomator` content descriptions on the record button, a settings toggle row and a session card.
- [ ] **Step 7:** Update `AGENTS.md` (UI layer diagram, package tree, the session screen, playback with Media3, Known Limitation 2 "Mixed-rotation merge" now applies to the merged video and export only, not to playback) and `README.md`; screenshots in `tmp/ui-after/` next to `tmp/ui-tour/`. Ask the owner before trashing test sessions and before deleting temporary files.
