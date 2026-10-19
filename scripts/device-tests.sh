#!/usr/bin/env bash
# On-device reliability tests for SafeRec.
#
# Drives a connected phone with adb and checks logcat, the local chunk store
# and chunk durations (ffprobe). Needs a debug build (run-as) and adb + ffprobe.
# Pick a device with ANDROID_SERIAL. Usage:
#
#   scripts/device-tests.sh                 # all scenarios
#   scripts/device-tests.sh offline_start replay
#
# Side effects (all restored or cleaned on exit):
#   - records camera and microphone (lay the phone face down)
#   - toggles airplane mode and Wi-Fi, blocks SafeRec's own network (Android 13+)
#   - rewrites SafeRec settings, deletes the test chunks it created
#   - opens the default video camera app to steal the camera
# It refuses to run if the chunk store already holds files: they may be real
# evidence not uploaded yet.

set -uo pipefail

PKG=net.ark3us.saferec
ACTIVITY="$PKG/.MainActivity"
APP_DIR="/data/user/0/$PKG"
STORE="$APP_DIR/files/data_store" # upload outbox: complete chunks only
WORK="$APP_DIR/files/recording"    # chunks being written
PREFS="$APP_DIR/shared_prefs/SafeRecPrefs.xml"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${OUT:-$ROOT/tmp/device-tests/$(date +%Y%m%d-%H%M%S)}"
TAGS=(SafeRecService MuxerSink VideoStreamRecorder AudioStreamRecorder GoogleDriveFileUploader
      GoogleDriveClient FreeTSAClient MainActivity AndroidRuntime)
CHUNK_MIN=8.5   # seconds, expected chunk duration window (target 10 s)
CHUNK_MAX=12.5
ALL_SCENARIOS=(offline_start audio_only camera_stolen replay errors_dont_stop network_drop retry_after_outage tsa)

RESULTS=()
LOGCAT_PID=""
ORIGINAL_PREFS=""
INITIAL_AIRPLANE=0
INITIAL_WIFI=1
INITIAL_CHAIN3=""
APP_BLOCK=0 # 1 when the device can block the network of SafeRec alone

# --- Output -----------------------------------------------------------------

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*"; }
record() { # scenario status detail
    RESULTS+=("$1|$2|$3")
    local color=32
    [[ $2 == FAIL ]] && color=31
    [[ $2 == SKIP ]] && color=33
    printf '  \033[1;%sm%s\033[0m %s: %s\n' "$color" "$2" "$1" "$3"
}

# --- Device helpers -----------------------------------------------------------

app_sh() { adb shell run-as "$PKG" "$@"; } # runs as the app, debug builds only

# Logs come from the host-side capture started in setup: the device buffer wraps
# under load (failed uploads log long stack traces) and silently drops old lines.
SCENARIO_LINE=1
TAG_RE=$(
    IFS='|'
    echo "${TAGS[*]}"
)
mark_scenario() { SCENARIO_LINE=$(($(wc -l <"$OUT/full.log") + 1)); }
logs() { tail -n +"$SCENARIO_LINE" "$OUT/full.log" | grep -E " [VDIWEF] ($TAG_RE) *: "; }
count_log() { logs | grep -cE "$1"; }
wait_log() { # pattern timeout_s
    local end=$((SECONDS + $2))
    while ((SECONDS < end)); do
        logs | grep -E "$1" >/dev/null && return 0
        sleep 1
    done
    return 1
}

is_online() { adb shell ping -c1 -W2 1.1.1.1 >/dev/null 2>&1; }

# Airplane mode alone is not an outage when a VPN app keeps its tunnel up: the app's packets
# vanish into it and every request hangs until its timeout instead of failing. Blocking
# SafeRec's own traffic makes the outage real whatever the VPN does.
set_app_network() { # true|false
    if ((APP_BLOCK)); then
        adb shell cmd connectivity set-package-networking-enabled "$1" "$PKG"
    fi
}
go_offline() {
    set_app_network false
    adb shell cmd connectivity airplane-mode enable
    adb shell svc wifi disable # some ROMs keep Wi-Fi on in airplane mode
    for _ in $(seq 15); do
        is_online || return 0
        sleep 1
    done
    return 1
}
go_online() {
    set_app_network true # first, so the app can use the network as soon as it is back
    adb shell cmd connectivity airplane-mode disable
    [[ $INITIAL_WIFI != 0 ]] && adb shell svc wifi enable
    for _ in $(seq 40); do
        is_online && return 0
        sleep 1
    done
    return 1
}

