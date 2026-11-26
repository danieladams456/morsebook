import tempfile
import unittest
import wave
from pathlib import Path

import numpy as np

from validator.check_audio import validate


class AudioChecks(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "fixture.wav"

    def write(self, hz=600, word_gap=420):
        blocks = []
        for i, ms in enumerate([60, 60, 180, 180, 60, word_gap, 180]):
            t = np.arange(ms * 48) / 48000
            blocks.append(18000 * np.sin(2 * np.pi * hz * t) if i % 2 == 0 else np.zeros_like(t))
        with wave.open(str(self.path), "wb") as wav:
            wav.setparams((1, 2, 48000, 0, "NONE", ""))
            wav.writeframes(np.concatenate(blocks).astype("<i2").tobytes())

    def test_good(self):
        self.write()
        validate(self.path)

    def test_truncated(self):
        self.write()
        with self.path.open("r+b") as wav:
            wav.truncate(self.path.stat().st_size - 96)
        with self.assertRaisesRegex(ValueError, "payload expected"):
            validate(self.path)

    def test_pitch(self):
        self.write(hz=650)
        with self.assertRaisesRegex(ValueError, "segment 1 expected 600 Hz, measured 650"):
            validate(self.path)

    def test_word_gap(self):
        self.write(word_gap=180)
        with self.assertRaisesRegex(ValueError, "segment 6 expected 420 ms, measured 180"):
            validate(self.path)
