#!/usr/bin/env python3
"""Serves the demo podcast feeds (<outdir>/feeds) over HTTP, with byte ranges so that players can seek.

Usage: serve.py [outdir] [port]   (defaults: build/demo-library, 8765)

The feeds point to http://127.0.0.1:8765/…; make the port reachable on the emulator with
`adb -s <serial> reverse tcp:8765 tcp:8765` (127.0.0.1 needs no local-network permission in the app).
"""
import os
import re
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
RANGE = re.compile(r"bytes=(\d*)-(\d*)$")


class RangeHandler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".xml": "application/rss+xml",
                      ".opml": "text/x-opml", ".mp3": "audio/mpeg"}

    def send_head(self):
        match = RANGE.match(self.headers.get("Range", ""))
        path = self.translate_path(self.path)
        if not match or not os.path.isfile(path):
            return super().send_head()
        size = os.path.getsize(path)
        first, last = match.groups()
        if first:
            start, end = int(first), min(int(last), size - 1) if last else size - 1
        else:
            start, end = max(0, size - int(last or 0)), size - 1
        if start >= size or start > end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.end_headers()
            return None
        f = open(path, "rb")
        f.seek(start)
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        remaining = getattr(self, "_remaining", None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        while remaining > 0:
            chunk = source.read(min(64 * 1024, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)

    def end_headers(self):
        if not self.headers.get("Range"):
            self.send_header("Accept-Ranges", "bytes")
        super().end_headers()


if __name__ == "__main__":
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else f"{REPO}/build/demo-library")
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
    server = ThreadingHTTPServer(("127.0.0.1", port), partial(RangeHandler, directory=f"{out}/feeds"))
    print(f"Serving {out}/feeds on http://127.0.0.1:{port}/", flush=True)
    server.serve_forever()
