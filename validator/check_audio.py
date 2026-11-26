"""Measure mono PCM timing and pitch against an independent run sheet."""
import argparse
import wave

import numpy as np


PRIMARY_MS = (60, 60, 180, 180, 60, 420, 180)


def validate(path, expected_ms=PRIMARY_MS, rate=48000, hz=600):
    with wave.open(str(path), "rb") as wav:
        fmt = (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype())
        expected_fmt = (1, 2, rate, "NONE")
        if fmt != expected_fmt:
            raise ValueError(f"format expected {expected_fmt}, measured {fmt}")
        declared = wav.getnframes()
        raw = wav.readframes(declared + 1)
    if len(raw) != declared * 2:
        raise ValueError(f"payload expected {declared * 2} bytes, measured {len(raw)}")

    signal = np.frombuffer(raw, dtype="<i2").astype(float)
    peak = np.max(np.abs(signal), initial=0)
    active = np.flatnonzero(np.abs(signal) > 0.1 * peak)
    # Bridge carrier zero crossings, while retaining genuine inter-mark silence.
    runs = np.split(active, np.flatnonzero(np.diff(active) > rate * 0.003) + 1)
    runs = [run for run in runs if len(run)]
    if len(runs) != (len(expected_ms) + 1) // 2:
        raise ValueError(f"tone count expected {(len(expected_ms) + 1) // 2}, measured {len(runs)}")
    spans = [(int(run[0]), int(run[-1]) + 1) for run in runs]
    for name, samples in (("leading", spans[0][0]), ("trailing", len(signal) - spans[-1][1])):
        ms = samples * 1000 / rate
        if ms > 6:
            raise ValueError(f"{name} silence expected 0 ms, measured {ms:.2f} ms")

    timings, pitches = [], []
    for i, expected in enumerate(expected_ms):
        j = i // 2
        if i % 2:
            duration = spans[j + 1][0] - spans[j][1]
        else:
            start, stop = spans[j]
            duration = stop - start
            tone = signal[start:stop]
            crossings = np.flatnonzero((tone[:-1] <= 0) & (tone[1:] > 0))
            if len(crossings) < 2:
                raise ValueError(f"segment {i+1} expected {hz} Hz, measured insufficient cycles")
            times = crossings - tone[crossings] / (tone[crossings + 1] - tone[crossings])
            pitch = rate * (len(times) - 1) / (times[-1] - times[0])
            if abs(pitch - hz) > 2:
                raise ValueError(f"segment {i+1} expected {hz} Hz, measured {pitch:.2f} Hz")
            pitches.append(pitch)
        ms = duration * 1000 / rate
        if abs(ms - expected) > 6:
            raise ValueError(f"segment {i+1} expected {expected} ms, measured {ms:.2f} ms")
        timings.append(ms)
    return signal, timings, pitches


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Check a standard AE T fixture")
    parser.add_argument("wav")
    _, timing, pitch = validate(parser.parse_args().wav)
    print("Measured ms:", timing, "Hz:", pitch)
