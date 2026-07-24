"""Requirement (2): state persistence round-trips through a JSON file, and
survives a simulated process restart.
"""

from __future__ import annotations

from pathlib import Path

from conftest import sample_observation_raw, sample_skill_result_raw
from minecp_bridge.messages import BlockPos, NamedLocation, Observation, SkillResult
from minecp_bridge.state import BridgeState


def test_round_trip_empty_state(tmp_path: Path):
    state = BridgeState()
    path = tmp_path / "state.json"
    state.save(path)

    loaded = BridgeState.load(path)
    assert loaded == state


def test_round_trip_with_data(tmp_path: Path):
    state = BridgeState()
    observation = Observation.model_validate(sample_observation_raw())
    state.record_observation(observation)
    state.register_memory(NamedLocation.base, BlockPos(x=1, y=2, z=3))
    state.register_memory(NamedLocation.last_death, BlockPos(x=-4, y=5, z=6))
    state.record_command_issued("goto", "cmd-1", {"target": "base"}, 1_700_000_000_000)

    result = SkillResult.model_validate(sample_skill_result_raw(status="failure"))
    result = result.model_copy(update={"command_id": "cmd-1"})
    state.record_skill_result(result, "goto", {"target": "base"})

    path = tmp_path / "nested" / "state.json"
    state.save(path)
    assert path.exists()

    loaded = BridgeState.load(path)
    assert loaded.last_observation is not None
    assert loaded.last_observation.self_.pos.x == observation.self_.pos.x
    assert loaded.get_memory(NamedLocation.base) == BlockPos(x=1, y=2, z=3)
    assert loaded.get_memory(NamedLocation.last_death) == BlockPos(x=-4, y=5, z=6)
    assert loaded.consecutive_failures["goto"] == 1
    assert len(loaded.action_history) == 2  # one "issued", one "failure"
    assert loaded.action_history[-1].status == "failure"
    assert loaded.action_history[-1].failure_code.value == "PATH_NOT_FOUND"


def test_history_is_bounded(tmp_path: Path):
    state = BridgeState(max_history=3)
    for i in range(10):
        state.record_command_issued("goto", f"cmd-{i}", {"target": "base"}, i)
    assert len(state.action_history) == 3
    assert state.action_history[-1].command_id == "cmd-9"


def test_load_missing_file_returns_fresh_state(tmp_path: Path):
    missing = tmp_path / "does_not_exist.json"
    state = BridgeState.load(missing)
    assert state == BridgeState()


def test_load_or_create_recovers_from_corrupt_file(tmp_path: Path):
    path = tmp_path / "state.json"
    path.write_text("{not valid json", encoding="utf-8")
    state = BridgeState.load_or_create(path)
    assert state == BridgeState()


def test_save_is_atomic_no_leftover_tmp_files(tmp_path: Path):
    state = BridgeState()
    path = tmp_path / "state.json"
    state.save(path)
    leftovers = list(tmp_path.glob(".state-*.tmp"))
    assert leftovers == []
