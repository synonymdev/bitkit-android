#!/usr/bin/env python3
"""Electrum bridge for testing dropped and lost connections.

Bitkit connects to tcp://127.0.0.1:<port>; every connection is forwarded over TLS to the
upstream Electrum server (staging regtest by default). Touch a file in the control directory
to break the next request:

  drop       the next request is swallowed; the bridge sends stray replies until a second
             request queues on the same connection, then ends the stream and resets the
             connection. This is how a server dropping an idle client looks to Bitkit while
             a call is waiting. The file is removed once used.
  blackhole  every request is swallowed and never answered, so a broadcast's outcome is lost.
             Remove the file to bring the connection back.

Usage:
  python3 scripts/electrum-bridge.py [--port 50099] [--upstream electrs.bitkit.stag0.blocktank.to:9999]
                                     [--control /tmp/electrum-bridge] [--max-seconds 10800]
"""
import argparse
import asyncio
import os
import ssl
import time

STRAY_REPLY = b'{"jsonrpc":"2.0","id":999999,"result":null}\n'


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=50099)
    parser.add_argument("--upstream", default="electrs.bitkit.stag0.blocktank.to:9999")
    parser.add_argument("--control", default="/tmp/electrum-bridge")
    parser.add_argument("--max-seconds", type=int, default=3 * 3600)
    args = parser.parse_args()
    host, port = args.upstream.rsplit(":", 1)
    os.makedirs(args.control, exist_ok=True)

    def flag(name):
        return os.path.exists(os.path.join(args.control, name))

    async def handle(client_reader, client_writer):
        peer = client_writer.get_extra_info("peername")
        context = ssl.create_default_context()
        upstream_reader, upstream_writer = await asyncio.open_connection(
            host, int(port), ssl=context, server_hostname=host
        )
        log("open", peer)

        async def up():
            while line := await client_reader.readline():
                if flag("drop"):
                    os.remove(os.path.join(args.control, "drop"))
                    log("drop: swallowing", line[:80], "and waiting for a queued call")
                    started, deadline, queued = time.time(), time.time() + 60, None
                    while time.time() < deadline and queued is None:
                        client_writer.write(STRAY_REPLY)
                        try:
                            received = await asyncio.wait_for(client_reader.readline(), 0.01)
                        except asyncio.TimeoutError:
                            continue
                        # Lines right after the first belong to the same batch, not another caller.
                        if time.time() - started > 0.1:
                            queued = received
                    log("drop: queued", (queued or b"none")[:80], "-> closing")
                    await asyncio.sleep(0.05)
                    if client_writer.can_write_eof():
                        client_writer.write_eof()
                    await asyncio.sleep(0.2)
                    client_writer.transport.abort()
                    upstream_writer.close()
                    return
                if flag("blackhole"):
                    log("blackhole:", line[:80])
                    continue
                upstream_writer.write(line)
                await upstream_writer.drain()
            upstream_writer.close()

        async def down():
            while line := await upstream_reader.readline():
                if client_writer.is_closing():
                    return
                client_writer.write(line)
                await client_writer.drain()
            client_writer.close()

        try:
            await asyncio.gather(up(), down())
        except Exception as error:  # resets are expected once a connection is dropped
            log("closed", peer, type(error).__name__)
        finally:
            client_writer.close()

    async def serve():
        server = await asyncio.start_server(handle, "127.0.0.1", args.port)
        log("listening on", args.port, "->", args.upstream, "control:", args.control)
        async with server:
            await asyncio.sleep(args.max_seconds)

    asyncio.run(serve())


if __name__ == "__main__":
    main()
