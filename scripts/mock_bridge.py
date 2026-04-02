#!/usr/bin/env python3
"""
mock_bridge.py — OAL protocol mock bridge for local app testing.

Speaks OAL protocol directly to the app (control JSON on 5288, video on 5290,
audio on 5289). Generates synthetic H.264 video via ffmpeg and PCM audio via
sine wave. No real bridge binary or phone needed.

Usage:
    python3 scripts/mock_bridge.py
    python3 scripts/mock_bridge.py --width 1920 --height 1080 --fps 30
    python3 scripts/mock_bridge.py --no-audio
    python3 scripts/mock_bridge.py --video-file test.h264

Requires: Python 3.8+, ffmpeg (for synthetic video generation)
"""

import argparse
import json
import math
import os
import signal
import socket
import struct
import subprocess
import sys
import threading
import time

# ── OAL Protocol Constants ───────────────────────────────────────────

CONTROL_PORT = 5288
VIDEO_PORT = 5290
AUDIO_PORT = 5289

# Video flags
FLAG_KEYFRAME = 0x0001
FLAG_CODEC_CONFIG = 0x0002
FLAG_EOS = 0x0004

# Audio
AUDIO_DIR_PLAYBACK = 0
AUDIO_DIR_MIC = 1
AUDIO_PURPOSE_MEDIA = 0
AUDIO_PURPOSE_NAV = 1
AUDIO_SAMPLE_RATE = 48000
AUDIO_CHANNELS = 2


# ── Video Generation ─────────────────────────────────────────────────

class VideoGenerator:
    """Generates H.264 Annex B NAL units via ffmpeg test pattern."""

    def __init__(self, width, height, fps, video_file=None):
        self.width = width
        self.height = height
        self.fps = fps
        self.video_file = video_file
        self._proc = None
        self._file = None

    def start(self):
        if self.video_file:
            self._file = open(self.video_file, "rb")
            return

        cmd = [
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi",
            "-i", (
                f"testsrc=duration=3600:size={self.width}x{self.height}:rate={self.fps},"
                f"drawtext=text='OAL Mock %{{pts\\:hms}}':fontsize=48:"
                f"fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2:"
                f"box=1:boxcolor=black@0.5:boxborderw=10"
            ),
            "-c:v", "libx264",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-profile:v", "baseline",
            "-level", "3.1",
            "-g", str(self.fps * 2),  # IDR every 2 seconds
            "-bf", "0",
            "-f", "h264",
            "-bsf:v", "h264_mp4toannexb",
            "pipe:1",
        ]
        self._proc = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )

    def stop(self):
        if self._proc:
            self._proc.terminate()
            self._proc.wait()
            self._proc = None
        if self._file:
            self._file.close()
            self._file = None

    def read_stream(self):
        """Yields raw bytes from the H.264 stream."""
        source = self._file if self._file else self._proc.stdout
        while True:
            chunk = source.read(4096)
            if not chunk:
                if self._file:
                    # Loop the file
                    self._file.seek(0)
                    continue
                break
            yield chunk


class NalSplitter:
    """Splits an Annex B H.264 byte stream into individual NAL units."""

    def __init__(self):
        self._buffer = bytearray()

    def feed(self, data):
        """Feed raw bytes, yields (nal_bytes, is_config, is_idr) tuples."""
        self._buffer.extend(data)

        while True:
            # Find start code (0x00000001 or 0x000001)
            pos3 = self._buffer.find(b"\x00\x00\x01")
            if pos3 < 0:
                break

            # Check for 4-byte start code
            start = pos3
            if start > 0 and self._buffer[start - 1] == 0:
                start -= 1

            # Find next start code
            next3 = self._buffer.find(b"\x00\x00\x01", pos3 + 3)
            next_start = next3
            if next3 < 0:
                # Not enough data yet — keep buffering
                if len(self._buffer) > 1024 * 1024:
                    # Safety: discard if too large
                    self._buffer = self._buffer[-4096:]
                break

            # Adjust for 4-byte start code on the next NAL
            if next_start > 0 and self._buffer[next_start - 1] == 0:
                next_start -= 1

            # Extract NAL with its start code (Annex B format for MediaCodec)
            nal_data = bytes(self._buffer[start:next_start])

            # Parse NAL type (first byte after start code, lower 5 bits)
            nal_body_start = 4 if self._buffer[start:start+4] == b"\x00\x00\x00\x01" else 3
            if start + nal_body_start < next_start:
                nal_type = self._buffer[start + nal_body_start] & 0x1F
            else:
                nal_type = 0

            is_config = nal_type in (7, 8)  # SPS=7, PPS=8
            is_idr = nal_type == 5

            # Yield raw NAL with start code
            yield nal_data, is_config, is_idr

            self._buffer = self._buffer[next_start:]