current_token() { app_sh cat "$PREFS" 2>/dev/null | sed -n 's/.*name="accessToken">\([^<]*\)<.*/\1/p'; }
token_fingerprint() { # never prints the token itself
    local t
    t=$(current_token)
    if [[ -z $t ]]; then echo none; else printf '%s' "$t" | sha256sum | cut -c1-12; fi
}

write_prefs() { # only_audio timestamping
    local token
    token=$(current_token)
    adb shell am force-stop "$PKG" # SharedPreferences are cached in memory
    {
        echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
        echo "<map>"
        echo "    <boolean name=\"tutorialShown\" value=\"true\" />"
        echo "    <boolean name=\"recordingsTutorialShown\" value=\"true\" />"
        echo "    <boolean name=\"autoStartOnLaunch\" value=\"true\" />"
        echo "    <boolean name=\"onlyAudio\" value=\"$1\" />"
        echo "    <boolean name=\"timestampingEnabled\" value=\"$2\" />"
        echo "    <boolean name=\"useFrontCamera\" value=\"false\" />"
        echo "    <int name=\"chunkSizeMB\" value=\"0\" />"
        echo "    <string name=\"videoQuality\">LOW</string>"
        [[ -n $token ]] && echo "    <string name=\"accessToken\">$token</string>"
        echo "</map>"
    } | write_app_file "$PREFS"
}

# adb exec-in does not honour quotes, adb shell forwards stdin fine.
# umask 007 gives the 660/770 modes the app itself would use.
write_app_file() { # path, content on stdin
    adb shell "run-as $PKG sh -c 'umask 007; mkdir -p $(dirname "$1") && cat > $1'"
}

list_store() { app_sh ls -1 "$STORE" 2>/dev/null | tr -d '\r' | grep -E '\.(mp4|tsa)$' || true; }
list_work() { app_sh ls -1 "$WORK" 2>/dev/null | tr -d '\r' | grep . || true; }
# adb shell re-joins its arguments, so the inner command needs its own quotes
clear_store() { adb shell "run-as $PKG sh -c 'rm -f $STORE/* $WORK/*'" 2>/dev/null || true; }

launch() { # -f must come before --activity-clear-task, which adds to the flags
    adb shell am start -n "$ACTIVITY" "$@" >/dev/null
}
tile_start() { launch --activity-clear-task --es command START --ez from_tile true; }
start_recording() { # -> 0 when the service logs the session start
    mark_scenario
    tile_start
    wait_log "Starting recording session" 15
}
stop_recording() {
    launch --activity-clear-task --es command STOP
    wait_log "AudioRecorder stopped" 15
    sleep 3 # let the last chunk close and the post-processing run
    adb shell input keyevent KEYCODE_HOME
}
premature_stops() { count_log "AudioRecorder stopped"; } # call before stop_recording
saved_chunks() { count_log "Processing saved chunk: .*\.mp4"; }
duplicate_uploads() { count_log "Failed to delete file after upload"; } # 2nd copy of an uploaded file

# --- Chunk analysis -------------------------------------------------------------

pull_chunks() { # dest_dir
    mkdir -p "$1"
    local f
    for f in $(list_store | grep '\.mp4$'); do
        adb exec-out run-as "$PKG" cat "$STORE/$f" >"$1/$f"
    done
}

