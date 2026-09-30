import json
import logging
import os
import re
import time
from functools import lru_cache
from pathlib import Path

import cv2

from worker_utils import resolve_video_path

logger = logging.getLogger("clip-ai-scoreboard-ocr")

OCR_MODEL_VERSION = os.getenv("OCR_MODEL_VERSION", "PaddleOCR-3.7.0-PP-OCRv6")
OCR_ENABLED = os.getenv("SCOREBOARD_OCR_ENABLED", "true").lower() == "true"
NORMAL_INTERVAL_SECONDS = float(os.getenv("SCOREBOARD_OCR_INTERVAL_SECONDS", "5"))
HIGH_FREQUENCY_INTERVAL_SECONDS = float(os.getenv("SCOREBOARD_OCR_HIGH_FREQUENCY_INTERVAL_SECONDS", "0.5"))
TRANSITION_WINDOW_SECONDS = float(os.getenv("SCOREBOARD_OCR_TRANSITION_WINDOW_SECONDS", "8"))
MINIMUM_CONFIDENCE = float(os.getenv("SCOREBOARD_OCR_MINIMUM_CONFIDENCE", "0.45"))
STABLE_READINGS_REQUIRED = int(os.getenv("SCOREBOARD_OCR_STABLE_READINGS", "2"))
STABLE_WINDOW_SECONDS = float(os.getenv("SCOREBOARD_OCR_STABLE_WINDOW_SECONDS", "12"))
MAXIMUM_SCORE = int(os.getenv("SCOREBOARD_OCR_MAXIMUM_SCORE", "15"))
MAXIMUM_FRAME_WIDTH = int(os.getenv("SCOREBOARD_OCR_MAXIMUM_FRAME_WIDTH", "1280"))

SCORE_PATTERN = re.compile(r"(?<!\d)([0-9OIl]{1,2})\s*[-:–—]\s*([0-9OIl]{1,2})(?!\d)")


def _score_digit(value: str) -> int:
    return int(value.translate(str.maketrans({"O": "0", "o": "0", "I": "1", "l": "1"})))


def parse_score(text: str, maximum_score: int = MAXIMUM_SCORE) -> tuple[int, int] | None:
    normalized = (text or "").replace("−", "-")
    match = SCORE_PATTERN.search(normalized)
    if not match:
        return None
    home, away = (_score_digit(part) for part in match.groups())
    if home > maximum_score or away > maximum_score:
        return None
    return home, away


@lru_cache(maxsize=1)
def load_ocr():
    if not OCR_ENABLED:
        raise RuntimeError("Scoreboard OCR is disabled by configuration")
    from paddleocr import PaddleOCR

    return PaddleOCR(
        lang=os.getenv("SCOREBOARD_OCR_LANGUAGE", "en"),
        device=os.getenv("SCOREBOARD_OCR_DEVICE", "cpu"),
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
    )


def _result_values(result):
    if hasattr(result, "json"):
        result = result.json
    if isinstance(result, str):
        result = json.loads(result)
    if isinstance(result, dict) and "res" in result and isinstance(result["res"], dict):
        result = result["res"]
    if not isinstance(result, dict):
        return [], [], []
    return (
        result.get("rec_texts", []) or [],
        result.get("rec_scores", []) or [],
        result.get("rec_polys", result.get("dt_polys", [])) or [],
    )


def _box_json(box):
    if hasattr(box, "tolist"):
        box = box.tolist()
    return box


def _confidence(scores):
    valid = [float(score) for score in scores if score is not None]
    return sum(valid) / len(valid) if valid else 0.0