# ── Audio Generation ─────────────────────────────────────────────────

def generate_audio_chunk(sample_rate, channels, duration_ms, frequency, t_offset):
    """Generate a PCM sine wave chunk (16-bit signed LE)."""
    num_samples = int(sample_rate * duration_ms / 1000)
    samples = bytearray(num_samples * channels * 2)

    for i in range(num_samples):
        t = t_offset + i / sample_rate
        value = int(8000 * math.sin(2 * math.pi * frequency * t))
        value = max(-32768, min(32767, value))
        packed = struct.pack("<h", value)
        for ch in range(channels):
            offset = (i * channels + ch) * 2
            samples[offset:offset + 2] = packed

    return bytes(samples), t_offset + num_samples / sample_rate


# ── OAL Frame Builders ───────────────────────────────────────────────

def build_video_header(payload_length, width, height, pts_ms, flags):
    """Build a 16-byte OAL video frame header."""
    return struct.pack("<IHHIHH",
        payload_length, width, height, pts_ms, flags, 0x0000)


def build_audio_header(direction, purpose, sample_rate, channels, payload_length):
    """Build an 8-byte OAL audio frame header per protocol spec.

    Offset  Size  Type    Field
    0       1     u8      direction
    1       1     u8      purpose
    2       2     u16le   sample_rate
    4       1     u8      channels
    5       3     u24le   payload_length
    """
    pl_bytes = payload_length.to_bytes(3, "little")
    return struct.pack("<BBHB", direction, purpose, sample_rate, channels) + pl_bytes


# ── Control Channel ──────────────────────────────────────────────────

