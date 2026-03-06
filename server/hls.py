"""
HLS example: convert uploaded MP4 chunks to TS segments and maintain a live playlist.
Requires ffmpeg on PATH. Play the session with: GET /{client_id}/sessions/{session_id}/live.m3u8
"""
import asyncio
import os
import subprocess
from pathlib import Path

from logger import log

DATA_DIR = "nosync"
HLS_SUBDIR = "hls"
PLAYLIST_NAME = "live.m3u8"
SEGMENT_PREFIX = "segment_"
SEGMENT_EXT = ".ts"
# Fixed target duration (seconds); increase if your chunks are longer
TARGET_DURATION = 10
# Default segment duration when ffprobe is unavailable
DEFAULT_SEGMENT_DURATION = 3.0


def _data_type_dir(client_id: str, session_id: str, data_type: str) -> Path:
    return Path(DATA_DIR) / client_id / session_id / data_type


def _hls_dir(client_id: str, session_id: str, data_type: str) -> Path:
    return _data_type_dir(client_id, session_id, data_type) / HLS_SUBDIR


def _probe_duration_seconds(mp4_path: str) -> float | None:
    """Probe segment duration with ffprobe. Returns None on failure."""
    try:
        out = subprocess.run(
            [
                "ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                mp4_path,
            ],
            capture_output=True,
            text=True,
            timeout=5,
        )
        if out.returncode == 0 and out.stdout.strip():
            return float(out.stdout.strip())
    except (subprocess.TimeoutExpired, ValueError, FileNotFoundError) as e:
        log.warning("ffprobe failed for %s: %s", mp4_path, e)
    return None


def _mp4_to_ts(mp4_path: str, ts_path: str) -> bool:
    """Convert one MP4 chunk to MPEG-TS segment. Returns True on success."""
    try:
        subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-i", mp4_path,
                "-c", "copy",
                "-f", "mpegts",
                ts_path,
            ],
            capture_output=True,
            timeout=30,
            check=True,
        )
        return True
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError) as e:
        log.error("ffmpeg failed for %s -> %s: %s", mp4_path, ts_path, e)
        return False


def _ensure_playlist_header(playlist_path: Path) -> None:
    """Create playlist file with HLS header if it doesn't exist."""
    if playlist_path.exists():
        return
    playlist_path.parent.mkdir(parents=True, exist_ok=True)
    with open(playlist_path, "w") as f:
        f.write("#EXTM3U\n")
        f.write("#EXT-X-VERSION:3\n")
        f.write("#EXT-X-TARGETDURATION:{}\n".format(TARGET_DURATION))
        f.write("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        f.write("#EXT-X-MEDIA-SEQUENCE:0\n")


def _append_segment_to_playlist(playlist_path: Path, segment_filename: str, duration_sec: float) -> None:
    with open(playlist_path, "a") as f:
        f.write("#EXTINF:{:.3f},\n".format(duration_sec))
        f.write(segment_filename + "\n")


def build_hls_for_new_mp4(
    client_id: str,
    session_id: str,
    data_type: str,
    new_mp4_path: str,
) -> bool:
    """
    Convert the newly uploaded MP4 to a TS segment and append it to the session's HLS playlist.
    Call this after saving an MP4 in the upload handler (e.g. in a thread so upload response isn't blocked).
    Returns True if the segment was added successfully.
    """
    data_dir = _data_type_dir(client_id, session_id, data_type)
    hls_dir = _hls_dir(client_id, session_id, data_type)
    playlist_path = hls_dir / PLAYLIST_NAME

    # List MP4 files (exclude HLS subdir) and sort to get segment index
    mp4_files = sorted(
        f for f in data_dir.iterdir()
        if f.is_file() and f.suffix.lower() == ".mp4"
    )
    segment_index = len(mp4_files) - 1
    if segment_index < 0:
        log.error("No MP4 file found at %s", new_mp4_path)
        return False

    segment_filename = f"{SEGMENT_PREFIX}{segment_index}{SEGMENT_EXT}"
    ts_path = hls_dir / segment_filename

    hls_dir.mkdir(parents=True, exist_ok=True)
    if not _mp4_to_ts(new_mp4_path, str(ts_path)):
        return False

    duration = _probe_duration_seconds(new_mp4_path) or DEFAULT_SEGMENT_DURATION
    _ensure_playlist_header(playlist_path)
    _append_segment_to_playlist(playlist_path, segment_filename, duration)
    log.info("HLS segment %s added for %s/%s", segment_filename, client_id, session_id)
    return True


def get_playlist_path(client_id: str, session_id: str, data_type: str) -> Path | None:
    """Path to live.m3u8 if it exists."""
    p = _hls_dir(client_id, session_id, data_type) / PLAYLIST_NAME
    return p if p.exists() else None


def get_segment_path(client_id: str, session_id: str, data_type: str, segment_index: int) -> Path | None:
    """Path to segment_N.ts if it exists."""
    p = _hls_dir(client_id, session_id, data_type) / f"{SEGMENT_PREFIX}{segment_index}{SEGMENT_EXT}"
    return p if p.exists() else None


async def build_hls_async(
    client_id: str,
    session_id: str,
    data_type: str,
    new_mp4_path: str,
) -> bool:
    """Async wrapper so ffmpeg/ffprobe don't block the event loop."""
    return await asyncio.to_thread(
        build_hls_for_new_mp4,
        client_id,
        session_id,
        data_type,
        new_mp4_path,
    )
