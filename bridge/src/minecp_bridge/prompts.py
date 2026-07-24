"""System / situation / reflection / death-recovery prompt construction.

Prompts are written in English regardless of the rest of this repository's
Japanese documentation, per the implementation requirement (better stability
observed with Qwen-family models on English instructions).
"""

from __future__ import annotations

from typing import Any

from .messages import FailureCode, Milestone, Observation, SkillName
from .milestones import current_milestone, milestone_dag_text
from .state import ActionHistoryEntry, BridgeState

SKILL_DESCRIPTIONS: dict[SkillName, str] = {
    SkillName.goto: "goto(target): Move to a coordinate {x,y,z} or a named location "
    "(base, nether_portal_overworld, nether_portal_nether, stronghold, last_death). Uses Automatone pathfinding.",
    SkillName.mine: "mine(block, count): Locate and mine `count` of `block` (an item id or a family name "
    "like 'log', 'stone', 'iron_ore', 'diamond_ore'), including travel to it.",
    SkillName.craft: "craft(item, count): Craft `count` of `item`. Securing a crafting table is handled internally.",
    SkillName.smelt: "smelt(item, count): Smelt to produce `count` of `item`. Securing a furnace and fuel is handled internally.",
    SkillName.place: "place(block, offset): Place `block` at a position relative to the agent's current position.",
    SkillName.attack: "attack(target_type): Engage `target_type` (an entity id, or 'nearest_hostile'). "
    "Combat mechanics and flee decisions are fully scripted, not decided by you.",
    SkillName.eat: "eat(): Eat available food if any is held.",
    SkillName.equip: "equip(item): Equip `item` (armor goes to the correct slot, otherwise main hand).",
    SkillName.use_portal: "use_portal(portal_type): Use a known 'nether' or 'end' portal.",
    SkillName.build_portal: "build_portal(): Build a nether portal (obsidian or the water+lava bucket method).",
    SkillName.throw_ender_eye: "throw_ender_eye(): Throw an ender eye to search for the stronghold; "
    "returns the flight direction as an observation.",
    SkillName.fight_dragon: "fight_dragon(): Run the full scripted End dragon fight (crystals then dragon).",
}

DECISION_BOUNDARIES = """\
Decision boundaries (do not try to out-think these — they are handled deterministically outside your control):
- Combat mechanics (vs mobs, vs the dragon), the dragon fight sequence (destroy crystals then attack), \
the End portal activation ritual, pathfinding/movement: all scripted or handled by Automatone.
- Your job is exclusively: (1) resource-gathering priority, (2) where to explore/dig/search, \
(3) what to do next after a success or failure, and (4) alternative plans when something fails.
"""

BEHAVIOR_PRINCIPLES = """\
Behavior principles:
- Always call exactly one skill (tool) per turn. Never respond with plain text only.
- Use the milestone DAG to figure out what you are currently working toward; do not invent new milestones.
- Prefer the skill that most directly advances the current (first incomplete) milestone.
- If a skill fails, read the failure_code and adjust: do not blindly repeat the exact same call.
- Keep the agent safe: if HP is low, prioritize eating or retreating (goto base) over risky actions.
- Base camp coordinates and other memory locations are provided to you; refer to them by name with goto \
rather than guessing coordinates.
"""


def build_system_prompt() -> str:
    skill_lines = "\n".join(f"- {desc}" for desc in SKILL_DESCRIPTIONS.values())
    return f"""You are the sole decision-making component of an autonomous Minecraft agent (Java Edition 1.20.1). \
A separate, deterministic game-side mod executes whatever skill you choose; you never control tick-by-tick \
movement or combat directly.

Goal: defeat the Ender Dragon (beat the game) with no human intervention.

Available skills:
{skill_lines}

Milestone DAG (in required order, do not skip ahead conceptually — you may still act out of order if it \
genuinely helps, e.g. gathering food while waiting on a smelt):
{milestone_dag_text()}

{DECISION_BOUNDARIES}
{BEHAVIOR_PRINCIPLES}
Respond by calling exactly one of the available tools with concrete arguments."""


def _format_failure_code(code: FailureCode | None) -> str:
    return code.value if code is not None else "-"


def _format_history(history: list[ActionHistoryEntry], limit: int = 10) -> str:
    if not history:
        return "(no prior actions this session)"
    lines = []
    for entry in history[-limit:]:
        lines.append(
            f"- [{entry.status}] {entry.skill}({entry.args}) "
            f"failure_code={_format_failure_code(entry.failure_code)} detail={entry.detail or '-'}"
        )
    return "\n".join(lines)


