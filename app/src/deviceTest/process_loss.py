"""Run against a fresh debug install on an unlocked, swipe-to-unlock test AVD.

Pass the adb executable and emulator serial as positional arguments. The driver
kills only MorseBook's process and returns the device to its unlocked state.
"""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.github.danieladams456.morsebook"
BOOK = "The Call of the Wild"
TWO = "Chapter II. The Law of Club and Fang"
FIVE = "Chapter V. The Toil of Trace and Trail"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("adb")
parser.add_argument("serial")
args = parser.parse_args()


def shell(*command):
    return subprocess.check_output([args.adb, "-s", args.serial, "shell", *command],
                                  text=True, stderr=subprocess.PIPE, timeout=15).strip()


def wait_for(probe, description, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = probe()
        if result:
            return result
        time.sleep(0.3)
    raise AssertionError(description)


def tree():
    shell("uiautomator", "dump", "/sdcard/morsebook-window.xml")
    return ET.fromstring(shell("cat", "/sdcard/morsebook-window.xml"))


def node(label):
    return wait_for(lambda: any(n.get("text") == label for n in tree().iter("node")), f"missing {label}")


def tap_element(element):
    left, top, right, bottom = map(int, re.findall(r"\d+", element.attrib["bounds"]))
    shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def tap(label):
    matches = wait_for(lambda: [n for n in tree().iter("node") if n.get("text") == label], label)
    tap_element(matches[0])


def pid():
    return shell("pidof", PACKAGE, "||", "true")


def commits(process):
    log = shell("logcat", "-d", "-v", "monotonic", "--pid", process, "-s", "MorseCheckpoint:D")
    return [(float(t), k, int(s), int(h), int(o)) for t, k, s, h, o in re.findall(
        r"^\s*(\d+\.\d+).*? (start|periodic) session=(\d+) head=(\d+) offset=(\d+)", log, re.M)]


def wake():
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")


def activity_saved():
    records = shell("dumpsys", "activity", "activities").split("* Hist ")
    return any(PACKAGE + "/.app.MainActivity " in record.splitlines()[0]
               and "mHaveState=true" in record and "state=STOPPED" in record
               for record in records[1:])


def kill(process):
    shell("run-as", PACKAGE, "kill", "-9", process)
    wait_for(lambda: pid() != process, "previous app process survived")


def resume_task(old_pid):
    shell("input", "keyevent", "KEYCODE_APP_SWITCH")
    cards = wait_for(lambda: [n for n in tree().iter("node")
                              if n.get("resource-id", "").endswith(":id/snapshot")
                              and n.get("content-desc") == "MorseBook"],
                     "missing MorseBook task in Recents")
    tap_element(cards[0])
    node(TWO)
    node("Play")
    new_pid = pid()
    assert new_pid and new_pid != old_pid, (old_pid, new_pid)
    print(f"Recents restored II paused, PID {old_pid} -> {new_pid}", flush=True)
    return new_pid


def marked(label, partial=False):
    for row in tree().iter("node"):
        if row.get("clickable") != "true" or not any(c.get("text") == label for c in row.iter()):
            continue
        assert any(c.get("content-desc") == "Last played" for c in row.iter()), label
        if partial:
            assert any(c.get("class") == "android.widget.ProgressBar" for c in row.iter()), label
        return
    raise AssertionError(f"missing row for {label}")


try:
    shell("am", "start", "-W", "-n", PACKAGE + "/com.github.danieladams456.morsebook.app.MainActivity")
    node("Library")
    tap(BOOK)
    tap(TWO)
    tap("Play")
    original = pid()
    wait_for(lambda: commits(original), "no first played frame")
    shell("input", "keyevent", "KEYCODE_HOME")
    shell("input", "keyevent", "KEYCODE_SLEEP")
    wait_for(lambda: "mWakefulness=Asleep" in shell("dumpsys", "power"), "screen did not lock")
    events = wait_for(lambda: (events if len(events := commits(original)) >= 3 else None),
                      "two locked checkpoints did not arrive", 35)
    assert "mWakefulness=Asleep" in shell("dumpsys", "power")
    start, first, second = events[:3]
    assert [e[1] for e in events[:3]] == ["start", "periodic", "periodic"]
    for earlier, later in [(start, first), (first, second)]:
        frames = later[3] - earlier[3]
        assert earlier[2] == later[2] and 480000 <= frames < 528000, events
        assert earlier[4] < later[4], events
        print(f"commit offset={later[4]} head={later[3]}, played={frames / 48000:.3f}s, "
              f"elapsed={later[0] - earlier[0]:.3f}s", flush=True)
    kill(original)
    saved = commits(original)[-1][4]
    wake()
    new = resume_task(original)
    assert not commits(new), "audio started before Play"
    tap("Back")
    marked(TWO, partial=True)
    tap(TWO)
    tap("Play")
    resumed = wait_for(lambda: commits(new), "no resumed audio")
    assert resumed[0][1] == "start" and resumed[0][4] == saved, (saved, resumed)
    print(f"First audio after Play resumed at committed offset {saved}", flush=True)
    tap("Back")
    marked(TWO)
    tap(FIVE)
    node("Play")
    shell("input", "keyevent", "KEYCODE_HOME")
    wait_for(activity_saved, "Android did not save chapter V before process loss")
    kill(new)
    resume_task(new)
    tap("Back")
    marked(TWO)
    tap("Back")
    marked(BOOK)
finally:
    wake()
