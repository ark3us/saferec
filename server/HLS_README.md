# HLS playback (play while recording)

Uploaded MP4 chunks are converted to MPEG-TS segments and listed in a live HLS playlist so you can play the session while the app is still recording.

**Requirement:** `ffmpeg` must be on the server `PATH` (used to convert MP4 → TS and to probe duration).

## Endpoints

- **Playlist:** `GET /{client_id}/sessions/{session_id}/hls/live.m3u8`
- **Segment:** `GET /{client_id}/sessions/{session_id}/hls/segment_{n}.ts` (used by the player automatically)

## How to play

1. Start the server and the SafeRec app; start recording so at least one chunk is uploaded.
2. Get the session ID from the server (e.g. `GET /client1/sessions`) or from the app.
3. Open the playlist URL in a player:

**ffplay:**
```bash
ffplay "http://localhost:8000/client1/sessions/<session_id>/hls/live.m3u8"
```

**VLC:** Open URL → `http://localhost:8000/client1/sessions/<session_id>/hls/live.m3u8`

**Browser (hls.js):** Use a page that loads hls.js and passes the playlist URL as the source.

Playlist type is `EVENT` (segments are appended; no sliding window). Refresh the playlist URL to see new segments as they are uploaded.
