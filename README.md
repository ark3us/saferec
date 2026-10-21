![SafeRec Banner](saferec.png)

# SafeRec

<a href="https://play.google.com/store/apps/details?id=net.ark3us.saferec">
  <img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="60" />
</a>

Record video or audio in an emergency. Every 10 seconds the recording is saved to your Google Drive, while you record. If the phone is taken or broken, what was recorded is already safe.

Open source, Android 8.0+, English and Italian.

## Why

A normal camera app saves the video when you stop. Lose the phone before that and you lose everything. SafeRec uploads as it goes.

## Features

**Start fast, even locked**
- Press the power key three times, with the phone locked and the screen off (optional).
- Quick settings tile, also on the lock screen.
- "Record now" shortcut on the app icon.
- Double press of the power key, if your phone lets another app take it (optional).
- Two short vibrations confirm the recording. Three short ones mean nothing is recording.

**Stop on purpose**
- Hold the record button for half a second. A tap does not stop.
- Stopping from the notification needs the unlocked phone.

**Safe on Drive**
- Pieces of 10 seconds, uploaded while recording.
- No network? Pieces wait on the phone and upload by themselves later, even after a reboot.
- A piece cut by a crash or a power cut is repaired. The original is kept too.
- SafeRec can only see the files it created on your Drive.

**Discreet**
- No sound. The notification is hidden on the lock screen.
- Audio-only mode.
- Android still shows its camera and microphone indicator: no app can hide it.

**Your recordings**
- One card per recording, with its status: Complete, Uploading, Incomplete or Not checked.
- Plays the whole recording as one video, straight from Drive.
- Share the video, or move it to the Drive trash (kept 30 days).
- Share all your recordings with a trusted person (read only, from Settings).

**Evidence**
- Date and time burned into every video frame.
- Each recording keeps a list of its pieces with their SHA-256 fingerprints, so a missing or changed piece shows.
- Optional certified time: Sectigo, an independent authority, timestamps each piece. Only the fingerprint is sent.
- "Export evidence package": one zip with the video, every original file, a report and a verifier. The phone checks it before sharing.

**Settings**
- Video quality: 360p, 480p or 720p.
- Front or back camera, for the app and for quick starts.

## Verify a recording

Anyone (a lawyer, an expert, a court) can check a recording without trusting the app or Google.

The easy way: open the recording, tap **Export evidence package**, share the zip. Inside, run:

```
uv run verify_session.py evidence
```

Or from Drive:
1. Download the recording's folder (named after its start time, e.g. `1792429746374`) and unzip it.
2. Install [uv](https://docs.astral.sh/uv/) and make sure `openssl` is available.
3. From this repository: `uv run scripts/verify_session.py <folder>`

It checks every piece against the list, and the timestamps up to a trusted root. It ends with **COMPLETE** or **NOT VERIFIED** and the reason.

## Privacy

No servers of ours. Recordings go from your phone to your Google Drive. With certified time on, Sectigo gets only each piece's fingerprint. Details: [privacy policy](privacy_policy.html).

## Build

You need Android Studio, JDK 11 and a Google Cloud project with the Drive API.

1. Clone: `git clone https://github.com/ark3us/saferec.git`
2. In the [Google Cloud Console](https://console.cloud.google.com/):
   - enable the **Google Drive API**;
   - set up the OAuth consent screen (External) with the scope `.../auth/drive.file`;
   - create an **Android** OAuth client: package `net.ark3us.saferec`, your SHA-1.
3. Your debug SHA-1:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
4. Build: `./gradlew assembleDebug`

Without the three presses (no special foreground service): add `-Psaferec.powerGesture=false`.

How it works inside: [AGENTS.md](AGENTS.md).

## Credits

Developed with the assistance of **Antigravity**.

Video playback: [Media3](https://developer.android.com/media/media3) (ExoPlayer) by Google, Apache License 2.0.

Icons: [Material Symbols](https://fonts.google.com/icons) by Google, Apache License 2.0.

## License

GNU General Public License v3.0, see [LICENSE](LICENSE).
