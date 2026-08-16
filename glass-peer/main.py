#!/usr/bin/env python3
"""Glass-peer entry point."""

import asyncio
import logging
import signal
import sys

from config import Config
from state import StateStore
from server import GlassServer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


async def main() -> int:
    """Run glass-peer server."""
    try:
        config = Config.from_env()
    except ValueError as e:
        logger.error(f"Configuration error: {e}")
        return 1

    logger.info("Starting glass-peer...")
    logger.info(f"  Data dir: {config.data_dir}")
    logger.info(f"  Port: {config.port}")
    logger.info(f"  ntfy internal: {config.ntfy_internal_url}")
    logger.info(f"  ntfy public: {config.ntfy_public_url}")
    logger.info(f"  STUN: {config.stun_server}")
    logger.info(f"  Invite TTL: {config.invite_ttl_seconds}s")

    state_store = StateStore(config.data_dir)
    server = GlassServer(config, state_store)

    # Handle shutdown signals
    shutdown_event = asyncio.Event()

    def shutdown_handler():
        logger.info("Shutdown signal received")
        shutdown_event.set()

    loop = asyncio.get_event_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, shutdown_handler)

    try:
        await server.start()
        logger.info("glass-peer ready")
        await shutdown_event.wait()
    finally:
        await server.stop()

    logger.info("glass-peer stopped")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
