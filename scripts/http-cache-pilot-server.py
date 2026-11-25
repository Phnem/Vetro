#!/usr/bin/env python3
"""Server for the playback disk cache pilot: byte ranges, a slow link, a byte counter.

    python scripts/http-cache-pilot-server.py <folder with clips> [port]
    adb reverse tcp:8765 tcp:8765

GET /stats returns the media bytes sent since the last GET /reset.
"""
import http.server
import json
import os
import sys
import threading
import time

ROOT = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
RATE = 1_500_000  # bytes per second, about a 12 Mbit/s link
LATENCY = 0.06    # seconds before every response
CHUNK = 32 * 1024

sent = {"bytes": 0, "requests": 0}
lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply_json(self, data):
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/stats":
            with lock:
                return self.reply_json(dict(sent))
        if self.path == "/reset":
            with lock:
                sent["bytes"] = sent["requests"] = 0
            return self.reply_json({})
        path = os.path.join(ROOT, os.path.basename(self.path.split("?")[0]))
        if not os.path.isfile(path):
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return None
        size = os.path.getsize(path)
        start, end = 0, size - 1
        header = self.headers.get("Range")
        if header and header.startswith("bytes="):
            first, last = header[6:].split("-", 1)
            start = int(first or 0)
            end = min(int(last), size - 1) if last else size - 1
        time.sleep(LATENCY)
        self.send_response(206 if header else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if header:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with lock:
            sent["requests"] += 1
        left = end - start + 1
        try:
            with open(path, "rb") as source:
                source.seek(start)
                while left > 0:
                    block = source.read(min(CHUNK, left))
                    self.wfile.write(block)
                    left -= len(block)
                    with lock:
                        sent["bytes"] += len(block)
                    time.sleep(len(block) / RATE)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # the player closes a connection it no longer needs
        return None


http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
