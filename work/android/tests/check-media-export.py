#!/usr/bin/env python3
"""Compare a full-length MMA export to a synthetic or authorized MP4 input."""
import argparse
import json
import subprocess
from fractions import Fraction


def probe(path, *arguments):
    command = ["ffprobe", "-v", "error", *arguments, "-of", "json", path]
    return json.loads(subprocess.check_output(command))


def video(path):
    return probe(path, "-select_streams", "v:0", "-show_streams")["streams"][0]


def dimensions(stream):
    rotation = next((s["rotation"] for s in stream.get("side_data_list", [])
                     if "rotation" in s), 0)
    width, height = stream["width"], stream["height"]
    return (height, width) if rotation % 180 else (width, height)


def audio_hashes(path):
    packets = probe(path, "-select_streams", "a:0", "-show_packets",
                    "-show_data_hash", "sha256", "-show_entries",
                    "packet=data_hash").get("packets", [])
    return [packet["data_hash"] for packet in packets]


def check(source, export):
    before = video(source)
    after = video(export)
    assert dimensions(before) == dimensions(after), "Output dimensions changed"
    rate = Fraction(before["avg_frame_rate"])
    assert abs(rate - Fraction(after["avg_frame_rate"])) < Fraction(1, 20), "Frame rate changed"
    duration_delta = abs(float(before["duration"]) - float(after["duration"]))
    assert duration_delta <= float(1 / rate) + 0.002, f"Video duration changed by {duration_delta}s"
    audio = audio_hashes(source)
    assert audio, "The input fixture must have an audio track"
    assert audio == audio_hashes(export), "Compressed audio packet payloads differ"
    print(f"Verified dimensions, frame rate, duration and {len(audio)} unchanged audio packets")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("export")
    args = parser.parse_args()
    check(args.source, args.export)
