"""In-process asyncio programmable fault-injection TCP proxy.

Works identically on Linux, macOS, and Windows with zero Docker, root, or `tc` requirements.
Supports dynamic mid-transfer latency, jitter, bandwidth throttling, connection drops,
and reset-after-N-bytes faults.
"""
from __future__ import annotations

import asyncio
import random
import threading
import time
from dataclasses import dataclass


@dataclass
class FaultProfile:
    delay_ms: float = 0.0
    jitter_ms: float = 0.0
    rate_bytes_per_sec: int = 0
    drop_new_connections: bool = False
    drop_after_bytes: int = 0  # closes server->client stream after N bytes transferred
    drop_once: bool = False  # if True, resets drop_after_bytes to 0 after triggering once


class FaultProxy:
    """Bidirectional TCP proxy forwarding 127.0.0.1:<local_port> -> <target_host>:<target_port>."""

    def __init__(self, target_host: str, target_port: int, bind_host: str = "127.0.0.1", bind_port: int = 0):
        self.target_host = target_host
        self.target_port = target_port
        self.bind_host = bind_host
        self._requested_port = bind_port
        self.port: int = 0
        self.profile = FaultProfile()
        self.bytes_to_client = 0
        self.bytes_to_server = 0
        self.connections_total = 0
        self.drops_triggered = 0
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._server: asyncio.AbstractServer | None = None
        self._ready = threading.Event()
        self._error: Exception | None = None

    def start(self) -> int:
        self._thread = threading.Thread(target=self._run_loop, name="amp-fault-proxy", daemon=True)
        self._thread.start()
        self._ready.wait(timeout=10.0)
        if self._error is not None:
            raise self._error
        return self.port

    def stop(self) -> None:
        if self._loop is not None:
            self._loop.call_soon_threadsafe(self._loop.stop)
        if self._thread is not None:
            self._thread.join(timeout=5.0)
        self._loop = None
        self._thread = None

    def set_fault(self, **kwargs) -> None:
        for key, value in kwargs.items():
            if not hasattr(self.profile, key):
                raise AttributeError(f"Unknown FaultProfile field: {key}")
            setattr(self.profile, key, value)

    def clear_faults(self) -> None:
        self.profile = FaultProfile()

    def _run_loop(self) -> None:
        loop = asyncio.new_event_loop()
        self._loop = loop
        asyncio.set_event_loop(loop)
        try:
            self._server = loop.run_until_complete(asyncio.start_server(self._handle_client, self.bind_host, self._requested_port))
            sock = self._server.sockets[0]
            self.port = int(sock.getsockname()[1])
        except Exception as e:
            self._error = e
            self._ready.set()
            return
        self._ready.set()
        try:
            loop.run_forever()
        finally:
            if self._server is not None:
                self._server.close()
                loop.run_until_complete(self._server.wait_closed())
            pending = asyncio.all_tasks(loop)
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()

    async def _handle_client(self, client_r: asyncio.StreamReader, client_w: asyncio.StreamWriter) -> None:
        self.connections_total += 1
        if self.profile.drop_new_connections:
            self.drops_triggered += 1
            client_w.close()
            await client_w.wait_closed()
            return
        try:
            upstream_r, upstream_w = await asyncio.open_connection(self.target_host, self.target_port)
        except OSError:
            client_w.close()
            await client_w.wait_closed()
            return

        t1 = asyncio.create_task(self._pipe(client_r, upstream_w, direction="up"))
        t2 = asyncio.create_task(self._pipe(upstream_r, client_w, direction="down"))
        done, pending = await asyncio.wait({t1, t2}, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        await asyncio.gather(*done, *pending, return_exceptions=True)
        for writer in (client_w, upstream_w):
            if not writer.is_closing():
                writer.close()
                try:
                    await writer.wait_closed()
                except OSError:
                    pass

    async def _pipe(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, *, direction: str) -> None:
        conn_bytes = 0
        chunk_size = 16384
        while True:
            data = await reader.read(chunk_size)
            if not data:
                return
            p = self.profile
            if direction == "down":
                self.bytes_to_client += len(data)
                conn_bytes += len(data)
                if p.drop_after_bytes > 0 and conn_bytes >= p.drop_after_bytes:
                    self.drops_triggered += 1
                    if p.drop_once:
                        p.drop_after_bytes = 0
                    return
                if p.delay_ms > 0 or p.jitter_ms > 0:
                    delay = max(0.0, p.delay_ms + random.uniform(-p.jitter_ms, p.jitter_ms)) / 1000.0
                    if delay > 0:
                        await asyncio.sleep(delay)
                if p.rate_bytes_per_sec > 0:
                    await asyncio.sleep(len(data) / float(p.rate_bytes_per_sec))
            else:
                self.bytes_to_server += len(data)
            writer.write(data)
            await writer.drain()
