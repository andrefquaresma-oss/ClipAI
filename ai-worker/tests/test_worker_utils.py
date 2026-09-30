import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from worker_utils import resolve_audio_path, resolve_video_path, seconds_to_milliseconds


class WorkerUtilsTest(unittest.TestCase):
    def test_seconds_are_rounded_to_milliseconds(self) -> None:
        self.assertEqual(1234, seconds_to_milliseconds(1.2344))

    def test_rejects_negative_timestamp(self) -> None:
        with self.assertRaises(ValueError):
            seconds_to_milliseconds(-0.1)

    def test_resolves_wav_inside_configured_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audio = root / "media" / "asset" / "audio.wav"
            audio.parent.mkdir(parents=True)
            audio.write_bytes(b"RIFF")

            self.assertEqual(audio.resolve(), resolve_audio_path(str(audio), directory))

    def test_rejects_audio_path_outside_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            audio = Path(outside) / "audio.wav"
            audio.write_bytes(b"RIFF")

            with self.assertRaisesRegex(ValueError, "inside"):
                resolve_audio_path(str(audio), directory)

    def test_resolves_supported_video_inside_configured_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / "media" / "asset" / "source.mp4"
            video.parent.mkdir(parents=True)
            video.write_bytes(b"video")

            self.assertEqual(video.resolve(), resolve_video_path(str(video), directory))

    def test_rejects_video_path_outside_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            video = Path(outside) / "source.mp4"
            video.write_bytes(b"video")

            with self.assertRaisesRegex(ValueError, "inside"):
                resolve_video_path(str(video), directory)


if __name__ == "__main__":
    unittest.main()