class ControlHandler:
    """Handles JSON-line control protocol on port 5288."""

    FAKE_TRACKS = [
        {"title": "Bohemian Rhapsody", "artist": "Queen", "album": "A Night at the Opera", "duration_ms": 354000},
        {"title": "Hotel California", "artist": "Eagles", "album": "Hotel California", "duration_ms": 391000},
        {"title": "Stairway to Heaven", "artist": "Led Zeppelin", "album": "Led Zeppelin IV", "duration_ms": 482000},
        {"title": "Imagine", "artist": "John Lennon", "album": "Imagine", "duration_ms": 187000},
        {"title": "Comfortably Numb", "artist": "Pink Floyd", "album": "The Wall", "duration_ms": 382000},
    ]

    FAKE_MANEUVERS = [
        {"maneuver": "turn_right", "distance_meters": 500, "road": "Main St", "eta_seconds": 420},
        {"maneuver": "turn_left", "distance_meters": 200, "road": "Oak Ave", "eta_seconds": 380},
        {"maneuver": "straight", "distance_meters": 1200, "road": "Highway 101", "eta_seconds": 350},
        {"maneuver": "turn_right", "distance_meters": 50, "road": "Elm Dr", "eta_seconds": 300},
        {"maneuver": "destination", "distance_meters": 0, "road": "123 Elm Dr", "eta_seconds": 0},
    ]

    def __init__(self, args):
        self.args = args
        self._phone_connected = False

    def handle(self, conn, addr, stop_event, phone_event):
        print(f"[control] App connected from {addr}")
        conn.settimeout(1.0)

        # Send hello
        hello = {
            "type": "hello",
            "version": 1,
            "name": "OpenAutoLink Mock",
            "capabilities": ["h264"],
            "video_port": VIDEO_PORT,
            "audio_port": AUDIO_PORT,
        }
        self._send(conn, hello)

        # Wait for app hello
        buf = b""
        app_hello_received = False
        while not stop_event.is_set():
            try:
                data = conn.recv(4096)
                if not data:
                    break
                buf += data
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    msg = json.loads(line)
                    if msg.get("type") == "hello":
                        print(f"[control] App hello: {msg.get('name', '?')} "
                              f"{msg.get('display_width', '?')}x{msg.get('display_height', '?')}")
                        app_hello_received = True
                    elif msg.get("type") == "touch":
                        pass  # Silently consume touch events
                    elif msg.get("type") == "keyframe_request":
                        print("[control] App requested keyframe")
                    elif msg.get("type") == "config_update":
                        print(f"[control] Config update: {msg}")
                    else:
                        print(f"[control] App: {msg}")
            except socket.timeout:
                pass

            if app_hello_received and not self._phone_connected:
                # Simulate phone connecting after 1 second
                time.sleep(1)
                self._send(conn, {
                    "type": "phone_connected",
                    "phone_name": "Mock Pixel",
                    "phone_type": "android",
                })
                self._send(conn, {
                    "type": "config_echo",
                    "video_codec": "h264",
                    "video_width": self.args.width,
                    "video_height": self.args.height,
                    "video_fps": self.args.fps,
                    "aa_resolution": "1080p",
                })
                if not self.args.no_audio:
                    self._send(conn, {
                        "type": "audio_start",
                        "purpose": "media",
                        "sample_rate": AUDIO_SAMPLE_RATE,
                        "channels": AUDIO_CHANNELS,
                    })
                self._phone_connected = True
                phone_event.set()
                print("[control] Simulated phone connection")

                # Start simulation threads for media metadata and nav state
                if not self.args.no_simulate:
                    threading.Thread(
                        target=self._simulate_media, args=(conn, stop_event),
                        daemon=True, name="media-sim"
                    ).start()
                    threading.Thread(
                        target=self._simulate_nav, args=(conn, stop_event),
                        daemon=True, name="nav-sim"
                    ).start()
                    threading.Thread(
                        target=self._simulate_stats, args=(conn, stop_event),
                        daemon=True, name="stats-sim"
                    ).start()

        print("[control] App disconnected")
        self._phone_connected = False
        phone_event.clear()

    def _send(self, conn, msg):
        line = json.dumps(msg, separators=(",", ":")) + "\n"
        try:
            conn.sendall(line.encode())
        except (BrokenPipeError, OSError):
            pass

    def _simulate_media(self, conn, stop_event):
        """Cycle through fake tracks every 15 seconds."""
        track_idx = 0
        while not stop_event.is_set():
            track = self.FAKE_TRACKS[track_idx % len(self.FAKE_TRACKS)]
            position_ms = 0
            # Send initial metadata
            self._send(conn, {
                "type": "media_metadata",
                "title": track["title"],
                "artist": track["artist"],
                "album": track["album"],
                "duration_ms": track["duration_ms"],
                "position_ms": position_ms,
                "playing": True,
            })
            print(f"[control] Now playing: {track['artist']} - {track['title']}")

            # Update position every 5 seconds until track changes
            for _ in range(3):
                if stop_event.is_set():
                    return
                time.sleep(5)
                position_ms += 5000
                self._send(conn, {
                    "type": "media_metadata",
                    "title": track["title"],
                    "artist": track["artist"],
                    "album": track["album"],
                    "duration_ms": track["duration_ms"],
                    "position_ms": position_ms,
                    "playing": True,
                })

            track_idx += 1

    def _simulate_nav(self, conn, stop_event):
        """Cycle through fake navigation maneuvers every 10 seconds."""
        time.sleep(3)  # Brief delay before nav starts
        maneuver_idx = 0
        while not stop_event.is_set():
            maneuver = self.FAKE_MANEUVERS[maneuver_idx % len(self.FAKE_MANEUVERS)]
            self._send(conn, {
                "type": "nav_state",
                **maneuver,
            })
            print(f"[control] Nav: {maneuver['maneuver']} in {maneuver['distance_meters']}m → {maneuver['road']}")
            maneuver_idx += 1
            for _ in range(10):
                if stop_event.is_set():
                    return
                time.sleep(1)

    def _simulate_stats(self, conn, stop_event):
        """Send bridge stats every 30 seconds."""
        start = time.time()
        video_frames = 0
        audio_frames = 0
        while not stop_event.is_set():
            for _ in range(30):
                if stop_event.is_set():
                    return
                time.sleep(1)
            uptime = int(time.time() - start)
            # Approximate frame counts based on elapsed time
            video_frames = uptime * self.args.fps
            audio_frames = uptime * 50  # ~50 audio chunks/sec at 20ms
            self._send(conn, {
                "type": "stats",
                "video_frames_sent": video_frames,
                "audio_frames_sent": audio_frames,
                "uptime_seconds": uptime,
            })


