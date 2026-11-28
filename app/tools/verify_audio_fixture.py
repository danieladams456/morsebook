"""Run after :app:installDebug and :app:installDebugAndroidTest on the chosen device."""
import argparse
import subprocess
import sys
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "validator"))
from check_audio import PRIMARY_MS, validate


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path, help="destination WAV for the accepted samples")
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
args = parser.parse_args()
adb = [args.adb, "-s", args.serial]
result = subprocess.check_output(adb + [
    "shell", "am", "instrument", "-w", "-e", "class",
    "com.github.danieladams456.morsebook.app.playback.AudioFixtureTest",
    "com.github.danieladams456.morsebook.test/androidx.test.runner.AndroidJUnitRunner",
], text=True)
if "OK (2 tests)" not in result:
    raise SystemExit(result)


def export(cache_file, destination):
    samples = subprocess.check_output(adb + [
        "shell", "-T", "run-as",
        "com.github.danieladams456.morsebook", "cat", f"cache/{cache_file}.pcm",
    ])
    with wave.open(str(destination), "wb") as wav:
        wav.setparams((1, 2, 48000, 0, "NONE", "not compressed"))
        wav.writeframes(samples)


export("ae-t", args.output)
edited = args.output.with_name(f"{args.output.stem}-edited.wav")
export("ae-t-edited", edited)
for path, timings, hz in [(args.output, PRIMARY_MS, 600),
                          (edited, (48, 48, 144, 144, 48, 336, 144), 700)]:
    _, measured, pitches = validate(path, expected_ms=timings, hz=hz)
    print(path, "ms:", measured, "Hz:", pitches)
