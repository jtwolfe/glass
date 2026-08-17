"""JSON-RPC ACP v1 client over child stdio (one object per line)."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import shlex
from typing import Any

logger = logging.getLogger(__name__)

PROTOCOL_VERSION = 1
CLIENT_INFO = {"name": "glass-peer", "version": "0.1.0"}
JSONRPC_METHOD_NOT_FOUND = -32601
JSONRPC_INVALID_PARAMS = -32602
_HANDSHAKE_TIMEOUT_SEC = 15.0
_KILL_GRACE_SEC = 2.0


class AcpError(Exception):
    """ACP transport or protocol failure."""

    def __init__(
        self,
        message: str,
        *,
        cancelled: bool = False,
        parse_error: bool = False,
        code: int | None = None,
    ):
        super().__init__(message)
        self.cancelled = cancelled
        self.parse_error = parse_error
        self.code = code


def permission_result(options: object, *, yolo: bool) -> dict:
    """Pick a session/request_permission outcome by option kind, not optionId."""
    cancelled = {"outcome": {"outcome": "cancelled"}}
    if not isinstance(options, list):
        return cancelled

    if yolo:
        always_id: str | None = None
        once_id: str | None = None
        for opt in options:
            if not isinstance(opt, dict):
                continue
            kind = opt.get("kind")
            option_id = opt.get("optionId")
            if not isinstance(kind, str) or not isinstance(option_id, str):
                continue
            if kind == "allow_always" and always_id is None:
                always_id = option_id
            elif kind == "allow_once" and once_id is None:
                once_id = option_id
        chosen = always_id if always_id is not None else once_id
        if chosen is None:
            return cancelled
        return {"outcome": {"outcome": "selected", "optionId": chosen}}

    for opt in options:
        if not isinstance(opt, dict):
            continue
        kind = opt.get("kind")
        option_id = opt.get("optionId")
        if not isinstance(kind, str) or not isinstance(option_id, str):
            continue
        if kind.startswith("reject_"):
            return {"outcome": {"outcome": "selected", "optionId": option_id}}
    return cancelled


def extract_agent_text(params: object) -> str | None:
    """Return agent_message_chunk text; ignore thought/tool-call updates."""
    if not isinstance(params, dict):
        return None
    update = params.get("update")
    if not isinstance(update, dict):
        return None
    if update.get("sessionUpdate") != "agent_message_chunk":
        return None
    content = update.get("content")
    if isinstance(content, dict) and content.get("type") == "text":
        text = content.get("text")
        return text if isinstance(text, str) else None
    return None


def _rpc_id(value: object) -> int | str | None:
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        return None
    return value


class AcpClient:
    """One long-lived ACP child. Handshake once; reuse session/new across prompts."""

    def __init__(
        self,
        cmd: str,
        *,
        cwd: str | None = None,
        yolo: bool = True,
        timeout_sec: float = 120.0,
    ):
        self._cmd = cmd
        self._cwd = os.path.abspath(cwd or os.getcwd())
        self._yolo = yolo
        self._timeout = timeout_sec
        self._proc: asyncio.subprocess.Process | None = None
        self._reader: asyncio.Task | None = None
        self._stderr_task: asyncio.Task | None = None
        self._pending: dict[int | str, asyncio.Future] = {}
        self._incoming: set[asyncio.Task] = set()
        self._next_id = 1
        self._write_lock = asyncio.Lock()
        self._session_id: str | None = None
        self._chunks: list[str] = []
        self._ready = False

    @property
    def session_id(self) -> str | None:
        return self._session_id

    def alive(self) -> bool:
        return self._proc is not None and self._proc.returncode is None

    async def ensure_ready(self) -> None:
        if self._proc is not None and self._proc.returncode is None:
            await asyncio.sleep(0)
        if self.alive() and self._ready:
            return
        await self._stop_child(reason="respawn")
        await self._spawn()
        try:
            await self._handshake()
        except Exception:
            await self._stop_child(reason="handshake")
            raise

    async def prompt(self, text: str) -> str:
        await self.ensure_ready()
        session_id = self._session_id
        if not session_id:
            raise AcpError("acp session missing")
        self._chunks = []
        params: dict[str, Any] = {
            "sessionId": session_id,
            "prompt": [{"type": "text", "text": text}],
        }
        try:
            try:
                result = await self._request(
                    "session/prompt", params, timeout=self._timeout
                )
            except AcpError as e:
                if not e.parse_error:
                    raise
                result = await self._request(
                    "session/prompt",
                    {
                        "sessionId": session_id,
                        "prompt": {"type": "text", "text": text},
                    },
                    timeout=self._timeout,
                )
        except AcpError as e:
            if "timed out" in str(e):
                await self.cancel_and_kill()
            raise
        if isinstance(result, dict) and result.get("stopReason") == "cancelled":
            raise AcpError("cancelled", cancelled=True)
        return "".join(self._chunks)

    async def cancel_and_kill(self) -> None:
        sid = self._session_id
        if self.alive() and sid:
            try:
                await self._notify("session/cancel", {"sessionId": sid})
            except Exception:
                pass
            pending = [f for f in self._pending.values() if not f.done()]
            if pending:
                await asyncio.wait(pending, timeout=0.3)
        await self._stop_child(reason="cancel")

    async def _spawn(self) -> None:
        argv = shlex.split(self._cmd, posix=True)
        if not argv:
            raise AcpError("empty ACP command")
        self._ready = False
        self._session_id = None
        self._next_id = 1
        try:
            proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=self._cwd,
            )
        except Exception as e:
            raise AcpError(f"acp spawn failed: {e}") from e
        self._proc = proc
        self._reader = asyncio.create_task(self._read_stdout(proc))
        self._stderr_task = asyncio.create_task(self._read_stderr(proc))

    async def _handshake(self) -> None:
        init = await self._request(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "clientInfo": dict(CLIENT_INFO),
                "clientCapabilities": {},
            },
            timeout=_HANDSHAKE_TIMEOUT_SEC,
        )
        if isinstance(init, dict):
            auth = init.get("authMethods")
            if isinstance(auth, list) and auth:
                first = auth[0]
                if isinstance(first, dict) and isinstance(first.get("id"), str):
                    await self._request(
                        "authenticate",
                        {"methodId": first["id"]},
                        timeout=_HANDSHAKE_TIMEOUT_SEC,
                    )
        created = await self._request(
            "session/new",
            {
                "cwd": self._cwd,
                "mcpServers": [],
                "_meta": {"yoloMode": bool(self._yolo)},
            },
            timeout=_HANDSHAKE_TIMEOUT_SEC,
        )
        sid = created.get("sessionId") if isinstance(created, dict) else None
        if not isinstance(sid, str) or not sid:
            raise AcpError("session/new missing sessionId")
        self._session_id = sid
        self._ready = True

    async def _request(
        self, method: str, params: dict, *, timeout: float | None
    ) -> Any:
        if not self.alive():
            raise AcpError("acp child not running")
        req_id = self._next_id
        self._next_id += 1
        fut = asyncio.get_running_loop().create_future()
        self._pending[req_id] = fut
        await self._write(
            {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
        )
        try:
            return await asyncio.wait_for(fut, timeout=timeout)
        except asyncio.TimeoutError:
            self._pending.pop(req_id, None)
            raise AcpError(f"{method} timed out") from None
        except asyncio.CancelledError:
            self._pending.pop(req_id, None)
            raise

    async def _notify(self, method: str, params: dict) -> None:
        await self._write({"jsonrpc": "2.0", "method": method, "params": params})

    async def _write(self, obj: dict) -> None:
        proc = self._proc
        if proc is None or proc.stdin is None or proc.returncode is not None:
            raise AcpError("acp child not running")
        payload = json.dumps(obj, separators=(",", ":"), ensure_ascii=False)
        async with self._write_lock:
            if proc.stdin.is_closing():
                raise AcpError("acp stdin closed")
            proc.stdin.write(payload.encode("utf-8") + b"\n")
            await proc.stdin.drain()

    async def _read_stdout(self, proc: asyncio.subprocess.Process) -> None:
        assert proc.stdout is not None
        saw_eof = False
        try:
            while True:
                line = await proc.stdout.readline()
                if not line:
                    saw_eof = True
                    break
                try:
                    self._dispatch(line)
                except Exception:
                    logger.debug("acp skip bad line")
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.debug("acp stdout reader failed")
        finally:
            if saw_eof or proc.returncode is not None:
                if proc.returncode is None:
                    try:
                        await proc.wait()
                    except Exception:
                        pass
                logger.info(f"acp child died rc={proc.returncode}")
                if self._proc is proc:
                    self._proc = None
                    self._ready = False
                self._fail_pending(f"acp child died rc={proc.returncode}")

    async def _read_stderr(self, proc: asyncio.subprocess.Process) -> None:
        if proc.stderr is None:
            return
        try:
            while True:
                line = await proc.stderr.readline()
                if not line:
                    return
                logger.debug(
                    "acp stderr %s",
                    line.decode("utf-8", errors="replace").rstrip(),
                )
        except Exception:
            return

    def _dispatch(self, raw: bytes) -> None:
        try:
            msg = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            logger.debug("acp skip non-json line")
            return
        if not isinstance(msg, dict):
            return
        method = msg.get("method")
        req_id = _rpc_id(msg.get("id")) if "id" in msg else None
        if isinstance(method, str) and "id" in msg:
            if req_id is None:
                logger.debug("acp skip request with bad id")
                return
            self._track_incoming(self._handle_incoming(msg))
            return
        if isinstance(method, str):
            self._on_notification(method, msg.get("params"))
            return
        if req_id is None:
            return
        fut = self._pending.pop(req_id, None)
        if fut is None or fut.done():
            return
        if "error" in msg:
            fut.set_exception(self._error_from_rpc(msg["error"]))
        else:
            fut.set_result(msg.get("result"))

    def _track_incoming(self, coro: Any) -> None:
        task = asyncio.create_task(coro)
        self._incoming.add(task)
        task.add_done_callback(self._incoming.discard)

    def _on_notification(self, method: str, params: object) -> None:
        if method != "session/update":
            logger.debug("acp notify %s", method)
            return
        text = extract_agent_text(params)
        if text is not None:
            self._chunks.append(text)
            return
        logger.debug("acp session/update ignored")

    async def _handle_incoming(self, msg: dict) -> None:
        method = msg.get("method")
        req_id = msg.get("id")
        try:
            if method == "session/request_permission":
                params = msg.get("params")
                options = params.get("options") if isinstance(params, dict) else None
                result = permission_result(options, yolo=self._yolo)
                await self._write({"jsonrpc": "2.0", "id": req_id, "result": result})
                return
            await self._write(
                {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": JSONRPC_METHOD_NOT_FOUND,
                        "message": "Method not found",
                    },
                }
            )
        except Exception:
            logger.debug("acp incoming reply failed method=%s", method)

    def _error_from_rpc(self, err: object) -> AcpError:
        if not isinstance(err, dict):
            return AcpError(str(err))
        code = err.get("code")
        if not isinstance(code, int):
            code = None
        message = err.get("message")
        text = message if isinstance(message, str) else str(err)
        parse = code == JSONRPC_INVALID_PARAMS
        return AcpError(text, parse_error=parse, code=code)

    def _fail_pending(self, message: str) -> None:
        pending = list(self._pending.values())
        self._pending.clear()
        for fut in pending:
            if not fut.done():
                fut.set_exception(AcpError(message))

    async def _stop_child(self, *, reason: str) -> None:
        proc = self._proc
        reader = self._reader
        stderr = self._stderr_task
        self._proc = None
        self._reader = None
        self._stderr_task = None
        self._ready = False
        self._session_id = None
        incoming = list(self._incoming)
        self._incoming.clear()
        for task in incoming:
            if not task.done():
                task.cancel()
        self._fail_pending(f"acp {reason}")
        if proc is None:
            return
        if proc.stdin is not None and not proc.stdin.is_closing():
            try:
                proc.stdin.close()
            except Exception:
                pass
        if proc.returncode is None:
            try:
                await asyncio.wait_for(proc.wait(), 0.15)
            except asyncio.TimeoutError:
                try:
                    proc.terminate()
                except ProcessLookupError:
                    pass
                try:
                    await asyncio.wait_for(proc.wait(), _KILL_GRACE_SEC)
                except asyncio.TimeoutError:
                    try:
                        proc.kill()
                    except ProcessLookupError:
                        pass
                    try:
                        await proc.wait()
                    except Exception:
                        pass
        for task in (reader, stderr):
            if task is not None and not task.done():
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    pass