def analyze_video(video_path: str, storage_root: str) -> dict:
    resolved = resolve_video_path(video_path, storage_root)
    started = time.monotonic()
    ocr = load_ocr()
    capture = cv2.VideoCapture(str(resolved))
    if not capture.isOpened():
        raise ValueError("Unable to open video for scoreboard OCR")
    fps = capture.get(cv2.CAP_PROP_FPS)
    frame_count = capture.get(cv2.CAP_PROP_FRAME_COUNT)
    duration_seconds = frame_count / fps if fps > 0 and frame_count > 0 else 0
    if duration_seconds <= 0:
        capture.release()
        raise ValueError("Video duration could not be determined")

    observations = []
    stable_score = None
    pending_score = None
    pending_readings = []
    parsed_readings = []
    high_frequency_until = -1.0
    timestamp_seconds = 0.0
    sampled = calls = 0
    processing_error_count = 0
    state_count = transition_count = reversal_count = 0
    try:
        while timestamp_seconds < duration_seconds:
            capture.set(cv2.CAP_PROP_POS_MSEC, timestamp_seconds * 1000)
            success, frame = capture.read()
            if not success or frame is None:
                timestamp_seconds += NORMAL_INTERVAL_SECONDS
                continue
            height, width = frame.shape[:2]
            if width > MAXIMUM_FRAME_WIDTH:
                scale = MAXIMUM_FRAME_WIDTH / width
                frame = cv2.resize(frame, (MAXIMUM_FRAME_WIDTH, round(height * scale)))
            calls += 1
            sampled += 1
            inference_failed = False
            try:
                output = ocr.predict(frame)
            except Exception:
                processing_error_count += 1
                output = []
                inference_failed = True
            texts, scores, boxes = _result_values(output[0] if isinstance(output, list) and output else output)
            raw_text = " ".join(str(text).strip() for text in texts if str(text).strip())
            mean_confidence = _confidence(scores)
            parsed = parse_score(raw_text)
            reason = "OCR_INFERENCE_FAILED" if inference_failed else None
            if parsed is None and not inference_failed:
                reason = "NO_VALID_SCORE_PATTERN"
            elif not inference_failed and mean_confidence < MINIMUM_CONFIDENCE:
                reason = "OCR_CONFIDENCE_BELOW_THRESHOLD"
                parsed = None
            timestamp_ms = round(timestamp_seconds * 1000)
            details = {
                "modelVersion": OCR_MODEL_VERSION,
                "boxes": [_box_json(box) for box in boxes],
                "parsed": parsed is not None,
                "rejectionReason": reason,
            }
            observations.append({
                "timestampMs": timestamp_ms,
                "kind": "OCR_OBSERVATION",
                "rawText": raw_text,
                "confidence": mean_confidence,
                "homeScore": parsed[0] if parsed else None,
                "awayScore": parsed[1] if parsed else None,
                "previousHomeScore": None,
                "previousAwayScore": None,
                "detailsJson": json.dumps(details, separators=(",", ":")),
            })

            if parsed is not None:
                reading = (timestamp_seconds, timestamp_ms, parsed, mean_confidence)
                parsed_readings.append(reading)
                if pending_score != parsed:
                    pending_score = parsed
                    pending_readings = []
                pending_readings = [item for item in pending_readings
                                    if timestamp_seconds - item[0] <= STABLE_WINDOW_SECONDS]
                pending_readings.append(reading)
                if stable_score is not None and parsed != stable_score:
                    high_frequency_until = max(high_frequency_until,
                                               timestamp_seconds + TRANSITION_WINDOW_SECONDS)
                if len(pending_readings) >= STABLE_READINGS_REQUIRED and parsed != stable_score:
                    previous = stable_score
                    stable_score = parsed
                    supporting = pending_readings[-STABLE_READINGS_REQUIRED:]
                    confidence = sum(item[3] for item in supporting) / len(supporting)
                    timestamp = supporting[0][1]
                    observations.append({
                        "timestampMs": timestamp,
                        "kind": "SCORE_STATE",
                        "rawText": raw_text,
                        "confidence": confidence,
                        "homeScore": parsed[0],
                        "awayScore": parsed[1],
                        "previousHomeScore": previous[0] if previous else None,
                        "previousAwayScore": previous[1] if previous else None,
                        "detailsJson": json.dumps({
                            "supportingReadings": len(supporting),
                            "stabilityWindowSeconds": STABLE_WINDOW_SECONDS,
                            "source": "PaddleOCR",
                        }, separators=(",", ":")),
                    })
                    state_count += 1
                    if previous is not None:
                        observations.append({
                            "timestampMs": timestamp,
                            "kind": "SCORE_TRANSITION",
                            "rawText": f"{previous[0]}-{previous[1]} -> {parsed[0]}-{parsed[1]}",
                            "confidence": confidence,
                            "homeScore": parsed[0],
                            "awayScore": parsed[1],
                            "previousHomeScore": previous[0],
                            "previousAwayScore": previous[1],
                            "detailsJson": json.dumps({
                                "source": "PaddleOCR",
                                "supportingReadings": len(supporting),
                            }, separators=(",", ":")),
                        })
                        transition_count += 1
                    high_frequency_until = -1.0

            interval = (HIGH_FREQUENCY_INTERVAL_SECONDS
                        if timestamp_seconds <= high_frequency_until
                        else NORMAL_INTERVAL_SECONDS)
            timestamp_seconds += max(interval, 0.1)
    finally:
        capture.release()

    # A short-lived parsed score that is bracketed by the same stable state is recorded
    # as a reversal observation, never as a football event.
    runs = []
    for reading in parsed_readings:
        if not runs or runs[-1]["score"] != reading[2]:
            runs.append({"score": reading[2], "readings": [reading]})
        else:
            runs[-1]["readings"].append(reading)
    for index in range(1, len(runs) - 1):
        before, temporary, after = runs[index - 1:index + 2]
        if (before["score"] == after["score"] and temporary["score"] != before["score"]
                and len(temporary["readings"]) < STABLE_READINGS_REQUIRED):
                first = before["readings"][-1]
                last = after["readings"][0]
                transient = temporary["readings"]
                observations.append({
                    "timestampMs": transient[0][1],
                    "kind": "SCORE_REVERSAL",
                    "rawText": f"{before['score'][0]}-{before['score'][1]} -> "
                               f"{temporary['score'][0]}-{temporary['score'][1]} -> "
                               f"{after['score'][0]}-{after['score'][1]}",
                    "confidence": min(first[3], *(item[3] for item in transient), last[3]),
                    "homeScore": after["score"][0],
                    "awayScore": after["score"][1],
                    "previousHomeScore": before["score"][0],
                    "previousAwayScore": before["score"][1],
                    "detailsJson": json.dumps({
                        "temporaryHomeScore": temporary["score"][0],
                        "temporaryAwayScore": temporary["score"][1],
                        "rangeStartMs": first[1],
                        "rangeEndMs": last[1],
                        "source": "PaddleOCR",
                    }, separators=(",", ":")),
                })
                reversal_count += 1

    observations.sort(key=lambda item: (item["timestampMs"], item["kind"]))
    elapsed_ms = round((time.monotonic() - started) * 1000)
    logger.info(
        "Scoreboard OCR complete model=%s samples=%d calls=%d raw=%d states=%d transitions=%d "
        "reversals=%d errors=%d durationMs=%d",
        OCR_MODEL_VERSION, sampled, calls,
        sum(item["kind"] == "OCR_OBSERVATION" for item in observations),
        state_count, transition_count, reversal_count, processing_error_count, elapsed_ms,
    )
    return {
        "modelVersion": OCR_MODEL_VERSION,
        "sampledFrameCount": sampled,
        "ocrCallCount": calls,
        "rawObservationCount": sum(item["kind"] == "OCR_OBSERVATION" for item in observations),
        "scoreStateCount": state_count,
        "scoreTransitionCount": transition_count,
        "scoreReversalCount": reversal_count,
        "processingErrorCount": processing_error_count,
        "processingDurationMs": elapsed_ms,
        "observations": observations,
    }
