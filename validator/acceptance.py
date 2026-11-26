"""Exercise the installed launcher and measure its completed files."""
import argparse
from pathlib import Path
import subprocess

import numpy as np

from check_audio import validate


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("launcher", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    def render(name, text, size):
        path = args.output / name
        subprocess.run([args.launcher, "--text", text, "--output", path, "--buffer-size", str(size),
                        "--wpm", "20", "--tone-hz", "600", "--sample-rate", "48000"], check=True)
        return path

    subprocess.run([args.launcher, "--help"], check=True)
    small, ms, hz = validate(render("ae-t-257.wav", "AE T", 257))
    large, _, _ = validate(render("ae-t-4096.wav", "AE T", 4096))
    np.testing.assert_array_equal(small, large)
    # TEST AT SEA, hand scored in units, including gaps between letters.
    phrase = [3,3,1,3,1,1,1,1,1,3,3,7,1,1,3,3,3,7,1,1,1,1,1,3,1,3,1,1,3]
    repeats = 12
    units = ((phrase + [7]) * repeats)[:-1]
    passage = " ".join(["TEST AT SEA"] * repeats)
    audio, _, _ = validate(render("passage.wav", passage, 257), [60 * u for u in units])
    report = (f"AE T ({len(small)} identical samples)\nSegment ms: {ms}\nPitch Hz: {hz}\n"
              f"Passage: {len(audio)} samples, {len(units)} validated segments\n")
    (args.output / "measurements.txt").write_text(report)
    print(report)


if __name__ == "__main__":
    main()
