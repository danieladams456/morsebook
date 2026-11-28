"""Run after :app:installDebug and :app:installDebugAndroidTest on the chosen device."""
import argparse
import subprocess
import sys
import wave
from pathlib import Path

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
if "OK (1 test)" not in result:
    raise SystemExit(result)
samples = subprocess.check_output(adb + [
    "shell", "-T", "run-as",
    "com.github.danieladams456.morsebook", "cat", "cache/ae-t.pcm",
])
with wave.open(str(args.output), "wb") as wav:
    wav.setparams((1, 2, 48000, 0, "NONE", "not compressed"))
    wav.writeframes(samples)
validator = Path(__file__).resolve().parents[2] / "validator" / "check_audio.py"
subprocess.run([sys.executable, str(validator), str(args.output)], check=True)
