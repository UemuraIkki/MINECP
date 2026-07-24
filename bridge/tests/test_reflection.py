"""Requirement (4): the reflection loop fires exactly when the same skill has
failed reflection_failure_threshold times in a row, and resets afterwards.
"""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest

import minecp_bridge.agent_loop as agent_loop_module
from minecp_bridge.agent_loop import AgentLoop
from minecp_bridge.config import BridgeConfig
from minecp_bridge.llm import LLMDecision, build_skill_command
from minecp_bridge.logging_setup import get_session_logger
from minecp_bridge.messages import FailureCode, SkillResult, SkillStatus
from minecp_bridge.state import BridgeState


def _failure_result(command_id: str, seq: int) -> SkillResult:
    return SkillResult(
        timestamp_ms=1_700_000_000_000 + seq,
        seq=seq,
        command_id=command_id,
        status=SkillStatus.failure,
        failure_code=FailureCode.PATH_NOT_FOUND,
        detail="stuck",
    )


@pytest.fixture
async def loop(tmp_path: Path) -> AgentLoop:
    config = BridgeConfig(reflection_failure_threshold=3, state_dir=tmp_path, logs_dir=tmp_path)
    state = BridgeState()
    session_logger = get_session_logger(tmp_path)
    async with httpx.AsyncClient() as client:
        agent = AgentLoop(config, state, client, session_logger)

        calls = []

        async def fake_decide_skill(*args, seq, exchange_logger=None, **kwargs):
            calls.append(seq)
            command = build_skill_command("goto", {"target": "base"}, seq=seq)
            return LLMDecision(command=command, attempts=1, used_fallback=False)

        sent = []

        async def fake_send_command(command):
            sent.append(command)
            return True

        agent_loop_module.decide_skill = fake_decide_skill  # monkeypatch module-level ref
        agent.server.send_command = fake_send_command
        agent._test_calls = calls
        agent._test_sent = sent
        yield agent


@pytest.mark.asyncio
async def test_reflection_not_triggered_before_threshold(loop: AgentLoop):
    for i in range(2):
        loop.state.record_command_issued("mine", f"cmd-{i}", {"block": "minecraft:iron_ore", "count": 1}, i)
        await loop.on_skill_result(_failure_result(f"cmd-{i}", i))
        if loop._decision_task is not None:
            await loop._decision_task

    assert loop.state.consecutive_failures["mine"] == 2
    assert loop._test_calls == []  # no LLM decision triggered yet


@pytest.mark.asyncio
async def test_reflection_triggers_on_third_consecutive_failure(loop: AgentLoop):
    for i in range(3):
        loop.state.record_command_issued("mine", f"cmd-{i}", {"block": "minecraft:iron_ore", "count": 1}, i)
        await loop.on_skill_result(_failure_result(f"cmd-{i}", i))
        if loop._decision_task is not None:
            await loop._decision_task

    assert loop._test_calls == [1]  # exactly one reflection decision was made
    assert len(loop._test_sent) == 1
    # streak resets after the reflection fires, so a subsequent failure alone
    # should not immediately retrigger reflection.
    assert loop.state.consecutive_failures["mine"] == 0


@pytest.mark.asyncio
async def test_reflection_streak_resets_on_success(loop: AgentLoop):
    for i in range(2):
        loop.state.record_command_issued("mine", f"cmd-{i}", {"block": "minecraft:iron_ore", "count": 1}, i)
        await loop.on_skill_result(_failure_result(f"cmd-{i}", i))
        if loop._decision_task is not None:
            await loop._decision_task
    assert loop.state.consecutive_failures["mine"] == 2

    success = SkillResult(
        timestamp_ms=1_700_000_000_010,
        seq=10,
        command_id="cmd-2",
        status=SkillStatus.success,
        detail="mined",
    )
    loop.state.record_command_issued("mine", "cmd-2", {"block": "minecraft:iron_ore", "count": 1}, 10)
    await loop.on_skill_result(success)

    assert loop.state.consecutive_failures["mine"] == 0
    assert loop._test_calls == []  # success never triggers reflection
