package dev.minecp.protocol;

/**
 * Exact mirror of schema/failure_codes.schema.json. Do not add a value here
 * until that schema is changed first.
 */
public enum FailureCode {
    INSUFFICIENT_MATERIALS,
    NO_FUEL,
    NO_TOOL,
    PATH_NOT_FOUND,
    TARGET_NOT_FOUND,
    TARGET_UNREACHABLE,
    INVENTORY_FULL,
    AGENT_DIED,
    TIMEOUT_STUCK,
    INTERRUPTED_BY_NEW_COMMAND,
    INTERRUPTED_BY_DISCONNECT,
    INVALID_ARGUMENTS,
    UNSUPPORTED_ITEM,
    CRAFTING_FAILED,
    SMELTING_FAILED,
    PLACEMENT_OBSTRUCTED,
    NO_FOOD,
    FLED_FROM_COMBAT,
    PORTAL_NOT_FOUND,
    PORTAL_BUILD_FAILED,
    NO_ENDER_EYE,
    DRAGON_FIGHT_ABORTED,
    INTERNAL_ERROR
}