# Checks every chunk but the last (still being closed by the stop) lasts ~10 s.
# Prints "seq:duration[streams] ..." and returns 1 on any out-of-range duration.
check_chunks() { # dir streams_regex
    local dir=$1 want=$2 files f d s seq i n bad=0 summary=""
    mapfile -t files < <(ls "$dir" 2>/dev/null | grep '\.mp4$' | sort -t. -k2,2n)
    n=${#files[@]}
    for ((i = 0; i < n; i++)); do
        f=${files[i]}
        seq=$(cut -d. -f2 <<<"$f")
        d=$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$dir/$f" 2>/dev/null)
        # Only streams with packets count: an empty audio track means audio was lost
        s=$(ffprobe -v error -count_packets -show_entries stream=codec_type,nb_read_packets -of csv=p=0 \
            "$dir/$f" 2>/dev/null | awk -F, '$2 + 0 > 0 { print $1 }' | sort | paste -sd+)
        summary+="#$seq:${d%%.*}s[$s] "
        [[ $s =~ ^($want)$ ]] || bad=1
        if ((i < n - 1)); then
            awk -v d="$d" -v lo=$CHUNK_MIN -v hi=$CHUNK_MAX 'BEGIN { exit !(d + 0 >= lo && d + 0 <= hi) }' || bad=1
        fi
    done
    ((n < 2)) && bad=1
    echo "$n chunks: $summary"
    return $bad
}

# --- Scenarios ------------------------------------------------------------------

scenario_offline_start() {
    say "offline_start: no network, tile with auto-start → must record at once"
    write_prefs false false
    clear_store
    go_offline || { record offline_start FAIL "could not go offline"; return; }
    local t0=$SECONDS
    if ! start_recording; then
        record offline_start FAIL "no recording within 15 s while offline"
        go_online
        return
    fi
    local latency=$((SECONDS - t0))
    sleep 35
    local premature unknown refresh saved durations ok=1
    premature=$(premature_stops)
    unknown=$(count_log "keeping file")
    refresh=$(count_log "Silent refresh|Access token rejected")
    stop_recording
    saved=$(saved_chunks)
    logs >"$OUT/offline_start.log"
    pull_chunks "$OUT/offline_start"
    durations=$(check_chunks "$OUT/offline_start" "audio\+video") || ok=0
    clear_store # before going online, or the outbox retry would upload these test chunks
    go_online
    note "start latency ${latency}s, saved chunks $saved, premature stops $premature"
    note "failed uploads kept for later: $unknown, token refresh attempts: $refresh"
    note "$durations"
    ((latency <= 10 && premature == 0 && saved >= 3 && unknown >= 1 && refresh == 0)) || ok=0
    if ((ok)); then
        record offline_start PASS "started in ${latency}s offline, $saved chunks of ~10 s, token untouched"
    else
        record offline_start FAIL "latency=${latency}s saved=$saved premature=$premature unknown=$unknown refresh=$refresh"
    fi
}

scenario_audio_only() {
    say "audio_only: chunks must be ~10 s (were ~4.4 min)"
    write_prefs true false
    clear_store
    go_offline || { record audio_only FAIL "could not go offline"; return; }
    start_recording || { record audio_only FAIL "recording did not start"; go_online; return; }
    sleep 35
    local premature durations ok=1
    premature=$(premature_stops)
    stop_recording
    logs >"$OUT/audio_only.log"
    pull_chunks "$OUT/audio_only"
    durations=$(check_chunks "$OUT/audio_only" "audio") || ok=0
    clear_store # before going online, or the outbox retry would upload these test chunks
    go_online
    note "$durations"
    ((premature == 0)) || ok=0
    if ((ok)); then
        record audio_only PASS "${durations%%:*}, all ~10 s, audio only"
    else
        record audio_only FAIL "premature=$premature; $durations"
    fi
}

scenario_camera_stolen() {
    say "camera_stolen: another app takes the camera → audio must go on"
    write_prefs false false
    clear_store
    go_offline || { record camera_stolen FAIL "could not go offline"; return; }
    start_recording || { record camera_stolen FAIL "recording did not start"; go_online; return; }
    sleep 15
    local before after premature fatal durations ok=1 fell_back=0
    before=$(saved_chunks)
    adb shell am start -a android.media.action.VIDEO_CAMERA >/dev/null
    wait_log "continuing with audio only" 20 && fell_back=1
    sleep 25
    after=$(saved_chunks)
    premature=$(premature_stops)
    fatal=$(count_log "FATAL EXCEPTION")
    stop_recording
    logs >"$OUT/camera_stolen.log"
    pull_chunks "$OUT/camera_stolen"
    durations=$(check_chunks "$OUT/camera_stolen" "audio\+video|audio") || ok=0
    clear_store # before going online, or the outbox retry would upload these test chunks
    go_online
    note "fallback logged: $fell_back, chunks before/after steal: $before/$after"
    note "premature stops $premature, fatal $fatal"
    note "$durations"
    ((fell_back == 1 && premature == 0 && fatal == 0 && after > before)) || ok=0
    [[ $durations == *"[audio]"* ]] || ok=0
    if ((ok)); then
        record camera_stolen PASS "fell back to audio only, chunks kept coming ($before → $after)"
    else
        record camera_stolen FAIL "fallback=$fell_back before=$before after=$after premature=$premature fatal=$fatal"
    fi
}

scenario_replay() {
    say "replay: relaunch from Recents must not replay the tile's START"
    write_prefs false false
    clear_store
    mark_scenario
    # NEW_TASK | LAUNCHED_FROM_HISTORY, like Recents re-delivering the tile intent
    launch -f 0x10100000 --activity-clear-task --es command START --ez from_tile true
    sleep 8
    local started
    started=$(count_log "Starting recording session")
    logs >"$OUT/replay.log"
    if ((started > 0)); then
        stop_recording
        record replay FAIL "the replayed START started a recording"
    else
        adb shell input keyevent KEYCODE_HOME
        record replay PASS "replayed START ignored"
    fi
}

scenario_errors_dont_stop() {
    say "errors_dont_stop: online; Drive/auth errors must not stop the recording"
    write_prefs false false
    clear_store
    go_online || { record errors_dont_stop FAIL "could not go online"; return; }
    start_recording || { record errors_dont_stop FAIL "recording did not start"; return; }
    sleep 40
    local premature auth_errors uploads saved dups ok=1
    premature=$(premature_stops)
    auth_errors=$(count_log "Silent refresh requires user interaction|Silent refresh returned null")
    uploads=$(count_log "uploaded successfully|Retry successful")
    stop_recording
    sleep 8 # the relaunch after STOP scans pending files: duplicates would show up now
    saved=$(saved_chunks)
    dups=$(duplicate_uploads)
    logs >"$OUT/errors_dont_stop.log"
    note "auth errors $auth_errors, successful uploads $uploads, saved chunks $saved, premature stops $premature, duplicates $dups"
    ((premature == 0 && saved >= 4 && (auth_errors + uploads) >= 1 && dups == 0)) || ok=0
    if ((ok)); then
        if ((uploads > 0)); then
            record errors_dont_stop PASS "uploads worked ($uploads), recording ran the full 40 s"
        else
            record errors_dont_stop PASS "$auth_errors auth errors reported, recording ran the full 40 s"
        fi
    else
        record errors_dont_stop FAIL "premature=$premature saved=$saved auth_errors=$auth_errors uploads=$uploads dups=$dups"
    fi
}

scenario_network_drop() {
    say "network_drop: network lost for 30 s mid-recording"
    write_prefs false false
    clear_store
    go_online || { record network_drop FAIL "could not go online"; return; }
    local token_before token_after
    token_before=$(token_fingerprint)
    start_recording || { record network_drop FAIL "recording did not start"; return; }
    sleep 12
    go_offline || note "warning: still online after enabling airplane mode"
    sleep 30
    go_online || note "warning: network did not come back"
    sleep 12
    local premature unknown saved dups ok=1
    premature=$(premature_stops)
    unknown=$(count_log "keeping file")
    stop_recording
    sleep 8 # let the post-STOP pending scan run
    saved=$(saved_chunks)
    dups=$(duplicate_uploads)
    token_after=$(token_fingerprint)
    logs >"$OUT/network_drop.log"
    note "saved chunks $saved, premature stops $premature, kept-for-later uploads $unknown, duplicates $dups"
    note "token before/after: $token_before / $token_after"
    ((premature == 0 && saved >= 5 && unknown >= 1 && dups == 0)) || ok=0
    [[ $token_before == "$token_after" ]] || ok=0
    if ((ok)); then
        record network_drop PASS "recording continued through the outage, token unchanged ($token_after)"
    else
        record network_drop FAIL "premature=$premature saved=$saved unknown=$unknown dups=$dups token $token_before→$token_after"
    fi
}

scenario_retry_after_outage() {
    say "retry_after_outage: chunks that failed offline must go out once the network is back, while still recording"
    write_prefs false false
    clear_store
    go_online || { record retry_after_outage FAIL "could not go online"; return; }
    if [[ $(token_fingerprint) == none ]]; then
        record retry_after_outage SKIP "needs a Drive token: give the Google consent in the app first"
        return
    fi
    start_recording || { record retry_after_outage FAIL "recording did not start"; return; }
    sleep 12
    go_offline || note "warning: still online after enabling airplane mode"
    sleep 25
    go_online || note "warning: network did not come back"
    # Still recording: the outbox (complete chunks only) must drain without waiting for STOP
    local back=$SECONDS pending=-1 drained
    while ((SECONDS - back < 45)); do
        pending=$(list_store | grep -c '\.mp4$')
        ((pending == 0)) && break
        sleep 2
    done
    drained=$((SECONDS - back))
    local premature unknown network_retry
    premature=$(premature_stops)
    unknown=$(count_log "keeping file")
    network_retry=$(count_log "retrying pending uploads")
    stop_recording
    sleep 8
    local saved uploaded dups ok=1
    saved=$(saved_chunks)
    uploaded=$(count_log "uploaded successfully|Retry successful")
    dups=$(duplicate_uploads)
    logs >"$OUT/retry_after_outage.log"
    note "outbox while recording: $pending chunk(s) left ${drained}s after the network came back"
    note "failed while offline $unknown, network-triggered retries $network_retry"
    note "saved $saved, uploaded $uploaded, duplicates $dups, premature stops $premature"
    ((premature == 0 && unknown >= 1 && network_retry >= 1 && pending == 0 && uploaded >= saved && dups == 0)) || ok=0
    if ((ok)); then
        record retry_after_outage PASS "outbox drained ${drained}s after the network returned, during recording; $uploaded/$saved uploaded"
    else
        record retry_after_outage FAIL "pending=$pending after ${drained}s, unknown=$unknown network_retry=$network_retry uploaded=$uploaded saved=$saved dups=$dups premature=$premature"
    fi
}

scenario_tsa() {
    say "tsa: every chunk is handed to the uploader without waiting for the TSA"
    write_prefs false true
    clear_store
    go_online || { record tsa FAIL "could not go online"; return; }
    start_recording || { record tsa FAIL "recording did not start"; return; }
    sleep 33
    stop_recording
    sleep 10 # TSA requests finish after the stop
    local saved attempts stamped tsa_failed dups ok=1
    saved=$(saved_chunks)
    dups=$(duplicate_uploads)
    attempts=$(count_log "Uploading file \(seq=[0-9]+\) to Google Drive: .*\.mp4")
    stamped=$(count_log "Successfully timestamped")
    tsa_failed=$(count_log "Failed to timestamp|TSA responded with code|Invalid content type|validate TSA")
    logs >"$OUT/tsa.log"
    # Per chunk: when it was saved, when its upload started, when its timestamp came back
    logs | grep -E "Processing saved chunk: .*\.mp4|Uploading file \(seq=.*\.mp4|Successfully timestamped" |
        awk '{ print "    " $2 " " substr($0, index($0, $7)) }' >"$OUT/tsa-timeline.txt"
    note "saved chunks $saved, upload attempts $attempts, timestamps $stamped, TSA failures $tsa_failed, duplicates $dups"
    sed 's/^/  /' "$OUT/tsa-timeline.txt"
    # One upload attempt per chunk: more means a chunk was uploaded twice
    ((saved >= 3 && attempts == saved && dups == 0 && stamped >= saved - 1)) || ok=0
    if ((ok)); then
        record tsa PASS "$attempts upload attempts for $saved chunks, $stamped timestamps, no duplicates"
    else
        record tsa FAIL "saved=$saved attempts=$attempts stamped=$stamped tsa_failed=$tsa_failed dups=$dups"
    fi
}

# --- Setup / teardown ---------------------------------------------------------

cleanup() {
    say "cleanup"
    [[ -n $LOGCAT_PID ]] && kill "$LOGCAT_PID" 2>/dev/null
    adb shell am force-stop "$PKG"
    clear_store
    if [[ -n $ORIGINAL_PREFS ]]; then
        printf '%s\n' "$ORIGINAL_PREFS" | write_app_file "$PREFS"
    else
        app_sh rm -f "$PREFS"
    fi
    if [[ $INITIAL_AIRPLANE == 1 ]]; then
        adb shell cmd connectivity airplane-mode enable
    else
        adb shell cmd connectivity airplane-mode disable
    fi
    if [[ $INITIAL_WIFI == 0 ]]; then adb shell svc wifi disable; else adb shell svc wifi enable; fi
    if ((APP_BLOCK)); then
        set_app_network true
        [[ $INITIAL_CHAIN3 == chain:disabled ]] && adb shell cmd connectivity set-chain3-enabled false
    fi
    adb shell input keyevent KEYCODE_HOME
    note "network, settings and chunk store restored"
}

setup() {
    say "setup → results in $OUT"
    mkdir -p "$OUT"
    adb get-state >/dev/null 2>&1 || { echo "No device connected"; exit 1; }
    command -v ffprobe >/dev/null || { echo "ffprobe not found"; exit 1; }
    [[ -f $APK ]] || { echo "APK not found: $APK (run ./gradlew assembleDebug)"; exit 1; }
    note "device: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
    if ! adb install -r -t "$APK" >/dev/null; then
        echo "Install failed (signature mismatch?). Not uninstalling: that would delete app data."
        exit 1
    fi
    local pending
    pending=$(($(list_store | wc -l) + $(list_work | wc -l)))
    if ((pending > 0)); then
        echo "The chunk store holds $pending files that may not be uploaded yet. Refusing to run."
        exit 1
    fi
    for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do
        adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null
    done
    ORIGINAL_PREFS=$(app_sh cat "$PREFS" 2>/dev/null | tr -d '\r' || true)
    INITIAL_AIRPLANE=$(adb shell settings get global airplane_mode_on | tr -d '\r')
    INITIAL_WIFI=$(adb shell settings get global wifi_on | tr -d '\r')
    trap cleanup EXIT
    INITIAL_CHAIN3=$(adb shell cmd connectivity get-chain3-enabled 2>/dev/null | tr -d '\r')
    if [[ $INITIAL_CHAIN3 == chain:* ]]; then
        APP_BLOCK=1
        adb shell cmd connectivity set-chain3-enabled true
        set_app_network true # a killed run may have left SafeRec blocked
    else
        note "warning: cannot block SafeRec's network alone, a VPN may keep it online in airplane mode"
    fi
    adb logcat -c
    adb logcat -v threadtime >"$OUT/full.log" 2>/dev/null &
    LOGCAT_PID=$!
}

summary() {
    say "summary"
    local fatal line
    fatal=$(grep -A2 "FATAL EXCEPTION" "$OUT/full.log" 2>/dev/null | grep -c "Process: $PKG")
    if ((fatal == 0)); then
        record no_crash PASS "no SafeRec crash in the whole run"
    else
        record no_crash FAIL "$fatal SafeRec crash(es), see $OUT/full.log"
    fi
    printf '\n' | tee "$OUT/summary.txt"
    for line in "${RESULTS[@]}"; do
        IFS='|' read -r name status detail <<<"$line"
        printf '%-18s %-5s %s\n' "$name" "$status" "$detail" | tee -a "$OUT/summary.txt"
    done
}

main() {
    local scenarios=("$@")
    ((${#scenarios[@]})) || scenarios=("${ALL_SCENARIOS[@]}")
    setup
    for s in "${scenarios[@]}"; do
        "scenario_$s"
    done
    summary
}

# Run only when executed, so other scripts can source the helpers
[[ ${BASH_SOURCE[0]} == "$0" ]] && main "$@"
