# SafeRec — Ideas

Ideas not yet planned. Each one says what it is, why it matters for evidence safety, and a starting point.

## Safety and alerts

### Trusted contacts auto-share
At session start, share the session folder with 1–3 preset emails (Drive permission `type=user`, `role=reader`). Google emails them a link, so the share doubles as a free alert, and they keep access even if the owner's account is wiped. Safer than the current "anyone with the link" share of the whole root folder.

### Dead man's switch with Apps Script
Each uploaded chunk updates a `heartbeat` file on Drive. A time-driven Google Apps Script in the user's own account checks it every minute. If the heartbeat is older than ~2 minutes and the session has no end marker, `MailApp` notifies the trusted contacts. No server to run.

### Exemption from permission auto-reset
Android revokes permissions of apps unused for months, which is exactly how an emergency app is used. Ask the user to exempt SafeRec with `IntentCompat.createManageUnusedAppRestrictionsIntent()` so the camera and microphone still work on the day it matters.

### Emergency drill
A settings button that records 15 s, checks the chunk exists, uploads it and shows ✅/❌ for each step (permissions, network, Drive, TSA). Better to find a broken setup during a drill than during an emergency.

## Evidence integrity

### Hash chain for timestamps
Chain chunk hashes (`H_n = sha256(H_{n-1} ‖ sha256(chunk_n))`) and timestamp only the chain head, at most every 15 s. One timestamp then proves existence, order and completeness of all previous chunks, and respects Sectigo's request of ≥15 s between automated requests.

### OpenTimestamps as a second anchor
Anchor the chain head with OpenTimestamps (free, no rate limit, anchored to Bitcoin) next to the Sectigo RFC 3161 timestamp. Two independent witnesses are harder to dispute than one.

### Verification guide
A short guide plus script (`openssl ts -verify -data chunk.mp4 -in chunk.tsa -CAfile …`) so a lawyer or expert can check a recording in two minutes.

## Quality and testing

### In-app chunk health check
After closing each chunk, check it with `MediaExtractor`: both tracks have samples, duration ~10 s. On failure, warn in the notification. The "no audio after the first chunk" bug would have been caught on users' phones instead of after an emergency.

### Device tests as a release gate
No Play Store bundle unless `scripts/device-tests.sh` passes on at least one real phone.

### Signing check before Drive tests
When Drive authorization fails with `UNREGISTERED_ON_API_CONSOLE`, have `scripts/device-tests.sh` print the APK's signing SHA-1 and the exact Cloud Console steps to register it. A new dev machine then costs two minutes instead of an investigation.

### Device health guard in device tests
Record `MemAvailable` and `SUnreclaim` (kernel unreclaimable memory) before and after each run, and refuse to run on a thrashing device. A custom-ROM kernel leak (5.3 GB of unreclaimable slab) once caused an ANR that looked like an app bug.
