"""The event-driven + periodic-review decision loop (仕様書§3.2, §4.2, §8.2, §8.3).

Ties together state, the WebSocket server, the LLM client, prompt
construction, and logging. All LLM calls run as background asyncio tasks so
that the WebSocket server keeps receiving/dispatching Mod messages while a
decision is being computed (仕様書§3.2 非同期原則) — the game side keeps
executing its current skill uninterrupted.
"""

from __future__ import annotations

import asyncio
import logging
import time

import httpx

from .config import BridgeConfig
from .llm import decide_skill
from .logging_setup import SessionLogger
from .messages import (
    AdvancementEvent,
    AttackedEvent,
    BlockPos,
    DeathEvent,
    Event,
    HpCriticalEvent,
    ItemPickupEvent,
    NamedLocation,
    Observation,
    RespawnedEvent,
    SkillResult,
    to_wire,
)
from .milestones import MilestoneContext, evaluate_all
from .prompts import (
    build_death_recovery_prompt,
    build_reflection_prompt,
    build_situation_prompt,
    build_system_prompt,
)
from .state import BridgeState, DeathRecoveryInfo
from .ws_server import BridgeServer

logger = logging.getLogger(__name__)


class AgentLoop:
    def __init__(
        self,
        config: BridgeConfig,
        state: BridgeState,
        http_client: httpx.AsyncClient,
        session_logger: SessionLogger,
    ):
        self.config = config
        self.state = state
        self.http_client = http_client
        self.session_logger = session_logger
        self.server = BridgeServer(
            config,
            on_observation=self.on_observation,
            on_skill_result=self.on_skill_result,
            on_event=self.on_event,
            on_connect=self.on_connect,
            on_disconnect=self.on_disconnect,
        )
        self._seq = 0
        self._decision_task: asyncio.Task | None = None
        self._periodic_task: asyncio.Task | None = None
        self._first_observation_seen = self.state.get_memory(NamedLocation.base) is not None
        self._milestone_context = MilestoneContext()

    # ------------------------------------------------------------------
    # lifecycle
    # ------------------------------------------------------------------

    async def start(self) -> None:
        await self.server.start()
        self._periodic_task = asyncio.create_task(self._periodic_review_loop())

    async def stop(self) -> None:
        if self._periodic_task is not None:
            self._periodic_task.cancel()
        if self._decision_task is not None:
            self._decision_task.cancel()
        await self.server.stop()

    def _next_seq(self) -> int:
        self._seq += 1
        return self._seq

    def _save_state(self) -> None:
        self.state.save(self.config.state_file)

    # ------------------------------------------------------------------
    # connection callbacks
    # ------------------------------------------------------------------

    async def on_connect(self) -> None:
        logger.info("Mod connected")

    async def on_disconnect(self) -> None:
        logger.info("Mod disconnected; waiting for reconnect")

    # ------------------------------------------------------------------
    # message handlers (registered with BridgeServer)
    # ------------------------------------------------------------------

    async def on_observation(self, observation: Observation) -> None:
        self.session_logger.log_observation(to_wire(observation))
        self.state.record_observation(observation)

        if not self._first_observation_seen:
            self._first_observation_seen = True
            if self.state.get_memory(NamedLocation.base) is None:
                # ADR-0001: the first observation's position becomes base camp.
                pos = observation.self_.pos
                self.state.register_memory(
                    NamedLocation.base,
                    BlockPos(x=round(pos.x), y=round(pos.y), z=round(pos.z)),
                )

        completed = evaluate_all(observation, self._milestone_context)
        self.state.set_completed_milestones(completed)
        self._save_state()

        if observation.reason.value in ("skill_finished", "reconnected"):
            self._trigger_decision(observation.reason.value)
        # "interrupt"-reason observations accompany an event that is handled
        # via on_event; "periodic" observations just refresh state, with the
        # periodic review timer (not the observation itself) driving
        # re-planning on that cadence.

    async def on_skill_result(self, result: SkillResult) -> None:
        self.session_logger.log_skill_result(to_wire(result))
        skill = self.state.current_skill or "unknown"
        args: dict = {}
        if self.state.action_history and self.state.action_history[-1].command_id == result.command_id:
            args = self.state.action_history[-1].args
        streak = self.state.record_skill_result(result, skill, args)
        self._save_state()

        if streak >= self.config.reflection_failure_threshold:
            self.session_logger.log_reflection(skill, self.config.reflection_failure_threshold)
            self._trigger_decision("reflection", reflection_skill=skill)
            self.state.reset_failure_streak(skill)
            self._save_state()
        # otherwise the on_observation(reason="skill_finished") that follows
        # this result will trigger the next normal decision.

    async def on_event(self, event: Event) -> None:
        self.session_logger.log_event(to_wire(event))

        if isinstance(event, DeathEvent):
            pos = event.data.pos
            death_pos = BlockPos(x=round(pos.x), y=round(pos.y), z=round(pos.z))
            self.state.register_memory(NamedLocation.last_death, death_pos)
            self.state.start_death_recovery(
                DeathRecoveryInfo(
                    died_at_ms=event.timestamp_ms,
                    pos=death_pos,
                    dimension=event.data.dimension.value,
                    cause=event.data.cause,
                )
            )
            self._save_state()
            return  # wait for the respawned event before deciding anything

        if (
            isinstance(event, RespawnedEvent)
            and self.state.pending_death_recovery is not None
            and not self.state.pending_death_recovery.resolved
        ):
            self._trigger_decision("death_recovery")
            return

        if isinstance(event, (HpCriticalEvent, AttackedEvent)):
            self._trigger_decision("interrupt", interrupt=True)
            return

        if isinstance(event, ItemPickupEvent):
            return  # informational; already reflected in the next observation's progress counts
        if isinstance(event, AdvancementEvent):
            return  # informational; already reflected in the next observation's advancements list

    # ------------------------------------------------------------------
    # decision triggering
    # ------------------------------------------------------------------

    def _trigger_decision(self, reason: str, *, interrupt: bool = False, reflection_skill: str | None = None) -> None:
        if self._decision_task is not None and not self._decision_task.done():
            if interrupt:
                self._decision_task.cancel()
            else:
                logger.debug("Decision already in flight; skipping trigger for reason=%s", reason)
                return
        self._decision_task = asyncio.create_task(self._decide_and_act(reason, reflection_skill=reflection_skill))

    async def _decide_and_act(self, reason: str, *, reflection_skill: str | None = None) -> None:
        try:
            system_prompt = build_system_prompt()

            if reason == "reflection" and reflection_skill:
                user_prompt = build_reflection_prompt(self.state, reflection_skill, self.config.reflection_failure_threshold)
            elif reason == "death_recovery" and self.state.pending_death_recovery:
                info = self.state.pending_death_recovery
                elapsed_s = (time.time() * 1000 - info.died_at_ms) / 1000.0
                remaining = self.config.item_despawn_s - elapsed_s
                user_prompt = build_death_recovery_prompt(
                    self.state,
                    (info.pos.x, info.pos.y, info.pos.z),
                    info.dimension,
                    info.cause,
                    remaining,
                )
                self.state.resolve_death_recovery()
                self._save_state()
            else:
                user_prompt = build_situation_prompt(self.state, self.state.completed_milestones)

            self.session_logger.log_prompt(reason, system_prompt, user_prompt)

            decision = await decide_skill(
                self.http_client,
                self.config,
                system_prompt,
                user_prompt,
                seq=self._next_seq(),
                exchange_logger=self.session_logger,
            )

            command = decision.command
            self.session_logger.log_skill_command(to_wire(command))
            self.state.record_command_issued(
                command.skill.value,
                command.command_id,
                command.args.model_dump(mode="json"),
                command.timestamp_ms,
            )
            self._save_state()
            await self.server.send_command(command)
        except asyncio.CancelledError:
            logger.info("Decision for reason=%s was superseded by an interrupt", reason)
            raise
        except Exception:
            logger.exception("Unexpected error in decision loop (reason=%s)", reason)

    async def _periodic_review_loop(self) -> None:
        try:
            while True:
                await asyncio.sleep(self.config.periodic_review_interval_s)
                if self.state.last_observation is not None:
                    self._trigger_decision("periodic")
        except asyncio.CancelledError:
            pass