# ── Video Streamer ────────────────────────────────────────────────────

class VideoStreamer:
    """Streams H.264 frames on port 5290 using OAL video headers."""

    def __init__(self, args):
        self.args = args

    def stream(self, conn, addr, stop_event, phone_event):
        print(f"[video] App connected from {addr}")

        # Wait for phone connection before streaming
        while not phone_event.is_set() and not stop_event.is_set():
            time.sleep(0.1)

        if stop_event.is_set():
            return

        gen = VideoGenerator(self.args.width, self.args.height, self.args.fps,
                             self.args.video_file)
        gen.start()
        splitter = NalSplitter()

        pts_ms = 0
        frame_interval = 1.0 / self.args.fps
        frames_sent = 0
        config_sent = False
        start_time = time.monotonic()

        # Accumulate NALs into frames
        config_nals = bytearray()
        frame_nals = bytearray()

        try:
            for chunk in gen.read_stream():
                if stop_event.is_set():
                    break

                for nal_data, is_config, is_idr in splitter.feed(chunk):
                    if stop_event.is_set():
                        break

                    if is_config:
                        # Accumulate SPS/PPS
                        config_nals.extend(nal_data)
                        continue

                    # Got a non-config NAL — send pending config first
                    if config_nals and not config_sent:
                        config_bytes = bytes(config_nals)
                        hdr = build_video_header(
                            len(config_bytes), self.args.width, self.args.height,
                            0, FLAG_CODEC_CONFIG
                        )
                        conn.sendall(hdr + config_bytes)
                        config_sent = True
                        config_nals = bytearray()
                        print(f"[video] Sent codec config ({len(config_bytes)} bytes)")

                    if config_nals:
                        # New config mid-stream (IDR with fresh SPS/PPS)
                        hdr = build_video_header(
                            len(config_nals), self.args.width, self.args.height,
                            pts_ms, FLAG_CODEC_CONFIG
                        )
                        conn.sendall(hdr + config_nals)
                        config_nals = bytearray()

                    # Send the frame NAL
                    flags = FLAG_KEYFRAME if is_idr else 0
                    hdr = build_video_header(
                        len(nal_data), self.args.width, self.args.height,
                        pts_ms, flags
                    )
                    conn.sendall(hdr + nal_data)
                    frames_sent += 1

                    if is_idr and frames_sent <= 2:
                        print(f"[video] Sent IDR frame #{frames_sent} ({len(nal_data)} bytes)")

                    # Pace to target FPS
                    pts_ms += int(frame_interval * 1000)
                    elapsed = time.monotonic() - start_time
                    target = frames_sent * frame_interval
                    if target > elapsed:
                        time.sleep(target - elapsed)

                    # Stats every 5 seconds
                    if frames_sent % (self.args.fps * 5) == 0:
                        actual_fps = frames_sent / max(elapsed, 0.001)
                        print(f"[video] {frames_sent} frames, {actual_fps:.1f} fps")

        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            gen.stop()
            print(f"[video] Stopped after {frames_sent} frames")


# ── Audio Streamer ────────────────────────────────────────────────────

