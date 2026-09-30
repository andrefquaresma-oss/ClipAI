import logging
import os
from functools import lru_cache
from pathlib import Path
from typing import Annotated
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field
from faster_whisper import WhisperModel

from worker_utils import resolve_audio_path, seconds_to_milliseconds, resolve_video_path
from scoreboard_ocr import analyze_video as analyze_scoreboard

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("clip-ai-whisper")

MODEL_SIZE = os.getenv("WHISPER_MODEL", "base")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
DEFAULT_LANGUAGE = os.getenv("WHISPER_LANGUAGE") or None
MEDIA_STORAGE_ROOT = os.getenv("MEDIA_STORAGE_ROOT", "./data/media")
MODEL_CACHE = os.getenv("WHISPER_DOWNLOAD_ROOT", "/models")

app = FastAPI(title="Clip AI Transcription Worker", docs_url=None, redoc_url=None)


@app.middleware("http")
async def log_invalid_transcription_requests(request: Request, call_next):
    response = await call_next(request)
    if request.url.path == "/transcriptions" and response.status_code == 422:
        logger.warning(
            "Transcription request rejected content_type=%s content_length=%s transfer_encoding=%s",
            request.headers.get("content-type", "unset"),
            request.headers.get("content-length", "unset"),
            request.headers.get("transfer-encoding", "unset"),
        )
    return response


class TranscriptionRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    audioPath: Annotated[str, Field(min_length=1)]
    language: str | None = None


class TranscriptSegment(BaseModel):
    start_ms: Annotated[int, Field(alias="startMs", ge=0)]
    end_ms: Annotated[int, Field(alias="endMs", gt=0)]
    text: str

    model_config = ConfigDict(populate_by_name=True)


class TranscriptionResponse(BaseModel):
    language: str
    segments: list[TranscriptSegment]


class ScoreboardAnalysisRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    videoPath: Annotated[str, Field(min_length=1)]


@lru_cache(maxsize=1)
def load_model() -> WhisperModel:
    logger.info(
        "Loading whisper model model=%s device=%s compute_type=%s",
        MODEL_SIZE,
        DEVICE,
        COMPUTE_TYPE,
    )
    return WhisperModel(
        MODEL_SIZE,
        device=DEVICE,
        compute_type=COMPUTE_TYPE,
        download_root=MODEL_CACHE,
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/transcriptions", response_model=TranscriptionResponse)
def transcribe(request: TranscriptionRequest) -> TranscriptionResponse:
    try:
        audio_path = resolve_audio_path(request.audioPath, MEDIA_STORAGE_ROOT)
    except (OSError, ValueError) as error:
        raise HTTPException(status_code=422, detail=str(error)) from error

    try:
        model = load_model()
        raw_segments, info = model.transcribe(
            str(audio_path),
            language=request.language or DEFAULT_LANGUAGE,
        )
        segments = []
        for segment in raw_segments:
            text = segment.text.strip()
            if not text:
                continue
            start_ms = seconds_to_milliseconds(segment.start)
            end_ms = max(start_ms + 1, seconds_to_milliseconds(segment.end))
            segments.append(
                TranscriptSegment(startMs=start_ms, endMs=end_ms, text=text)
            )
        return TranscriptionResponse(
            language=info.language or request.language or DEFAULT_LANGUAGE or "und",
            segments=segments,
        )
    except Exception as error:
        logger.exception("Transcription failed")
        raise HTTPException(status_code=502, detail="Transcription engine failed") from error


@app.post("/scoreboards/analyze")
def analyze_scoreboard_video(request: ScoreboardAnalysisRequest) -> dict:
    try:
        resolve_video_path(request.videoPath, MEDIA_STORAGE_ROOT)
    except (OSError, ValueError) as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    try:
        return analyze_scoreboard(request.videoPath, MEDIA_STORAGE_ROOT)
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail="Scoreboard OCR is unavailable") from error
    except Exception as error:
        logger.exception("Scoreboard OCR failed")
        raise HTTPException(status_code=502, detail="Scoreboard OCR failed") from error
