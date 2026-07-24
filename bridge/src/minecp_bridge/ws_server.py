"""WebSocket server: the bridge is the server, the Mod is the client
(仕様書§7.1, §7.3, schema/README ワイヤ形式).

Incoming raw JSON is schema-validated against ``schema/*.schema.json`` (the
single source of truth) before being parsed into the mirrored Pydantic
models in :mod:`minecp_bridge.messages` and dispatched to caller-supplied
async handlers. The server tolerates disconnects: on reconnect it flushes
the most recently pending outbound ``skill_command`` (only the latest one
matters, since only one skill runs at a time and a new command always
supersedes the previous one per 仕様書§4.1.3).
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Awaitable, Callable

import websockets
from websockets.exceptions import ConnectionClosed

from .config import BridgeConfig
from .messages import Event, Observation, SkillResult, parse_incoming, to_wire
from .schema_validation import SchemaValidationError, get_validator_set

logger = logging.getLogger(__name__)

ObservationHandler = Callable[[Observation], Awaitable[None]]
SkillResultHandler = Callable[[SkillResult], Awaitable[None]]
EventHandler = Callable[[Event], Awaitable[None]]
VoidHandler = Callable[[], Awaitable[None]]


class BridgeServer:
    def __init__(
        self,
        config: BridgeConfig,
        on_observation: ObservationHandler,
        on_skill_result: SkillResultHandler,
        on_event: EventHandler,
        on_connect: VoidHandler | None = None,
        on_disconnect: VoidHandler | None = None,
    ):
        self.config = config
        self.on_observation = on_observation
        self.on_skill_result = on_skill_result
        self.on_event = on_event
        self.on_connect = on_connect
        self.on_disconnect = on_disconnect
        self._validators = get_validator_set(str(config.schema_dir))
        self._client: Any | None = None
        self._pending_command: dict[str, Any] | None = None
        self._server: Any | None = None

    @property
    def is_connected(self) -> bool:
        return self._client is not None

    async def start(self):
        self._server = await websockets.serve(self._handle, self.config.ws_host, self.config.ws_port)
        logger.info("Bridge WebSocket server listening on %s", self.config.ws_uri)
        return self._server

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()

    async def _handle(self, websocket) -> None:
        logger.info("Mod connected from %s", getattr(websocket, "remote_address", "?"))
        self._client = websocket
        if self.on_connect is not None:
            await self.on_connect()
        await self._flush_pending()
        try:
            async for raw_text in websocket:
                await self.handle_raw_text(raw_text)
        except ConnectionClosed:
            logger.info("Mod disconnected")
        finally:
            if self._client is websocket:
                self._client = None
            if self.on_disconnect is not None:
                await self.on_disconnect()

    async def handle_raw_text(self, raw_text: str) -> None:
        """Validate + parse + dispatch one raw wire-format JSON text frame.

        Exposed separately from ``_handle`` so tests can feed frames without
        a live socket.
        """

        try:
            raw = json.loads(raw_text)
        except json.JSONDecodeError:
            logger.warning("Received non-JSON message, ignoring: %r", raw_text[:200])
            return

        message_type = raw.get("message_type")
        try:
            self._validators.validate_by_envelope(raw)
        except SchemaValidationError as exc:
            logger.warning("Schema validation failed, ignoring message: %s", exc)
            return

        try:
            parsed = parse_incoming(raw)
        except Exception:
            logger.exception(
                "Failed to parse message that passed schema validation (message_type=%s)",
                message_type,
            )
            return

        try:
            if message_type == "observation":
                await self.on_observation(parsed)
            elif message_type == "skill_result":
                await self.on_skill_result(parsed)
            elif message_type == "event":
                await self.on_event(parsed)
            else:
                logger.warning("Unhandled message_type from Mod: %s", message_type)
        except Exception:
            logger.exception("Handler raised while processing %s", message_type)

    async def send_command(self, command) -> bool:
        """Send a skill_command to the connected Mod.

        If no Mod is connected, the command becomes the single pending
        command and is flushed on the next reconnect.
        """

        payload = to_wire(command)
        self._pending_command = payload
        if self._client is None:
            logger.warning("No Mod connected; queuing skill_command %s", payload.get("command_id"))
            return False
        try:
            await self._client.send(json.dumps(payload))
            return True
        except ConnectionClosed:
            self._client = None
            return False

    async def _flush_pending(self) -> None:
        if self._pending_command is None or self._client is None:
            return
        try:
            await self._client.send(json.dumps(self._pending_command))
        except ConnectionClosed:
            self._client = None