class AudioStreamer:
    """Streams PCM sine wave audio on port 5289."""

    def __init__(self, args):
        self.args = args

    def stream(self, conn, addr, stop_event, phone_event):
        print(f"[audio] App connected from {addr}")

        if self.args.no_audio:
            # Just keep connection alive
            while not stop_event.is_set():
                time.sleep(1)
            return

        # Wait for phone connection
        while not phone_event.is_set() and not stop_event.is_set():
            time.sleep(0.1)

        if stop_event.is_set():
            return

        chunk_ms = 20  # 20ms audio chunks (standard)
        frequency = 440.0  # A4 note
        t_offset = 0.0
        chunks_sent = 0

        try:
            while not stop_event.is_set():
                pcm, t_offset = generate_audio_chunk(
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, chunk_ms, frequency, t_offset
                )

                hdr = build_audio_header(
                    AUDIO_DIR_PLAYBACK, AUDIO_PURPOSE_MEDIA,
                    AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, len(pcm)
                )
                conn.sendall(hdr + pcm)
                chunks_sent += 1

                # Slowly change pitch for variety
                if chunks_sent % 250 == 0:
                    # Cycle through C major scale
                    notes = [261.6, 293.7, 329.6, 349.2, 392.0, 440.0, 493.9, 523.3]
                    frequency = notes[(chunks_sent // 250) % len(notes)]

                time.sleep(chunk_ms / 1000.0)

        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

        print(f"[audio] Stopped after {chunks_sent} chunks")


# ── Server Plumbing ───────────────────────────────────────────────────

def listen_accept(port, handler, stop_event, **kwargs):
    """Listen on a port, accept one connection at a time, run handler."""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.settimeout(1.0)
    srv.bind(("0.0.0.0", port))
    srv.listen(1)

    while not stop_event.is_set():
        try:
            conn, addr = srv.accept()
        except socket.timeout:
            continue

        try:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            handler(conn, addr, stop_event, **kwargs)
        except Exception as e:
            print(f"[port {port}] Error: {e}")
        finally:
            conn.close()

    srv.close()


# ── Main ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="OAL mock bridge for local app testing"
    )
    parser.add_argument("--width", type=int, default=1920, help="Video width (default: 1920)")
    parser.add_argument("--height", type=int, default=1080, help="Video height (default: 1080)")
    parser.add_argument("--fps", type=int, default=30, help="Video FPS (default: 30)")
    parser.add_argument("--no-audio", action="store_true", help="Disable audio streaming")
    parser.add_argument("--no-simulate", action="store_true",
                        help="Disable media/nav/stats simulation on control channel")
    parser.add_argument("--video-file", type=str, help="Use a raw H.264 Annex B file instead of ffmpeg")
    parser.add_argument("--bind", type=str, default="0.0.0.0", help="Bind address (default: 0.0.0.0)")
    args = parser.parse_args()

    # Check ffmpeg
    if not args.video_file:
        try:
            subprocess.run(["ffmpeg", "-version"], capture_output=True, check=True)
        except FileNotFoundError:
            print("ERROR: ffmpeg not found. Install it or use --video-file.")
            sys.exit(1)

    print("=" * 50)
    print("  OpenAutoLink Mock Bridge")
    print("=" * 50)
    print(f"  Control: {args.bind}:{CONTROL_PORT}")
    print(f"  Video:   {args.bind}:{VIDEO_PORT} ({args.width}x{args.height} @ {args.fps}fps)")
    print(f"  Audio:   {args.bind}:{AUDIO_PORT} ({'disabled' if args.no_audio else '48kHz stereo'})")
    print(f"  Source:  {'file: ' + args.video_file if args.video_file else 'ffmpeg test pattern'}")
    print(f"  Simulate: {'off' if args.no_simulate else 'media + nav + stats'}")
    print("=" * 50)
    print("  Waiting for app connection...")
    print()

    stop_event = threading.Event()
    phone_event = threading.Event()

    def signal_handler(sig, frame):
        print("\n[main] Shutting down...")
        stop_event.set()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    control = ControlHandler(args)
    video = VideoStreamer(args)
    audio = AudioStreamer(args)

    threads = [
        threading.Thread(target=listen_accept, args=(CONTROL_PORT, control.handle, stop_event),
                         kwargs={"phone_event": phone_event}, daemon=True, name="control"),
        threading.Thread(target=listen_accept, args=(VIDEO_PORT, video.stream, stop_event),
                         kwargs={"phone_event": phone_event}, daemon=True, name="video"),
        threading.Thread(target=listen_accept, args=(AUDIO_PORT, audio.stream, stop_event),
                         kwargs={"phone_event": phone_event}, daemon=True, name="audio"),
    ]

    for t in threads:
        t.start()

    # Wait until stopped
    try:
        while not stop_event.is_set():
            time.sleep(0.5)
    except KeyboardInterrupt:
        stop_event.set()

    print("[main] Stopped")


if __name__ == "__main__":
    main()
