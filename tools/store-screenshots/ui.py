#!/usr/bin/env python3
"""Small UI Automator helpers for driving the emulators.

CLI:
  ui.py dump <serial>                 print every node with text/desc and bounds
  ui.py tap <serial> <text> [n]       tap the n-th (default 0) node whose text or content-desc equals <text>
  ui.py taplike <serial> <substring>  tap the first node whose text contains <substring>
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(serial, *args, binary=False):
    try:
        out = subprocess.run(["adb", "-s", serial, *args], capture_output=True, check=False, timeout=40).stdout
    except subprocess.TimeoutExpired:
        out = b""
    return out if binary else out.decode("utf-8", "replace")


def dump(serial, retries=3):
    for _ in range(retries):
        raw = adb(serial, "exec-out", "uiautomator", "dump", "/dev/stdout")
        start = raw.find("<?xml")
        end = raw.rfind("</hierarchy>")
        if start >= 0 and end >= 0:
            root = ET.fromstring(raw[start:end + len("</hierarchy>")])
            nodes = []
            for el in root.iter("node"):
                m = BOUNDS.match(el.get("bounds", ""))
                if not m:
                    continue
                x1, y1, x2, y2 = map(int, m.groups())
                nodes.append(dict(text=el.get("text", ""), desc=el.get("content-desc", ""),
                                  rid=el.get("resource-id", ""), clickable=el.get("clickable") == "true",
                                  bounds=(x1, y1, x2, y2)))
            return nodes
        time.sleep(1)
    return []


def center(node):
    x1, y1, x2, y2 = node["bounds"]
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(serial, x, y):
    adb(serial, "shell", "input", "tap", str(x), str(y))


def find(nodes, text, contains=False):
    out = []
    for n in nodes:
        for v in (n["text"], n["desc"]):
            if v and (text in v if contains else v == text):
                out.append(n)
                break
    return out


def tap_text(serial, text, index=0, contains=False):
    nodes = find(dump(serial), text, contains)
    if len(nodes) <= index:
        return False
    tap(serial, *center(nodes[index]))
    return True


def texts(serial):
    return [n["text"] for n in dump(serial) if n["text"]]


if __name__ == "__main__":
    cmd, serial = sys.argv[1], sys.argv[2]
    if cmd == "dump":
        for n in dump(serial):
            if n["text"] or n["desc"]:
                print(f'{n["text"] or "·"} | {n["desc"]} | {n["bounds"]} | click={n["clickable"]}')
    elif cmd == "tap":
        ok = tap_text(serial, sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 0)
        print("tapped" if ok else "NOT FOUND")
        sys.exit(0 if ok else 1)
    elif cmd == "taplike":
        ok = tap_text(serial, sys.argv[3], contains=True)
        print("tapped" if ok else "NOT FOUND")
        sys.exit(0 if ok else 1)
