from pathlib import Path


def seconds_to_milliseconds(seconds: float) -> int:
    if seconds < 0:
        raise ValueError("time must not be negative")
    return int(round(seconds * 1000))


def resolve_audio_path(audio_path: str, storage_root: str) -> Path:
    root = Path(storage_root).expanduser().resolve()
    candidate = Path(audio_path).expanduser().resolve(strict=True)
    try:
        candidate.relative_to(root)
    except ValueError as error:
        raise ValueError("audioPath must be inside the configured media storage root") from error
    if not candidate.is_file() or candidate.suffix.lower() != ".wav":
        raise ValueError("audioPath must refer to a WAV file")
    return candidate


def resolve_video_path(video_path: str, storage_root: str) -> Path:
    root = Path(storage_root).expanduser().resolve()
    candidate = Path(video_path).expanduser().resolve(strict=True)
    try:
        candidate.relative_to(root)
    except ValueError as error:
        raise ValueError("videoPath must be inside the configured media storage root") from error
    if not candidate.is_file() or candidate.suffix.lower() not in {".mp4", ".mkv", ".webm", ".mov", ".avi"}:
        raise ValueError("videoPath must refer to a supported video file")
    return candidate
