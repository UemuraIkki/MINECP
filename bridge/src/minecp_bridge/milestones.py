"""Milestone DAG (仕様書§8.1) and completion heuristics.

The DAG is the 14-node chain defined once, authoritatively, as the
``Milestone`` enum in ``schema/common.schema.json`` (in dependency order).
This module treats that enum order as the edge list of a linear chain
(each milestone's sole prerequisite is the one before it) and adds
inventory/progress-flag based completion detection (仕様書§4.2.1).

Some milestones cannot be derived from a single observation alone (e.g.
``stronghold_found`` depends on whether the bridge has *previously*
registered a stronghold coordinate from a ``throw_ender_eye`` skill
result). Those take the current :class:`~minecp_bridge.state.BridgeState`
as additional context. This is a deliberate, documented heuristic layer —
see ``bridge/README.md`` "未解決事項" for its limitations.
"""

from __future__ import annotations

from dataclasses import dataclass

from .messages import Milestone, Observation

MILESTONE_ORDER: list[Milestone] = list(Milestone)

MILESTONE_PREREQS: dict[Milestone, Milestone | None] = {
    milestone: (MILESTONE_ORDER[i - 1] if i > 0 else None) for i, milestone in enumerate(MILESTONE_ORDER)
}

# Vanilla wooden/stone/iron/diamond pickaxe ids, used by several heuristics.
_WOOD_ITEMS = {"minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks"}
_WOODEN_TOOLS = {"minecraft:wooden_pickaxe", "minecraft:wooden_axe", "minecraft:wooden_sword"}
_STONE_TOOLS = {"minecraft:stone_pickaxe", "minecraft:stone_axe", "minecraft:stone_sword"}
_IRON_TOOLS = {"minecraft:iron_pickaxe", "minecraft:iron_sword"}
_IRON_ARMOR = {"minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots"}
_DIAMOND_TOOLS = {"minecraft:diamond_pickaxe"}
_FOOD_ITEMS = {"minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken", "minecraft:cooked_mutton", "minecraft:cooked_rabbit", "minecraft:baked_potato", "minecraft:apple", "minecraft:carrot"}
_FOOD_SECURED_THRESHOLD = 8

BLAZE_RODS_REQUIRED = 7
ENDER_PEARLS_REQUIRED = 12
ENDER_EYES_REQUIRED = 12

_NETHER_ADVANCEMENT = "minecraft:story/enter_the_nether"
_PORTAL_ACTIVATED_ADVANCEMENT = "minecraft:end/enter_end_portal"
_DRAGON_SLAIN_ADVANCEMENT = "minecraft:end/kill_dragon"


@dataclass
class MilestoneContext:
    """External facts the observation alone cannot express (ADR-0001: memory
    coordinates live in the bridge, not the observation)."""

    has_nether_portal: bool = False
    has_stronghold_location: bool = False
    equipment_finalized: bool = False


def _has_any(item_counts: dict[str, int], ids: set[str]) -> bool:
    return any(item_counts.get(i, 0) > 0 for i in ids)


def _item_counts(observation: Observation) -> dict[str, int]:
    return {stack.id: stack.count for stack in observation.inventory.items}


def is_completed(
    milestone: Milestone,
    observation: Observation,
    context: MilestoneContext | None = None,
) -> bool:
    """Best-effort completion check for a single milestone."""

    context = context or MilestoneContext()
    counts = _item_counts(observation)
    advancements = set(observation.progress.advancements)

    if milestone is Milestone.wood:
        return _has_any(counts, _WOOD_ITEMS) or _has_any(counts, _WOODEN_TOOLS) or _has_any(counts, _STONE_TOOLS) or _has_any(counts, _IRON_TOOLS)
    if milestone is Milestone.wooden_tools:
        return _has_any(counts, _WOODEN_TOOLS) or _has_any(counts, _STONE_TOOLS) or _has_any(counts, _IRON_TOOLS) or _has_any(counts, _DIAMOND_TOOLS)
    if milestone is Milestone.stone_tools:
        return _has_any(counts, _STONE_TOOLS) or _has_any(counts, _IRON_TOOLS) or _has_any(counts, _DIAMOND_TOOLS)
    if milestone is Milestone.iron_gear:
        return counts.get("minecraft:iron_pickaxe", 0) > 0 and counts.get("minecraft:iron_sword", 0) > 0
    if milestone is Milestone.food_secured:
        total_food = sum(counts.get(i, 0) for i in _FOOD_ITEMS)
        return total_food >= _FOOD_SECURED_THRESHOLD
    if milestone is Milestone.diamond_tools:
        return _has_any(counts, _DIAMOND_TOOLS)
    if milestone is Milestone.nether_portal:
        return context.has_nether_portal or _NETHER_ADVANCEMENT in advancements
    if milestone is Milestone.blaze_rods:
        return observation.progress.blaze_rods >= BLAZE_RODS_REQUIRED
    if milestone is Milestone.ender_pearls:
        return observation.progress.ender_pearls >= ENDER_PEARLS_REQUIRED
    if milestone is Milestone.ender_eyes:
        return observation.progress.ender_eyes >= ENDER_EYES_REQUIRED
    if milestone is Milestone.stronghold_found:
        return context.has_stronghold_location
    if milestone is Milestone.portal_activated:
        return _PORTAL_ACTIVATED_ADVANCEMENT in advancements
    if milestone is Milestone.gear_final_check:
        has_diamond_sword = counts.get("minecraft:diamond_sword", 0) > 0
        armor_pieces = [
            observation.equipment.helmet,
            observation.equipment.chestplate,
            observation.equipment.leggings,
            observation.equipment.boots,
        ]
        full_armor = all(a is not None for a in armor_pieces)
        return context.equipment_finalized or (has_diamond_sword and full_armor)
    if milestone is Milestone.dragon_slain:
        return _DRAGON_SLAIN_ADVANCEMENT in advancements
    raise AssertionError(f"unhandled milestone: {milestone}")


def evaluate_all(observation: Observation, context: MilestoneContext | None = None) -> set[Milestone]:
    """Return the set of milestones considered complete given this observation."""

    return {m for m in MILESTONE_ORDER if is_completed(m, observation, context)}


def current_milestone(completed: set[Milestone] | list[Milestone]) -> Milestone:
    """The first not-yet-completed milestone in DAG order.

    If every milestone is complete, returns the last one (dragon_slain).
    """

    completed_set = set(completed)
    for milestone in MILESTONE_ORDER:
        if milestone not in completed_set:
            return milestone
    return MILESTONE_ORDER[-1]


def milestone_dag_text() -> str:
    """Human/LLM-readable rendering of the DAG, for prompt embedding (§8.1)."""

    return " -> ".join(m.value for m in MILESTONE_ORDER)
