import asyncio
import os
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, StreamingResponse

from logger import log
from hls import (
    build_hls_async,
    get_playlist_path,
    get_segment_path,
)

DATA_TYPES = {
    "audio": "mp4",
    "video": "mp4",
    "mix":   "mp4",
}

DATA_DIR = "nosync"

app = FastAPI()


@app.post("/{client_id}/upload")
async def upload_file(request: Request, client_id: str):
    # multipart/form-data
    form = await request.form()
    file = form.get("file")
    if not file:
        log.error("No file provided")
        return {"error": "No file provided"}
    if not file.size:
        log.error("Empty file provided")
        return {"error": "Empty file provided"}
    # filename format: <session_id>_<data_type>_<timestamp>.<part>
    filename = file.filename
    log.info(f"Received file: {filename} from client: {client_id}")
    parts = filename.split("_")
    if len(parts) < 3:
        log.error("Invalid filename format")
        return {"error": "Invalid filename format"}
    session_id = parts[0]
    data_type = parts[1]
    timestamp_part = parts[2]
    ext = DATA_TYPES.get(data_type)
    # Create directory structure: DATA_DIR/<client_id>/<session_id>/<data_type>/
    upload_dir = os.path.join(DATA_DIR, client_id, session_id, data_type)
    os.makedirs(upload_dir, exist_ok=True)
    file_path = os.path.join(upload_dir, f"{timestamp_part}.{ext}")
    with open(file_path, "wb") as f:
        content = await file.read()
        f.write(content)
    log.info(f"Saved file to: {file_path}")

    # Build HLS segment in background (requires ffmpeg)
    if ext == "mp4":
        asyncio.create_task(
            build_hls_async(client_id, session_id, data_type, file_path)
        )

    return {"status": "success", "file_path": file_path}

def parse_session(client_id: str, session_id: str, full_paths=False) -> dict | None:
    client_dir = os.path.join(DATA_DIR, client_id)
    session_path = os.path.join(client_dir, session_id)
    if not os.path.exists(session_path):
        log.error(f"No data for client_id: {client_id}, session_id: {session_id}")
        return None
    session_data = {}
    for data_type in os.listdir(session_path):
        data_type_path = os.path.join(session_path, data_type)
        if os.path.isdir(data_type_path):
            session_data["data_type"] = data_type
            if full_paths:
                session_data["files"] = [os.path.join(data_type_path, f) for f in os.listdir(data_type_path)]
            else:
                session_data["files"] = os.listdir(data_type_path)
    return session_data


@app.get("/{client_id}/sessions")
async def get_sessions(request: Request, client_id: str):
    client_dir = os.path.join(DATA_DIR, client_id)
    if not os.path.exists(client_dir):
        log.error(f"No data for client_id: {client_id}")
        return {"error": "No data for this client_id"}
    log.info(f"Retrieved sessions for client_id: {client_id}")
    sessions = {}
    for session_id in os.listdir(client_dir):
        session_path = os.path.join(client_dir, session_id)
        if not os.path.isdir(session_path):
            continue
        session_data = parse_session(client_id, session_id)
        if session_data:
            sessions[session_id] = session_data
    return sessions

@app.get("/{client_id}/sessions/{session_id}")
async def get_session(request: Request, client_id: str, session_id: str):
    client_dir = os.path.join(DATA_DIR, client_id)
    session_path = os.path.join(client_dir, session_id)
    if not os.path.exists(session_path):
        log.error(f"No data for client_id: {client_id}, session_id: {session_id}")
        return {"error": "No data for this client_id and session_id"}

    log.info(f"Retrieved data for client_id: {client_id}, session_id: {session_id}")
    session_data = parse_session(client_id, session_id, full_paths=True)
    if not session_data:
        return {"error": "No data for this session"}
    data_type = session_data["data_type"]
    ext = DATA_TYPES.get(data_type)
    mime_type = f"{data_type}/{ext}"
    session_files = session_data["files"]
    async def iterfiles():
        for file_path in sorted(session_files):
            log.info(f"Streaming file: {file_path} mime_type: {mime_type}")
            with open(file_path, "rb") as f:
                yield f.read()
    return StreamingResponse(iterfiles(), media_type=mime_type)


# ---- HLS: play while recording ----
# Play with: GET /{client_id}/sessions/{session_id}/hls/live.m3u8
# (e.g. VLC, ffplay, or hls.js: http://localhost:8000/client1/sessions/<session_id>/hls/live.m3u8)


@app.get("/{client_id}/sessions/{session_id}/hls/live.m3u8")
async def hls_playlist(request: Request, client_id: str, session_id: str):
    session_data = parse_session(client_id, session_id)
    if not session_data:
        return {"error": "No data for this session"}
    data_type = session_data["data_type"]
    playlist_path = get_playlist_path(client_id, session_id, data_type)
    if not playlist_path or not playlist_path.exists():
        return PlainTextResponse(
            "#EXTM3U\n#EXT-X-VERSION:3\n# No segments yet\n",
            media_type="application/vnd.apple.mpegurl",
        )
    return FileResponse(
        playlist_path,
        media_type="application/vnd.apple.mpegurl",
    )


@app.get("/{client_id}/sessions/{session_id}/hls/segment_{segment_index:int}.ts")
async def hls_segment(
    request: Request,
    client_id: str,
    session_id: str,
    segment_index: int,
):
    session_data = parse_session(client_id, session_id)
    if not session_data:
        return {"error": "No data for this session"}
    data_type = session_data["data_type"]
    segment_path = get_segment_path(client_id, session_id, data_type, segment_index)
    if not segment_path or not segment_path.exists():
        return JSONResponse(
            content={"error": "Segment not found"},
            status_code=404,
        )
    return FileResponse(
        segment_path,
        media_type="video/MP2T",
    )


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=8000)