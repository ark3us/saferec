![SafeRec Banner](saferec.png)

# SafeRec

<a href="https://play.google.com/store/apps/details?id=net.ark3us.saferec">
  <img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="60" />
</a>


**SafeRec** is an open-source Android application designed for emergency and high-risk situations. It ensures your critical evidence is safe by recording (video and audio) and instantly uploading the footage in chunks directly to your Google Drive. Even if your phone is destroyed, broken, or confiscated during an event, your recordings are already securely preserved in your cloud storage. 

Furthermore, SafeRec generates verifiable timestamp certificates (TSA) to prove the authenticity and integrity of your recordings, making them robust for evidentiary purposes.

## Motivations

In emergency situations, every second is vital. A traditional camera app saves the video locally only *after* the recording is stopped. If the device is taken, broken, or turned off during the event, the evidence is often lost forever or corrupted. 

SafeRec solves this fundamental flaw by continuously uploading video and audio chunks to your Google Drive in real-time. This guarantees that whatever was recorded up until the moment of device failure or confiscation is securely preserved. The cryptographic TSA stamping adds an irrefutable layer of provability for legal or evidentiary use, ensuring the file existed at a specific point in time and has not been tampered with.

## Key Features

- **Instant Cloud Backup**: Automatically uploads video/audio chunks directly to Google Drive as they are being recorded.
- **Background Recording**: Uses an Android Foreground Service, allowing you to seamlessly record even when the screen is locked or while you are using other apps.
- **Audio-Only Mode**: Easily toggle between Video+Audio and Audio-Only modes for discretion, battery-saving, and reduced bandwidth.
- **Quick Settings Tile**: Start a recording instantly from the Android Quick Settings drop-down menu, also on the lock screen, without opening the app. The tile only starts recordings: stopping needs the unlocked phone.
- **Quick Start**: Recording starts without unlocking the phone, from the Quick Settings tile, the "Record now" shortcut, or by pressing the power key three times (optional, can be left out of a build). It vibrates to confirm, and the notification shows the elapsed time and how many pieces are already safe on Drive. Stopping needs the unlocked phone.
- **Cryptographic Timestamping (TSA)**: Hashes the media files (SHA-256) and requests timestamp certificates from a recognizable Time Stamping Authority (Sectigo), proving the integrity and creation time of your recordings.
- **Survives Crashes, Kills and Reboots**: Chunks recorded offline wait on the phone and upload on their own once there is a network, even after the app was killed or the phone restarted, without opening the app again.
- **Cut Chunk Repair**: A chunk cut by a crash or a sudden power cut is rebuilt into a playable video from a log kept while recording. Its untouched original goes to Drive too, and both are timestamped.
- **Session Manifest**: Each session keeps, next to its chunks, the list of everything it recorded (sizes and SHA-256). The recordings screen flags a chunk missing on Drive, and anyone can check that nothing was removed or altered (see [Verifying a session](#verifying-a-session)).
- **Evidence Package**: One tap on a session in the recordings screen builds a single zip to hand over: the whole recording merged into one video to watch, every original file as it is on Drive, a report (chunks, SHA-256, timestamp times, gaps), a plain-language README in the app's language and the verifier.
- **Chunk Management**: Automatically chunks video files (with adjustable chunk sizes) to ensure frequent and manageable uploads, minimizing the risk of losing long segments.
- **Video Quality Controls**: Adjust video quality (High 720p, Medium 480p, Low 360p) to balance file size against upload speeds.

## Technical Details

- **Burned-in Timestamp Overlay**: Every video frame passes through a small OpenGL ES 2.0 pipeline that draws the current date/time directly into the image, with correct orientation in both portrait and landscape.
- **Cloud Integration**: Powered by the Google Drive API v3 and Google Play Services Auth for secure, token-based uploads.
- **Minimum SDK**: Android 8.0 (API Level 26).
- **Target SDK**: Android 16 (API Level 36).

## Verifying a session

Anyone who receives a SafeRec session (a lawyer, an expert, a court) can check it without trusting the app or Google Drive.

The simplest way is the **evidence package**: in the recordings screen, tap the package icon of the session and share the zip. The phone runs the verifier's checks first: if a chunk is missing or altered, it says so before sharing, while a chunk still on the phone can be uploaded. The zip holds the verifier and a README with the three steps below; run `uv run verify_session.py evidence` inside it. Otherwise, from the Drive folder:


1. In Google Drive, right-click the session folder (it is named after the session's start, e.g. `1792429746374`) and choose **Download**. Unzip it.
2. Install [uv](https://docs.astral.sh/uv/) and make sure the `openssl` command is available (it is on Linux and macOS).
3. Run, from this repository:

   ```
   uv run scripts/verify_session.py <the unzipped folder>
   ```

For each session it checks:
- the manifest's RFC 3161 timestamp: its signature, its certificate chain up to a root of Mozilla's trusted list (Sectigo's timestamping chain goes up to USERTrust RSA), and that it certifies this very manifest;
- that every chunk listed in the manifest is there, with the listed size and SHA-256;
- the timestamp of every chunk, and of the original of a repaired chunk.

It ends with **COMPLETE** (every listed chunk present and unchanged) or **NOT VERIFIED**, followed by the reason: a chunk missing, altered, or never closed, or a manifest edited after its timestamp. Lines the app added to the manifest after its timestamp (a chunk recovered later) are reported separately, as not covered by that timestamp. Timestamps exist only if timestamping was on in the app's settings.

## Build and Run Instructions

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (latest stable version recommended).
- JDK 11 (specified in `build.gradle.kts`).
- An Android Device or Emulator running Android 8.0 (API level 26) or higher.
- A Google Cloud Console Project with the **Google Drive API** enabled.

### Setup & Compilation
1. **Clone the repository**:
   ```bash
   git clone https://github.com/yourusername/SafeRec.git
   cd SafeRec
   ```
2. **Open in Android Studio**:
   Import the project into Android Studio and let Gradle sync the dependencies.

3. **Configure Google API Credentials**:
   Wait, before you start, ensure you have a Google account. The app requires a specific OAuth 2.0 Client ID to interact with Google Drive.

   #### A. Create the Project
   - Go to the [Google Cloud Console](https://console.cloud.google.com/).
   - Create a new project named **SafeRec** (or any name you prefer).
   - Go to **APIs & Services > Library** and search for **Google Drive API**. Click **Enable**.

   #### B. Configure OAuth Consent Screen
   - Go to **APIs & Services > OAuth consent screen**.
   - Choose **External** (unless you have a Google Workspace org).
   - Fill in the required fields (App name, User support email, Developer contact info).
   - Under **Scopes**, add `.../auth/drive.file` (this allows SafeRec to only access files it creates).

   #### C. Create Android Credentials
   - Go to **APIs & Services > Credentials**.
   - Click **Create Credentials > OAuth client ID**.
   - Select **Android** as the application type.
   - **Name**: SafeRec Android Client
   - **Package name**: `net.ark3us.saferec`
   - **SHA-1 certificate fingerprint**: You need your debug/release fingerprint.

   #### D. Get your SHA-1 Fingerprint
   You can get this by running the following command in your terminal:
   ```bash
   # On Linux/macOS
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   
   # On Windows
   keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```
   Look for the `SHA1` line in the output and paste it into the Google Cloud Console.

   *Note: If you are using a release keystore, replace the path above with your `.jks` file path.*

4. **Build the Project**:
   You can build the APK directly through Android Studio ("Build > Build Bundle(s) / APK(s) > Build APK(s)") or via the command line:
   ```bash
   ./gradlew assembleDebug
   ```

5. **Run the App**:
   Deploy the application to your emulator or physical Android device. Make sure to grant the necessary permissions (Camera, Microphone, Foreground Service, Notifications) upon the first launch, and authenticate with your Google account when prompted to authorize Google Drive access.

## Credits

This application was developed with the assistance of **Antigravity**.

Icons: [Material Symbols](https://fonts.google.com/icons) by Google, Apache License 2.0.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
