"""要塞のx,z座標は、throw_ender_eyeが返す方向ベクトルを2地点から投げて
三角測量することで推定する(仕様書/ADR-0001: 位置オラクル禁止のため、
Modは方向と生存有無しか返さない)。

`BridgeState.record_ender_eye_throw`の幾何計算そのものと、
`AgentLoop`経由での配線(推定値の登録・実観測による上書き・実観測後の
非上書き)の両方を検証する。
"""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest
from conftest import sample_observation_raw

from minecp_bridge.agent_loop import AgentLoop
from minecp_bridge.config import BridgeConfig
from minecp_bridge.logging_setup import get_session_logger
from minecp_bridge.messages import BlockPos, NamedLocation, Observation, SkillResult
from minecp_bridge.state import BridgeState


def test_single_throw_does_not_triangulate():
    state = BridgeState()
    assert state.record_ender_eye_throw(0.0, 0.0, 1.0, 0.0) is None


def test_two_throws_triangulate_to_expected_point():
    state = BridgeState()
    state.record_ender_eye_throw(0.0, 0.0, 1.0, 0.0)  # heading +x from origin
    estimate = state.record_ender_eye_throw(20.0, 20.0, 0.0, -1.0)  # heading -z from (20, 20)
    assert estimate is not None
    x, z = estimate
    assert x == pytest.approx(20.0)
    assert z == pytest.approx(0.0)


def test_parallel_throws_do_not_triangulate():
    state = BridgeState()
    state.record_ender_eye_throw(0.0, 0.0, 1.0, 0.0)
    estimate = state.record_ender_eye_throw(20.0, 20.0, 1.0, 0.0)
    assert estimate is None


def test_throws_too_close_together_do_not_triangulate():
    state = BridgeState()
    state.record_ender_eye_throw(0.0, 0.0, 1.0, 0.0)
    estimate = state.record_ender_eye_throw(2.0, 2.0, 0.0, -1.0)  # < 16-block separation
    assert estimate is None


def test_intersection_behind_a_throw_point_is_rejected():
    state = BridgeState()
    # Thrown pointing away from where the other throw would place the target.
    state.record_ender_eye_throw(0.0, 0.0, -1.0, 0.0)
    estimate = state.record_ender_eye_throw(10.0, 20.0, 0.0, -1.0)
    assert estimate is None


@pytest.fixture
async def loop(tmp_path: Path):
    config = BridgeConfig(state_dir=tmp_path, logs_dir=tmp_path)
    session_logger = get_session_logger(tmp_path)
    async with httpx.AsyncClient() as client:
        yield AgentLoop(config, BridgeState(), client, session_logger)


def _observation_at(x: float, z: float, *, seq: int) -> Observation:
    raw = sample_observation_raw(seq=seq)
    raw["self"]["pos"] = {"x": x, "y": 40.0, "z": z}
    raw["nearby"]["points_of_interest"] = []
    return Observation.model_validate(raw)


def _throw_result(command_id: str, dir_x: float, dir_z: float, *, seq: int) -> SkillResult:
    return SkillResult.model_validate(
        {
            "message_type": "skill_result",
            "timestamp_ms": 1_700_000_000_000,
            "seq": seq,
            "command_id": command_id,
            "status": "success",
            "detail": "Ender eye flight completed",
            "data": {"direction": {"x": dir_x, "y": 0.05, "z": dir_z}, "eye_survived": True},
        }
    )


@pytest.mark.asyncio
async def test_two_throws_register_an_approximate_stronghold_estimate(loop: AgentLoop):
    await loop.on_observation(_observation_at(0.0, 0.0, seq=1))
    loop.state.current_skill = "throw_ender_eye"
    await loop.on_skill_result(_throw_result("cmd-1", 1.0, 0.0, seq=2))

    await loop.on_observation(_observation_at(20.0, 20.0, seq=3))
    loop.state.current_skill = "throw_ender_eye"
    await loop.on_skill_result(_throw_result("cmd-2", 0.0, -1.0, seq=4))

    pos = loop.state.get_memory(NamedLocation.stronghold)
    assert pos is not None
    assert (pos.x, pos.z) == (20, 0)
    assert loop.state.stronghold_estimate_is_exact is False


@pytest.mark.asyncio
async def test_observed_stronghold_block_overrides_triangulated_estimate(loop: AgentLoop):
    loop.state.register_memory(NamedLocation.stronghold, BlockPos(x=10, y=40, z=0))
    loop.state.stronghold_estimate_is_exact = False

    raw = sample_observation_raw(seq=5)
    raw["nearby"]["points_of_interest"] = [
        {"kind": "stronghold_block", "id": "minecraft:stone_bricks", "pos": {"x": 12, "y": 30, "z": -3}, "distance": 2.0}
    ]
    await loop.on_observation(Observation.model_validate(raw))

    pos = loop.state.get_memory(NamedLocation.stronghold)
    assert (pos.x, pos.y, pos.z) == (12, 30, -3)
    assert loop.state.stronghold_estimate_is_exact is True


@pytest.mark.asyncio
async def test_new_triangulation_does_not_regress_an_exact_location(loop: AgentLoop):
    loop.state.register_memory(NamedLocation.stronghold, BlockPos(x=12, y=30, z=-3))
    loop.state.stronghold_estimate_is_exact = True

    await loop.on_observation(_observation_at(0.0, 0.0, seq=1))
    loop.state.current_skill = "throw_ender_eye"
    await loop.on_skill_result(_throw_result("cmd-1", 1.0, 0.0, seq=2))
    await loop.on_observation(_observation_at(20.0, 20.0, seq=3))
    loop.state.current_skill = "throw_ender_eye"
    await loop.on_skill_result(_throw_result("cmd-2", 0.0, -1.0, seq=4))

    pos = loop.state.get_memory(NamedLocation.stronghold)
    assert (pos.x, pos.y, pos.z) == (12, 30, -3)
    assert loop.state.stronghold_estimate_is_exact is True
