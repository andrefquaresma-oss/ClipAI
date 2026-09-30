import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scoreboard_ocr
from scoreboard_ocr import parse_score


class ScoreboardScoreParserTest(unittest.TestCase):
    def test_parses_score_with_team_context_and_spaces(self) -> None:
        self.assertEqual((1, 0), parse_score("BAR 1 - 0 RAY"))

    def test_normalizes_common_ocr_digit_confusions(self) -> None:
        self.assertEqual((0, 1), parse_score("BAR O - I RAY"))

    def test_rejects_unseparated_numbers_and_arbitrary_text(self) -> None:
        self.assertIsNone(parse_score("minute 19 score 84"))
        self.assertIsNone(parse_score("Barcelona score"))

    def test_rejects_out_of_range_scores(self) -> None:
        self.assertIsNone(parse_score("19-84"))
        self.assertIsNone(parse_score("999-0"))


class FakeCapture:
    def __init__(self, duration_seconds: int) -> None:
        self.duration_seconds = duration_seconds
        self.released = False

    def isOpened(self) -> bool:
        return True

    def get(self, property_id: int) -> float:
        if property_id == scoreboard_ocr.cv2.CAP_PROP_FPS:
            return 1.0
        if property_id == scoreboard_ocr.cv2.CAP_PROP_FRAME_COUNT:
            return float(self.duration_seconds)
        return 0.0

    def set(self, property_id: int, value: float) -> bool:
        return True

    def read(self) -> tuple[bool, SimpleNamespace]:
        return True, SimpleNamespace(shape=(10, 10, 3))

    def release(self) -> None:
        self.released = True


class FakeOcr:
    def __init__(self, readings: list[str]) -> None:
        self.readings = iter(readings)

    def predict(self, frame: SimpleNamespace) -> list[dict]:
        text = next(self.readings)
        return [{"rec_texts": [text], "rec_scores": [0.99], "rec_polys": []}]


class ScoreboardAnalysisTest(unittest.TestCase):
    def run_analysis(self, readings: list[str], duration_seconds: int) -> dict:
        capture = FakeCapture(duration_seconds)
        with (
            patch.object(scoreboard_ocr, "resolve_video_path", return_value=Path("video.mp4")),
            patch.object(scoreboard_ocr, "load_ocr", return_value=FakeOcr(readings)),
            patch.object(scoreboard_ocr.cv2, "VideoCapture", return_value=capture),
            patch.object(scoreboard_ocr, "NORMAL_INTERVAL_SECONDS", 1.0),
            patch.object(scoreboard_ocr, "HIGH_FREQUENCY_INTERVAL_SECONDS", 0.5),
            patch.object(scoreboard_ocr, "TRANSITION_WINDOW_SECONDS", 8.0),
            patch.object(scoreboard_ocr, "MINIMUM_CONFIDENCE", 0.1),
            patch.object(scoreboard_ocr, "STABLE_READINGS_REQUIRED", 2),
        ):
            result = scoreboard_ocr.analyze_video("video.mp4", ".")
        self.assertTrue(capture.released)
        return result

    def test_stable_score_change_emits_transition(self) -> None:
        result = self.run_analysis(
            ["BAR 0-0 RAY", "BAR 0-0 RAY", "BAR 1-0 RAY", "BAR 1-0 RAY",
             "BAR 1-0 RAY", "BAR 1-0 RAY"],
            4,
        )

        self.assertEqual(2, result["scoreStateCount"])
        self.assertEqual(1, result["scoreTransitionCount"])
        transition = next(item for item in result["observations"] if item["kind"] == "SCORE_TRANSITION")
        self.assertEqual((0, 0), (transition["previousHomeScore"], transition["previousAwayScore"]))
        self.assertEqual((1, 0), (transition["homeScore"], transition["awayScore"]))

    def test_short_score_flicker_emits_reversal_not_stable_state(self) -> None:
        result = self.run_analysis(
            ["BAR 0-0 RAY", "BAR 0-0 RAY", "BAR 1-0 RAY", "BAR 0-0 RAY",
             "BAR 0-0 RAY", "BAR 0-0 RAY"],
            4,
        )

        self.assertEqual(1, result["scoreStateCount"])
        self.assertEqual(0, result["scoreTransitionCount"])
        self.assertEqual(1, result["scoreReversalCount"])
        self.assertIn("SCORE_REVERSAL", [item["kind"] for item in result["observations"]])

    def test_ocr_exceptions_are_counted_and_preserved_as_raw_diagnostics(self) -> None:
        capture = FakeCapture(1)

        class FailingOcr:
            def predict(self, frame: SimpleNamespace) -> list[dict]:
                raise RuntimeError("inference failed")

        with (
            patch.object(scoreboard_ocr, "resolve_video_path", return_value=Path("video.mp4")),
            patch.object(scoreboard_ocr, "load_ocr", return_value=FailingOcr()),
            patch.object(scoreboard_ocr.cv2, "VideoCapture", return_value=capture),
        ):
            result = scoreboard_ocr.analyze_video("video.mp4", ".")

        self.assertEqual(1, result["processingErrorCount"])
        raw = next(item for item in result["observations"] if item["kind"] == "OCR_OBSERVATION")
        self.assertEqual("OCR_INFERENCE_FAILED", __import__("json").loads(raw["detailsJson"])["rejectionReason"])


if __name__ == "__main__":
    unittest.main()
