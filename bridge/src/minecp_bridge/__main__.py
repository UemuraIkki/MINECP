"""Entry point: ``python -m minecp_bridge``."""

from __future__ import annotations

import asyncio
import logging
import signal

import httpx

from .agent_loop import AgentLoop
from .config import load_config
from .logging_setup import get_session_logger
from .state import BridgeState


def _setup_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


async def _main() -> None:
    _setup_logging()
    config = load_config()
    state = BridgeState.load_or_create(config.state_file)
    session_logger = get_session_logger(config.logs_dir)

    async with httpx.AsyncClient() as http_client:
        loop = AgentLoop(config, state, http_client, session_logger)
        await loop.start()

        stop_event = asyncio.Event()

        def _request_stop(*_args) -> None:
            stop_event.set()

        try:
            asyncio.get_running_loop().add_signal_handler(signal.SIGINT, _request_stop)
            asyncio.get_running_loop().add_signal_handler(signal.SIGTERM, _request_stop)
        except NotImplementedError:
            # add_signal_handler is not available on Windows' default event loop;
            # Ctrl+C will raise KeyboardInterrupt instead, handled below.
            pass

        try:
            await stop_event.wait()
        except KeyboardInterrupt:
            pass
        finally:
            await loop.stop()
            state.save(config.state_file)


def run() -> None:
    asyncio.run(_main())


if __name__ == "__main__":
    run()
