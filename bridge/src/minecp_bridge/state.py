"""Bridge state management + atomic JSON file persistence.

Holds everything the bridge is responsible for remembering per 仕様書§4.2.1/2
and ADR-0001: the latest observation, the in-flight skill, milestone
progress, memory coordinates (base / nether portals / stronghold /
last death), a bounded action history, and per-skill consecutive-failure
counters used to trigger the reflection loop (仕様書§8.2).

State survives a process restart: :func:`BridgeState.load` reconstructs it
from ``bridge/state/state.json``; :meth:`BridgeState.save` writes it back
atomically (write to a temp file in the same directory, then
``os.replace``).
"""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from .messages import BlockPos, FailureCode, Milestone, NamedLocation, Observation, SkillResult

STATE_SCHEMA_VERSION = 1


class ActionHistoryEntry(BaseModel):
    """One entry in the bounded recent-action history fed into prompts."""

    model_config = ConfigDict(extra="forbid")

    timestamp_ms: int
    command_id: str
    skill: str
    args: dict[str, Any]
    status: str  # "success" | "failure" | "issued"
    failure_code: FailureCode | None = None
    detail: str | None = None


class DeathRecoveryInfo(BaseModel):
    """Pending death-recovery context (仕様書§8.3)."""

    model_config = ConfigDict(extra="forbid")

    died_at_ms: int
    pos: BlockPos
    dimension: str
    cause: str
    resolved: bool = False


class BridgeState(BaseModel):
    """The full persisted bridge state."""

    model_config = ConfigDict(extra="forbid")

    schema_version: int = STATE_SCHEMA_VERSION

    last_observation: Observation | None = None
    current_command_id: str | None = None
    current_skill: str | None = None

    completed_milestones: list[Milestone] = Field(default_factory=list)

    memory_coords: dict[str, BlockPos] = Field(default_factory=dict)
    """Keyed by NamedLocation value (base / nether_portal_overworld / ...)."""

    action_history: list[ActionHistoryEntry] = Field(default_factory=list)
    max_history: int = 50

    consecutive_failures: dict[str, int] = Field(default_factory=dict)
    """Keyed by skill name: current consecutive-failure streak for that skill."""

    pending_death_recovery: DeathRecoveryInfo | None = None

    # ------------------------------------------------------------------
    # Mutators
    # ------------------------------------------------------------------

    def record_observation(self, observation: Observation) -> None:
        self.last_observation = observation
        if observation.current_skill is not None:
            self.current_command_id = observation.current_skill.command_id
            self.current_skill = observation.current_skill.skill
        else:
            self.current_command_id = None
            self.current_skill = None

    def record_command_issued(self, skill: str, command_id: str, args: dict[str, Any], timestamp_ms: int) -> None:
        self.current_command_id = command_id
        self.current_skill = skill
        self._push_history(
            ActionHistoryEntry(
                timestamp_ms=timestamp_ms,
                command_id=command_id,
                skill=skill,
                args=args,
                status="issued",
            )
        )

    def record_skill_result(self, result: SkillResult, skill: str, args: dict[str, Any]) -> bool:
        """Record a skill_result. Returns True if this skill just crossed the
        reflection threshold (i.e. reached its Nth consecutive failure)."""

        self._push_history(
            ActionHistoryEntry(
                timestamp_ms=result.timestamp_ms,
                command_id=result.command_id,
                skill=skill,
                args=args,
                status=result.status.value,
                failure_code=result.failure_code,
                detail=result.detail,
            )
        )

        if result.status.value == "failure":
            self.consecutive_failures[skill] = self.consecutive_failures.get(skill, 0) + 1
        else:
            self.consecutive_failures[skill] = 0

        return self.consecutive_failures.get(skill, 0)

    def reset_failure_streak(self, skill: str) -> None:
        self.consecutive_failures[skill] = 0

    def register_memory(self, location: NamedLocation | str, pos: BlockPos) -> None:
        key = NamedLocation(location).value
        self.memory_coords[key] = pos

    def get_memory(self, location: NamedLocation | str) -> BlockPos | None:
        return self.memory_coords.get(NamedLocation(location).value)

    def mark_milestone_completed(self, milestone: Milestone) -> None:
        if milestone not in self.completed_milestones:
            self.completed_milestones.append(milestone)

    def set_completed_milestones(self, milestones: set[Milestone] | list[Milestone]) -> None:
        # Preserve DAG order for readability in prompts/logs.
        ordered = [m for m in Milestone if m in set(milestones)]
        self.completed_milestones = ordered

    def start_death_recovery(self, info: DeathRecoveryInfo) -> None:
        self.pending_death_recovery = info

    def resolve_death_recovery(self) -> None:
        if self.pending_death_recovery is not None:
            self.pending_death_recovery.resolved = True

    def _push_history(self, entry: ActionHistoryEntry) -> None:
        self.action_history.append(entry)
        limit = self.max_history
        if len(self.action_history) > limit:
            self.action_history = self.action_history[-limit:]

    # ------------------------------------------------------------------
    # Persistence
    # ------------------------------------------------------------------

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        payload = self.model_dump(mode="json", by_alias=True)
        fd, tmp_name = tempfile.mkstemp(dir=str(path.parent), prefix=".state-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(payload, f, ensure_ascii=False, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_name, path)
        finally:
            if os.path.exists(tmp_name):
                os.remove(tmp_name)

    @classmethod
    def load(cls, path: Path) -> "BridgeState":
        if not path.exists():
            return cls()
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f)
        return cls.model_validate(data)

    @classmethod
    def load_or_create(cls, path: Path) -> "BridgeState":
        try:
            return cls.load(path)
        except Exception:
            # Corrupt state file: start fresh rather than crash the bridge.
            return cls()
