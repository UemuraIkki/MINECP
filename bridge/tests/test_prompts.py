"""prompts.py had no dedicated test file; it was only exercised indirectly
through AgentLoop-level tests. These verify each prompt builder actually
includes the information it claims to (skills, milestones, memory, history,
failure detail), since a prompt that silently drops a section is invisible
until the LLM starts making worse decisions.
"""

from __future__ import annotations

from conftest import sample_observation_raw
from minecp_bridge.messages import BlockPos, Milestone, Observation, SkillName, SkillResult
from minecp_bridge.prompts import (
    SKILL_DESCRIPTIONS,
    build_death_recovery_prompt,
    build_reflection_prompt,
    build_situation_prompt,
    build_system_prompt,
    build_tool_error_feedback,
)
from minecp_bridge.state import BridgeState


def test_system_prompt_lists_every_skill_and_the_full_milestone_chain():
    prompt = build_system_prompt()
    for skill in SkillName:
        assert skill.value in prompt
    assert len(SKILL_DESCRIPTIONS) == len(SkillName)
    assert "wood -> wooden_tools" in prompt
    assert prompt.endswith("Respond by calling exactly one of the available tools with concrete arguments.")


def test_situation_prompt_without_observation_is_a_short_placeholder():
    assert build_situation_prompt(BridgeState(), []) == "No observation received yet."


def test_situation_prompt_reflects_observation_memory_and_history():
    state = BridgeState()
    raw = sample_observation_raw()
    state.record_observation(Observation.model_validate(raw))
    state.register_memory("base", BlockPos(x=1, y=64, z=2))
    state.record_command_issued("mine", "cmd-1", {"block": "log", "count": 4}, 1_700_000_000_000)

    prompt = build_situation_prompt(state, {Milestone.wood})

    assert "Completed: wood" in prompt
    assert "Currently working toward: wooden_tools" in prompt
    assert "hp=20.0/20" in prompt
    assert "minecraft:iron_pickaxe" in prompt  # from the sample inventory
    assert "base: (1, 64, 2)" in prompt
    assert "mine({'block': 'log', 'count': 4})" in prompt


def test_reflection_prompt_includes_only_the_failing_skills_recent_attempts():
    state = BridgeState()
    state.record_observation(Observation.model_validate(sample_observation_raw()))
    for i in range(4):
        args = {"block": "log", "count": i + 1}  # distinct per attempt, so recency is checkable
        state.record_command_issued("mine", f"cmd-{i}", args, 1_700_000_000_000 + i)
        result_raw = {
            "message_type": "skill_result",
            "timestamp_ms": 1_700_000_000_000 + i,
            "seq": i,
            "command_id": f"cmd-{i}",
            "status": "failure",
            "failure_code": "TARGET_NOT_FOUND",
        }
        state.record_skill_result(SkillResult.model_validate(result_raw), "mine", args)

    prompt = build_reflection_prompt(state, "mine", threshold=3)
    failure_section = prompt.split("\n\nCurrent situation:\n", 1)[0]

    assert "the skill 'mine' has now failed 3 times in a row" in prompt
    assert "'count': 1" not in failure_section  # oldest attempt trimmed from the last-3 window
    assert "'count': 4" in failure_section  # most recent attempt retained


def test_death_recovery_prompt_includes_position_dimension_and_timer():
    state = BridgeState()
    state.record_observation(Observation.model_validate(sample_observation_raw()))

    prompt = build_death_recovery_prompt(state, (12.0, 45.0, -8.0), "nether", "minecraft:lava", 42.4)

    assert "died at (12, 45, -8)" in prompt
    assert "in dimension 'nether'" in prompt
    assert "cause: minecraft:lava" in prompt
    assert "approximately 42 seconds" in prompt


def test_death_recovery_prompt_clamps_negative_remaining_time_to_zero():
    state = BridgeState()
    state.record_observation(Observation.model_validate(sample_observation_raw()))

    prompt = build_death_recovery_prompt(state, (0.0, 0.0, 0.0), "overworld", "minecraft:zombie", -5.0)

    assert "approximately 0 seconds" in prompt


def test_tool_error_feedback_lists_every_error():
    prompt = build_tool_error_feedback(["bad arguments", "unknown skill"])
    assert "- bad arguments" in prompt
    assert "- unknown skill" in prompt
