#!/usr/bin/env python3
"""Glass-peer entry point."""

import asyncio
import json
import logging
import signal
import sys
from pathlib import Path

from agent import select_backend
from config import Config
from mint import mint_invite, write_qr_svg
from protocol import ProtocolHandler
from server import GlassServer
from session import SessionServer
from session_log import SessionLog
from state import StateStore

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


def prepare_pairing_state(
    state: StateStore, ttl_seconds: int, log: SessionLog | None = None
) -> bool:
    """
    Split on paired (phone_peer set), not Invite.is_expired.

    Paired start keeps sessionId, reply_seq, and the session log.
    Unpaired start rotates or remints and wipes the log.
    Returns True if a new invite was minted.
    """
    if state.get_phone_peer():
        if not state.get_session_id():
            state.ensure_session_id()
        logger.info(
            f"session keep id={state.get_session_id()} seq={state.get_reply_seq()}"
        )
        return False
    invite = state.get_invite()
    if invite is not None and not invite.is_expired:
        state.rotate_session()
        if log is not None:
            log.clear()
        return False
    state.set_invite(mint_invite(ttl_seconds))
    if log is not None:
        log.clear()
    return True


def log_startup_identity(state: StateStore, config: Config) -> None:
    phone = state.get_phone_peer()
    if phone:
        invite = state.get_invite()
        expired = True if invite is None else invite.is_expired
        logger.info(
            f"paired {phone[:12]} invite expired={expired} "
            f"(remint via curl 127.0.0.1/pair to change phones)"
        )
        return

    invite = state.get_invite()
    if not invite:
        return
    qr = invite.to_qr_json(wss=config.public_wss_url)
    logger.info(json.dumps(qr))
    write_qr_svg(
        invite,
        Path(config.data_dir) / "qr.svg",
        wss=config.public_wss_url,
    )


async def main() -> int:
    """Run glass-peer server."""
    try:
        config = Config.from_env()
    except ValueError as e:
        logger.error(f"Configuration error: {e}")
        return 1

    logger.info("Starting glass-peer...")
    logger.info(f"  Data dir: {config.data_dir}")
    logger.info(f"  HTTP: {config.http_bind}:{config.port}")
    logger.info(
        f"  Session: {config.session_bind}:{config.session_port}{config.session_path}"
    )
    if config.public_wss_url:
        logger.info(f"public_wss hint={config.public_wss_url}")
    logger.info(f"  Invite TTL: {config.invite_ttl_seconds}s")
    logger.info(f"  Agent: {config.backend_kind()}")

    try:
        backend = select_backend(
            kind=config.backend_kind(),
            url=config.agent_url,
            timeout_sec=config.agent_timeout_sec,
            acp_cmd=config.agent_acp_cmd,
            acp_cwd=config.agent_acp_cwd,
            acp_name=config.agent_acp_name,
            acp_yolo=config.agent_acp_yolo,
            grokbot_gateway_path=config.grokbot_gateway_path,
            grokbot_persistence_dir=config.grokbot_persistence_dir,
            grokbot_poll_sec=config.grokbot_poll_sec,
            grokbot_tail_limit=config.grokbot_tail_limit,
            grokbot_tail_pages=config.grokbot_tail_pages,
        )
    except ValueError as e:
        logger.error(f"Configuration error: {e}")
        return 1

    state_store = StateStore(config.data_dir)
    session_log = SessionLog(
        state_store,
        max_replies=config.session_log_max,
        max_bytes=config.session_log_max_bytes,
    )
    state_store.bind_session_log(session_log.clear)
    prepare_pairing_state(state_store, config.invite_ttl_seconds, session_log)
    if sid := state_store.get_session_id():
        session_log.bind(sid)
    log_startup_identity(state_store, config)

    protocol = ProtocolHandler(state_store, backend, log=session_log)
    session = SessionServer(config, state_store, protocol)
    protocol.bind_session(session)
    server = GlassServer(config, state_store, session, backend_name=backend.name)

    shutdown_event = asyncio.Event()

    def shutdown_handler():
        logger.info("Shutdown signal received")
        shutdown_event.set()

    loop = asyncio.get_event_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, shutdown_handler)

    try:
        await session.start()
        await server.start()
        logger.info("glass-peer ready")
        await shutdown_event.wait()
    finally:
        await server.stop()
        await session.stop()

    logger.info("glass-peer stopped")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
