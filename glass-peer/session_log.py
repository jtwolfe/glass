"""Bounded this-session reply log (persist-then-send; catch-up source)."""

from __future__ import annotations

import json
import logging
import os
import threading
import uuid
from pathlib import Path

from state import StateStore

logger = logging.getLogger(__name__)

LOG_NAME = "session-log.json"
DEFAULT_MAX_REPLIES = 200
DEFAULT_MAX_BYTES = 262144
_SEP = (",", ":")
_ELLIPSIS = "…"
_STORED_KEYS = ("id", "from", "agentId", "text", "at", "inReplyTo")


def _dumps(obj: object) -> str:
    return json.dumps(obj, separators=_SEP)


def _doc_bytes(session_id: str, replies: list[dict]) -> int:
    return len(_dumps({"sessionId": session_id, "replies": replies}))


class SessionLog:
    """Disk log of assistant replies for this plugin sessionId."""

    def __init__(
        self,
        state: StateStore,
        *,
        max_replies: int = DEFAULT_MAX_REPLIES,
        max_bytes: int = DEFAULT_MAX_BYTES,
    ):
        self._state = state
        self._max_replies = max(1, int(max_replies))
        self._max_bytes = max(1, int(max_bytes))
        self._path = Path(state.data_dir) / LOG_NAME
        self._lock = threading.Lock()
        self._session_id: str | None = None
        self._replies: list[dict] = []
        self._load()
        sid = state.get_session_id()
        if sid:
            self.bind(sid)

    @property
    def path(self) -> Path:
        return self._path

    def bind(self, session_id: str) -> None:
        """Discard on session mismatch; reply_seq = max(stored, max_seq+1)."""
        with self._lock:
            self._bind_locked(session_id)

    def allocate_and_append(self, payload: dict) -> dict | None:
        """One lock: assign seq, evict/truncate, write log + reply_seq."""
        with self._lock:
            sid = self._state.get_session_id() or self._state.ensure_session_id()
            if self._session_id != sid:
                self._bind_locked(sid)
            seq = self._state.get_reply_seq()
            row = _stored_row(payload, seq)
            candidate = list(self._replies)
            candidate.append(row)
            evicted = 0
            while len(candidate) > self._max_replies or (
                _doc_bytes(sid, candidate) > self._max_bytes and len(candidate) > 1
            ):
                candidate.pop(0)
                evicted += 1
            nbytes = _doc_bytes(sid, candidate)
            if nbytes > self._max_bytes:
                fitted = _truncate_row(candidate[0], sid, self._max_bytes)
                if fitted is None:
                    logger.info("session_log reject_oversize")
                    return None
                candidate[0] = fitted
                row = fitted
                nbytes = _doc_bytes(sid, candidate)
            self._persist(sid, candidate)
            self._replies = candidate
            self._session_id = sid
            self._state.set_reply_seq(seq + 1)
            logger.info(
                f"session_log n={len(self._replies)} bytes={nbytes} evicted={evicted}"
            )
            return dict(row)

    def after(self, seq: int) -> list[dict]:
        with self._lock:
            return [dict(row) for row in self._replies if int(row["seq"]) > seq]

    def clear(self) -> None:
        with self._lock:
            self._replies = []
            self._session_id = self._state.get_session_id()
            self._unlink()

    def _bind_locked(self, session_id: str) -> None:
        if self._session_id != session_id:
            self._replies = []
            self._session_id = session_id
            self._unlink()
        max_seq = max((int(row["seq"]) for row in self._replies), default=-1)
        stored = self._state.get_reply_seq()
        repaired = max(stored, max_seq + 1)
        if repaired != stored:
            self._state.set_reply_seq(repaired)

    def _load(self) -> None:
        if not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError, UnicodeError):
            self._unlink()
            return
        if not isinstance(data, dict):
            self._unlink()
            return
        sid = data.get("sessionId")
        raw = data.get("replies")
        if not isinstance(sid, str) or not isinstance(raw, list):
            self._unlink()
            return
        replies: list[dict] = []
        for item in raw:
            if not isinstance(item, dict):
                continue
            seq = item.get("seq")
            if isinstance(seq, bool) or not isinstance(seq, int):
                continue
            replies.append(item)
        self._session_id = sid
        self._replies = replies

    def _persist(self, session_id: str, replies: list[dict]) -> None:
        blob = _dumps({"sessionId": session_id, "replies": replies})
        tmp = self._path.with_name(self._path.name + ".tmp")
        tmp.write_text(blob, encoding="utf-8")
        os.replace(tmp, self._path)

    def _unlink(self) -> None:
        try:
            self._path.unlink()
        except FileNotFoundError:
            pass
        tmp = self._path.with_name(self._path.name + ".tmp")
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass


def _stored_row(payload: dict, seq: int) -> dict:
    row = {"seq": seq}
    for key in _STORED_KEYS:
        value = payload.get(key)
        row[key] = value if value is not None else ""
    if not row["id"]:
        row["id"] = str(uuid.uuid4())
    return row


def _truncate_row(row: dict, session_id: str, max_bytes: int) -> dict | None:
    text = row.get("text") or ""
    if not isinstance(text, str):
        text = str(text)
    lo, hi = 0, len(text)
    best: dict | None = None
    while lo <= hi:
        mid = (lo + hi) // 2
        trial = {**row, "text": text[:mid] + _ELLIPSIS}
        if _doc_bytes(session_id, [trial]) <= max_bytes:
            best = trial
            lo = mid + 1
        else:
            hi = mid - 1
    if best is not None:
        return best
    minimum = {**row, "text": _ELLIPSIS}
    if _doc_bytes(session_id, [minimum]) <= max_bytes:
        return minimum
    return None
