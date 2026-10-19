"""Measures the A/V lag of an MP4 recorded while the phone vibrated at irregular times.

The vibration motor is one physical event seen by both tracks: its buzz reaches the
microphone and its shake moves the camera image. Cross-correlating the audio buzz envelope
with the video motion energy gives the lag between the tracks. Prints JSON; a positive
lag_ms means the audio comes later than the video.

Usage: av_lag.py <file.mp4> [start_s end_s]   (needs ffmpeg/ffprobe and numpy)
"""
import json
import subprocess
import sys

import numpy as np

STEP = 0.005  # common time grid, seconds
RATE = 8000
SKIP_S = 1.5  # auto exposure is still settling after the camera opens
MIN_SPAN_S = 5.0
MAX_LAG_S = 1.5


def probe(path, args):
    out = subprocess.run(["ffprobe", "-v", "error", "-of", "json", *args, path],
                         capture_output=True, check=True, text=True).stdout
    return json.loads(out)


def audio_envelope(path):
    """RMS of the band around the motor buzz, one value per STEP, and its start time."""
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:a", "-ac", "1", "-ar", str(RATE),
                          "-af", "highpass=f=100,lowpass=f=500", "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    audio = np.frombuffer(pcm, np.int16).astype(np.float64)
    hop = int(RATE * STEP)
    env = np.sqrt((audio[: len(audio) // hop * hop] ** 2).reshape(-1, hop).mean(axis=1))
    streams = {s["codec_type"]: s for s in probe(path, ["-show_streams"])["streams"]}
    return env, float(streams["audio"].get("start_time", 0))


def video_motion(path):
    """Frame times and frame-to-frame change on the most textured pixels."""
    frames = probe(path, ["-select_streams", "v", "-show_entries",
                          "frame=best_effort_timestamp_time,pict_type", "-show_frames"])["frames"]
    times = np.array([float(f["best_effort_timestamp_time"]) for f in frames])
    key = np.array([f.get("pict_type") == "I" for f in frames])
    w, h = 90, 160
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-map", "0:v", "-vf", f"scale={w}:{h},format=gray",
                          "-fps_mode", "passthrough", "-f", "rawvideo", "-"], capture_output=True, check=True).stdout
    pixels = np.frombuffer(raw, np.uint8).reshape(-1, h, w).astype(np.float64)[:, : int(h * 0.9)]  # no clock overlay
    n = min(len(pixels), len(times))
    times, key, pixels = times[:n], key[:n], pixels[:n]
    # Shake moves edges; flat areas only add noise
    grad = np.hypot(*np.gradient(pixels.mean(axis=0)))
    edges = grad >= np.quantile(grad, 0.9)
    motion = np.r_[0, np.abs(np.diff(pixels, axis=0))[:, edges].mean(axis=1)]
    # Key frames are encoded differently: the change around them is not motion
    masked = key | np.r_[False, key[:-1]]
    motion[masked] = np.median(motion[~masked])
    return times, motion


def main():
    path = sys.argv[1]
    start, end = (float(sys.argv[2]), float(sys.argv[3])) if len(sys.argv) > 3 else (-1e9, 1e9)
    name = path.rsplit("/", 1)[-1]
    env, a0 = audio_envelope(path)
    vt, motion = video_motion(path)

    grid = np.arange(max(a0, vt[0]) + SKIP_S, min(a0 + len(env) * STEP, vt[-1]), STEP)
    grid = grid[(grid >= start) & (grid < end)]
    if len(grid) * STEP < MIN_SPAN_S:
        print(json.dumps({"file": name, "skipped": f"shorter than {MIN_SPAN_S:g} s"}))
        return
    a = np.interp(grid, a0 + np.arange(len(env)) * STEP, env)
    m = motion[np.clip(np.searchsorted(vt, grid), 0, len(vt) - 1)]  # frame i spans (t[i-1], t[i]]
    # One transient (someone walking by) must not outweigh the repeated pulses
    a, m = (np.minimum(x, 6 * np.median(x)) for x in (a, m))
    a, m = ((x - x.mean()) / (x.std() + 1e-12) for x in (a, m))

    lags = np.arange(-int(MAX_LAG_S / STEP), int(MAX_LAG_S / STEP) + 1)
    corr = np.array([np.mean(a[max(0, k):len(a) + min(0, k)] * m[max(0, -k):len(m) - max(0, k)]) for k in lags])
    best = int(np.argmax(corr))
    frac = 0.0
    if 0 < best < len(corr) - 1:  # parabola through the peak
        y0, y1, y2 = corr[best - 1: best + 2]
        frac = 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2)
    print(json.dumps({"file": name, "lag_ms": round(float((lags[best] + frac) * STEP * 1000), 1),
                      "peak": round(float(corr[best]), 3), "span_s": round(float(len(grid) * STEP), 1)}))


if __name__ == "__main__":
    main()