def _format_memory(state: BridgeState) -> str:
    if not state.memory_coords:
        return "(no locations remembered yet)"
    lines = [f"- {name}: ({pos.x}, {pos.y}, {pos.z})" for name, pos in state.memory_coords.items()]
    return "\n".join(lines)


def _format_observation(observation: Observation) -> str:
    self_ = observation.self_
    inv = ", ".join(f"{i.id}x{i.count}" for i in observation.inventory.items) or "(empty)"
    equip = observation.equipment
    poi = ", ".join(
        f"{p.kind.value}:{p.id}@{p.distance:.0f}m" for p in observation.nearby.points_of_interest
    ) or "(none)"
    hostiles = ", ".join(
        f"{h.type}@{h.distance:.0f}m" for h in observation.nearby.hostiles
    ) or "(none)"
    return f"""Self: hp={self_.hp}/20 food={self_.food}/20 pos=({self_.pos.x:.0f},{self_.pos.y:.0f},{self_.pos.z:.0f}) \
dimension={self_.dimension.value} time_of_day={self_.time_of_day}
Inventory ({observation.inventory.empty_slots} empty slots): {inv}
Equipment: main_hand={equip.main_hand} off_hand={equip.off_hand} helmet={equip.helmet} \
chestplate={equip.chestplate} leggings={equip.leggings} boots={equip.boots}
Nearby points of interest: {poi}
Nearby hostiles: {hostiles}
Villagers nearby: {observation.nearby.villagers}
Progress: blaze_rods={observation.progress.blaze_rods} ender_pearls={observation.progress.ender_pearls} \
ender_eyes={observation.progress.ender_eyes}
Relevant advancements: {', '.join(observation.progress.advancements) or '(none)'}"""


def build_situation_prompt(state: BridgeState, completed_milestones: set[Milestone] | list[Milestone]) -> str:
    if state.last_observation is None:
        return "No observation received yet."

    active_milestone = current_milestone(completed_milestones)
    completed_text = ", ".join(m.value for m in completed_milestones) or "(none yet)"

    return f"""Current milestone status:
- Completed: {completed_text}
- Currently working toward: {active_milestone.value}

{_format_observation(state.last_observation)}

Remembered locations:
{_format_memory(state)}

Recent action history (most recent last):
{_format_history(state.action_history)}

Decide the single best next skill call."""


def build_reflection_prompt(state: BridgeState, skill: str, threshold: int) -> str:
    relevant = [h for h in state.action_history if h.skill == skill][-threshold:]
    failures_text = "\n".join(
        f"- attempt: {h.skill}({h.args}) -> failure_code={_format_failure_code(h.failure_code)} detail={h.detail or '-'}"
        for h in relevant
    )
    return f"""REFLECTION REQUIRED: the skill '{skill}' has now failed {threshold} times in a row with the same \
arguments pattern. Repeating it again is not acceptable this turn.

Failure history for '{skill}':
{failures_text}

Current situation:
{build_situation_prompt(state, state.completed_milestones)}

Propose a different next action: either a different skill, different arguments (e.g. a different target \
location/block), or a step back to a prerequisite milestone (e.g. gather more basic materials first). \
Call exactly one tool with your revised plan."""


def build_death_recovery_prompt(
    state: BridgeState,
    death_pos: tuple[float, float, float],
    dimension: str,
    cause: str,
    seconds_until_despawn: float,
) -> str:
    return f"""DEATH RECOVERY: the agent died at ({death_pos[0]:.0f}, {death_pos[1]:.0f}, {death_pos[2]:.0f}) \
in dimension '{dimension}' (cause: {cause}) and has respawned. Dropped items despawn in \
approximately {max(seconds_until_despawn, 0):.0f} seconds if not recovered.

Current situation after respawn:
{build_situation_prompt(state, state.completed_milestones)}

Decide: recover the dropped items (goto the death location, named 'last_death') if the remaining time and \
risk make that worthwhile, or write off the loss and continue progressing (re-gather/re-craft) if recovery is \
too risky or the timer is too short. Call exactly one tool for the immediate next step."""


def build_tool_error_feedback(errors: list[str]) -> str:
    joined = "\n".join(f"- {e}" for e in errors)
    return f"""Your previous tool call was invalid:
{joined}

Call exactly one of the available tools again, with corrected arguments that match its schema exactly."""
