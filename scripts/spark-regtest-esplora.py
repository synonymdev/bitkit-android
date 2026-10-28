#!/usr/bin/env python3
"""Esplora endpoint on Spark's regtest chain for a dev build's ldk-node.

Spark's regtest is Lightspark's own chain. Its only public chain API is a mempool.space instance
(the Breez SDK's regtest default), which answers Esplora's address, transaction and block calls
but has no `/fee-estimates`, which ldk-node reads at start, nor `/block/<hash>/status`, which its
Lightning sync reads. This proxy serves `/fee-estimates` from the instance's `/v1/fees/recommended`,
builds a block's status from `/block/<hash>` and `/block-height/<height>`, and forwards everything
else, so a dev build built with `ESPLORA_URL=http://<host>:<port>` runs on Spark's chain.

Usage:
  python3 scripts/spark-regtest-esplora.py [--port 3999] [--bind 0.0.0.0] [--max-seconds 10800]
  # emulator on the same host: ESPLORA_URL=http://10.0.2.2:3999
"""
import argparse
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://regtest-mempool.us-west-2.sparkinfra.net/api"


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


def fetch(path):
    with urllib.request.urlopen(f"{UPSTREAM}{path}", timeout=15) as response:
        return response.read().decode()


def block_status(block_hash):
    height = json.loads(fetch(f"/block/{block_hash}"))["height"]
    in_best_chain = fetch(f"/block-height/{height}").strip() == block_hash
    try:
        next_best = fetch(f"/block-height/{height + 1}").strip() if in_best_chain else None
    except urllib.error.HTTPError:
        next_best = None
    return {"in_best_chain": in_best_chain, "height": height, "next_best": next_best}


def fee_estimates():
    with urllib.request.urlopen(f"{UPSTREAM}/v1/fees/recommended", timeout=15) as response:
        fees = json.load(response)
    targets = {1: "fastestFee", 2: "fastestFee", 3: "halfHourFee", 6: "hourFee", 12: "economyFee", 144: "minimumFee"}
    return {str(blocks): float(fees.get(key, 1)) for blocks, key in targets.items()}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        if self.path.rstrip("/") == "/fee-estimates":
            return self.reply(200, json.dumps(fee_estimates()).encode(), "application/json")
        parts = self.path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "block" and parts[2] == "status":
            return self.reply(200, json.dumps(block_status(parts[1])).encode(), "application/json")
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)) if self.command == "POST" else None
        request = urllib.request.Request(f"{UPSTREAM}{self.path}", data=body, method=self.command)
        if self.headers.get("Content-Type"):
            request.add_header("Content-Type", self.headers["Content-Type"])
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                self.reply(response.status, response.read(), response.headers.get("Content-Type"))
        except urllib.error.HTTPError as error:
            self.reply(error.code, error.read(), error.headers.get("Content-Type"))

    def reply(self, status, payload, content_type):
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        log(self.command, self.path, *args[1:2])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=3999)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--max-seconds", type=int, default=3 * 3600)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    threading.Timer(args.max_seconds, server.shutdown).start()
    log(f"serving Spark regtest Esplora on {args.bind}:{args.port} for {args.max_seconds}s")
    server.serve_forever()


if __name__ == "__main__":
    main()
