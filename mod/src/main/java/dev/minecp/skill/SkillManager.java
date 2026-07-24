package dev.minecp.skill;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecp.observation.ObservationService;
import dev.minecp.path.IPathfinder;
import dev.minecp.protocol.BridgeWebSocketClient;
import dev.minecp.protocol.FailureCode;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class SkillManager {
    public record CurrentSkill(String commandId, String skill, long elapsedMs) {
    }

    private static final Set<String> SKILLS = Set.of(
            "goto", "mine", "craft", "smelt", "place", "attack",
            "eat", "equip", "use_portal", "build_portal",
            "throw_ender_eye", "fight_dragon"
    );
    private static final Set<String> COMMAND_FIELDS = Set.of(
            "message_type", "timestamp_ms", "seq", "command_id", "skill", "args"
    );
    private static final long STUCK_TIMEOUT_MS = 60_000L;

    private final Supplier<ServerPlayerEntity> playerSupplier;
    private final IPathfinder pathfinder;
    private final BridgeWebSocketClient bridge;
    private final ObservationService observations;
    private final Logger logger;
    private ActiveTask active;
    private long activeStartedMs;
    private long lastMovementMs;
    private Vec3d lastPosition;

    public SkillManager(
            Supplier<ServerPlayerEntity> playerSupplier,
            IPathfinder pathfinder,
            BridgeWebSocketClient bridge,
            ObservationService observations,
            Logger logger
    ) {
        this.playerSupplier = playerSupplier;
        this.pathfinder = pathfinder;
        this.bridge = bridge;
        this.observations = observations;
        this.logger = logger;
        logger.info("Skill movement implementation: {}", pathfinder.implementationName());
    }

    public CurrentSkill currentSkill() {
        if (active == null) {
            return null;
        }
        return new CurrentSkill(
                active.commandId(),
                active.skill(),
                Math.max(0L, System.currentTimeMillis() - activeStartedMs)
        );
    }

    public void accept(JsonObject command) {
        String commandId = stringValue(command, "command_id");
        try {
            ValidatedCommand validated = validate(command);
            ServerPlayerEntity player = requirePlayer();

            if (active != null) {
                finish(Outcome.failure(
                        FailureCode.INTERRUPTED_BY_NEW_COMMAND,
                        "Interrupted by a newer valid skill command"
                ));
            }

            active = createTask(validated, player);
            activeStartedMs = System.currentTimeMillis();
            lastMovementMs = activeStartedMs;
            lastPosition = player.getPos();
            logger.info("Started skill {} ({})", active.skill(), active.commandId());
        } catch (CommandFailure failure) {
            if (commandId != null) {
                sendStandaloneFailure(commandId, failure.code, failure.getMessage());
            } else {
                logger.warn("Discarded skill command without a usable command_id: {}", failure.getMessage());
            }
        } catch (RuntimeException e) {
            logger.error("Could not accept skill command", e);
            if (commandId != null) {
                sendStandaloneFailure(commandId, FailureCode.INTERNAL_ERROR, "Internal command handling error");
            }
        }
    }

    public void tick() {
        if (active == null) {
            return;
        }
        ServerPlayerEntity player = playerSupplier.get();
        if (player == null || player.isDead()) {
            return;
        }

        long now = System.currentTimeMillis();
        Vec3d current = player.getPos();
        if (lastPosition == null || current.squaredDistanceTo(lastPosition) > 0.0001) {
            lastPosition = current;
            lastMovementMs = now;
        } else if (now - lastMovementMs >= STUCK_TIMEOUT_MS) {
            finish(Outcome.failure(
                    FailureCode.TIMEOUT_STUCK,
                    "Fake player position did not change for 60 continuous seconds"
            ));
            return;
        }

        try {
            Outcome outcome = active.tick(player, pathfinder);
            if (outcome != null) {
                finish(outcome);
            }
        } catch (RuntimeException e) {
            logger.error("Skill {} ({}) crashed", active.skill(), active.commandId(), e);
            finish(Outcome.failure(FailureCode.INTERNAL_ERROR, "Deterministic skill executor threw an exception"));
        }
    }

    public void onDisconnected() {
        if (active != null) {
            finish(Outcome.failure(
                    FailureCode.INTERRUPTED_BY_DISCONNECT,
                    "WebSocket disconnected; movement stopped and command aborted"
            ));
        }
    }

    public void onAgentDied() {
        if (active != null) {
            finish(Outcome.failure(FailureCode.AGENT_DIED, "Fake player died while executing the skill"));
        }
    }

    private void finish(Outcome outcome) {
        if (active == null) {
            return;
        }
        ServerPlayerEntity player = playerSupplier.get();
        ActiveTask finished = active;
        try {
            if (player != null) {
                finished.cancel(player, pathfinder);
            }
        } finally {
            active = null;
        }

        bridge.send("skill_result", json -> writeResult(json, finished.commandId(), outcome));
        observations.send("skill_finished");
        logger.info(
                "Finished skill {} ({}) with {}{}",
                finished.skill(),
                finished.commandId(),
                outcome.success ? "success" : "failure",
                outcome.failureCode == null ? "" : " " + outcome.failureCode
        );
    }

    private void sendStandaloneFailure(String commandId, FailureCode code, String detail) {
        bridge.send("skill_result", json -> writeResult(json, commandId, Outcome.failure(code, detail)));
        observations.send("skill_finished");
    }

    private static void writeResult(JsonObject json, String commandId, Outcome outcome) {
        json.addProperty("command_id", commandId);
        json.addProperty("status", outcome.success ? "success" : "failure");
        if (!outcome.success) {
            json.addProperty("failure_code", outcome.failureCode.name());
        }
        if (outcome.detail != null && !outcome.detail.isBlank()) {
            json.addProperty("detail", outcome.detail);
        }
        if (outcome.data != null) {
            json.add("data", outcome.data);
        }
    }

    private ValidatedCommand validate(JsonObject json) throws CommandFailure {
        requireExactFields(json, COMMAND_FIELDS);
        if (!"skill_command".equals(stringValue(json, "message_type"))
                || !isInteger(json.get("timestamp_ms"))
                || !isInteger(json.get("seq"))
                || json.get("seq").getAsLong() < 0L) {
            throw invalid("Invalid skill_command envelope");
        }

        String commandId = stringValue(json, "command_id");
        String skill = stringValue(json, "skill");
        if (commandId == null || commandId.isBlank() || skill == null || !SKILLS.contains(skill)) {
            throw invalid("Unknown skill or blank command_id");
        }
        JsonElement argsElement = json.get("args");
        if (argsElement == null || !argsElement.isJsonObject()) {
            throw invalid("args must be an object");
        }
        JsonObject args = argsElement.getAsJsonObject();

        switch (skill) {
            case "goto" -> validateGoto(args);
            case "mine" -> validateIdAndCount(args, "block");
            case "craft", "smelt" -> validateIdAndCount(args, "item");
            case "place" -> validatePlace(args);
            case "attack" -> {
                requireExactFields(args, Set.of("target_type"));
                requireNonBlankString(args, "target_type");
            }
            case "eat", "build_portal", "throw_ender_eye", "fight_dragon" ->
                    requireExactFields(args, Set.of());
            case "equip" -> {
                requireExactFields(args, Set.of("item"));
                requireNonBlankString(args, "item");
            }
            case "use_portal" -> {
                requireExactFields(args, Set.of("portal_type"));
                String type = requireNonBlankString(args, "portal_type");
                if (!type.equals("nether") && !type.equals("end")) {
                    throw invalid("portal_type must be nether or end");
                }
            }
            default -> throw invalid("Unknown skill");
        }
        return new ValidatedCommand(commandId, skill, args);
    }

    private static void validateGoto(JsonObject args) throws CommandFailure {
        requireExactFields(args, Set.of("target"));
        JsonElement target = args.get("target");
        if (target == null) {
            throw invalid("goto.target is required");
        }
        if (target.isJsonPrimitive() && target.getAsJsonPrimitive().isString()) {
            String named = target.getAsString();
            if (!Set.of("base", "nether_portal_overworld", "nether_portal_nether", "stronghold", "last_death").contains(named)) {
                throw invalid("Unknown named location");
            }
            return;
        }
        validateBlockPos(target, "goto.target");
    }

    private static void validateIdAndCount(JsonObject args, String idField) throws CommandFailure {
        requireExactFields(args, Set.of(idField, "count"));
        requireNonBlankString(args, idField);
        JsonElement count = args.get("count");
        if (!isInteger(count) || count.getAsInt() < 1 || count.getAsInt() > 4096) {
            throw invalid("count must be an integer from 1 through 4096");
        }
    }

    private static void validatePlace(JsonObject args) throws CommandFailure {
        requireExactFields(args, Set.of("block", "offset"));
        requireNonBlankString(args, "block");
        validateBlockPos(args.get("offset"), "place.offset");
    }

    private ActiveTask createTask(ValidatedCommand command, ServerPlayerEntity player) throws CommandFailure {
        JsonObject args = command.args;
        return switch (command.skill) {
            case "goto" -> {
                JsonElement target = args.get("target");
                if (!target.isJsonObject()) {
                    // ADR-0001 makes named-location memory a bridge responsibility.
                    throw new CommandFailure(
                            FailureCode.INVALID_ARGUMENTS,
                            "Named locations must be resolved to BlockPos by the bridge"
                    );
                }
                yield new GotoTask(command.commandId, blockPos(target.getAsJsonObject()));
            }
            case "mine" -> new MineTask(
                    command.commandId,
                    args.get("block").getAsString(),
                    args.get("count").getAsInt()
            );
            case "craft" -> new CraftTask(
                    command.commandId,
                    args.get("item").getAsString(),
                    args.get("count").getAsInt()
            );
            case "smelt" -> new UnsupportedTask(
                    command.commandId,
                    "smelt",
                    FailureCode.SMELTING_FAILED,
                    "P1 skeleton: deterministic furnace timing and inventory transfer are not implemented"
            );
            case "place" -> new PlaceTask(
                    command.commandId,
                    args.get("block").getAsString(),
                    blockPos(args.getAsJsonObject("offset"))
            );
            case "attack" -> new AttackTask(command.commandId, args.get("target_type").getAsString());
            case "eat" -> new EatTask(command.commandId);
            case "equip" -> new EquipTask(command.commandId, args.get("item").getAsString());
            case "use_portal" -> new UsePortalTask(command.commandId, args.get("portal_type").getAsString());
            case "build_portal" -> new BuildPortalTask(command.commandId);
            case "throw_ender_eye" -> new UnsupportedTask(
                    command.commandId,
                    "throw_ender_eye",
                    hasItem(player, Items.ENDER_EYE) ? FailureCode.INTERNAL_ERROR : FailureCode.NO_ENDER_EYE,
                    "P1 skeleton: stronghold locate and eye survival tracking are not implemented"
            );
            case "fight_dragon" -> new FightDragonTask(command.commandId);
            default -> throw invalid("Unknown skill");
        };
    }

    private ServerPlayerEntity requirePlayer() throws CommandFailure {
        ServerPlayerEntity player = playerSupplier.get();
        if (player == null || player.isDead()) {
            throw new CommandFailure(FailureCode.AGENT_DIED, "Fake player is not alive");
        }
        return player;
    }

    private static void requireExactFields(JsonObject json, Set<String> expected) throws CommandFailure {
        Set<String> actual = new HashSet<>(json.keySet());
        if (!actual.equals(expected)) {
            throw invalid("Object fields do not match schema; expected " + expected);
        }
    }

    private static String requireNonBlankString(JsonObject json, String field) throws CommandFailure {
        String value = stringValue(json, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " must be a nonblank string");
        }
        return value;
    }

    private static void validateBlockPos(JsonElement element, String field) throws CommandFailure {
        if (element == null || !element.isJsonObject()) {
            throw invalid(field + " must be a BlockPos object");
        }
        JsonObject pos = element.getAsJsonObject();
        requireExactFields(pos, Set.of("x", "y", "z"));
        if (!isInteger(pos.get("x")) || !isInteger(pos.get("y")) || !isInteger(pos.get("z"))) {
            throw invalid(field + " coordinates must be integers");
        }
    }

    private static boolean isInteger(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            return element.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String stringValue(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static BlockPos blockPos(JsonObject json) {
        return new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt());
    }

    private static CommandFailure invalid(String message) {
        return new CommandFailure(FailureCode.INVALID_ARGUMENTS, message);
    }

    private record ValidatedCommand(String commandId, String skill, JsonObject args) {
    }

    private static final class CommandFailure extends Exception {
        private final FailureCode code;

        private CommandFailure(FailureCode code, String message) {
            super(message);
            this.code = code;
        }
    }

    private record Outcome(boolean success, FailureCode failureCode, String detail, JsonObject data) {
        private static Outcome success(String detail) {
            return new Outcome(true, null, detail, null);
        }

        private static Outcome success(String detail, JsonObject data) {
            return new Outcome(true, null, detail, data);
        }

        private static Outcome failure(FailureCode code, String detail) {
            return new Outcome(false, code, detail, null);
        }
    }

    private interface ActiveTask {
        String commandId();

        String skill();

        Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder);

        default void cancel(ServerPlayerEntity player, IPathfinder pathfinder) {
            pathfinder.cancel(player);
        }
    }

    private abstract static class BaseTask implements ActiveTask {
        private final String commandId;
        private final String skill;

        private BaseTask(String commandId, String skill) {
            this.commandId = commandId;
            this.skill = skill;
        }

        @Override
        public final String commandId() {
            return commandId;
        }

        @Override
        public final String skill() {
            return skill;
        }
    }

    private static final class UnsupportedTask extends BaseTask {
        private final FailureCode failureCode;
        private final String detail;

        private UnsupportedTask(String commandId, String skill, FailureCode failureCode, String detail) {
            super(commandId, skill);
            this.failureCode = failureCode;
            this.detail = detail;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            return Outcome.failure(failureCode, detail);
        }
    }

    private static final class GotoTask extends BaseTask {
        private final BlockPos target;
        private boolean started;

        private GotoTask(String commandId, BlockPos target) {
            super(commandId, "goto");
            this.target = target.toImmutable();
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (!started) {
                if (target.getY() < player.getServerWorld().getBottomY()
                        || target.getY() >= player.getServerWorld().getTopY()) {
                    return Outcome.failure(FailureCode.INVALID_ARGUMENTS, "goto target is outside world height");
                }
                pathfinder.start(player, target);
                started = true;
            }
            return switch (pathfinder.tick(player)) {
                case REACHED -> Outcome.success("Reached target " + target.toShortString());
                case FAILED -> Outcome.failure(FailureCode.PATH_NOT_FOUND, "Pathfinder could not reach target");
                case IDLE -> Outcome.failure(FailureCode.PATH_NOT_FOUND, "Pathfinder stopped before reaching target");
                case RUNNING -> null;
            };
        }
    }

    private static final class MineTask extends BaseTask {
        private static final int SEARCH_RADIUS = 24;
        private static final int DIAMETER = SEARCH_RADIUS * 2 + 1;
        private static final int SEARCH_TOTAL = DIAMETER * DIAMETER * DIAMETER;
        private static final int SEARCH_PER_TICK = 1024;

        private final String requestedBlock;
        private final int requestedCount;
        private Predicate<BlockState> predicate;
        private BlockPos searchCenter;
        private BlockPos bestTarget;
        private double bestDistance;
        private int searchCursor;
        private BlockPos target;
        private boolean pathing;
        private int mined;
        private Outcome validationFailure;

        private MineTask(String commandId, String requestedBlock, int requestedCount) {
            super(commandId, "mine");
            this.requestedBlock = requestedBlock;
            this.requestedCount = requestedCount;
            try {
                this.predicate = blockPredicate(requestedBlock);
            } catch (CommandFailure failure) {
                this.validationFailure = Outcome.failure(failure.code, failure.getMessage());
            }
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (validationFailure != null) {
                return validationFailure;
            }
            if (mined >= requestedCount) {
                JsonObject data = new JsonObject();
                data.addProperty("mined_count", mined);
                return Outcome.success("Mined requested blocks", data);
            }

            if (target != null) {
                BlockState state = player.getServerWorld().getBlockState(target);
                if (!predicate.test(state)) {
                    target = null;
                    pathing = false;
                    resetSearch(player);
                    return null;
                }
                if (player.getPos().squaredDistanceTo(Vec3d.ofCenter(target)) <= 25.0) {
                    pathfinder.cancel(player);
                    pathing = false;
                    Outcome toolFailure = equipSuitableTool(player, state);
                    if (toolFailure != null) {
                        return toolFailure;
                    }
                    player.swingHand(Hand.MAIN_HAND);
                    boolean broke = player.interactionManager.tryBreakBlock(target);
                    if (!broke || predicate.test(player.getServerWorld().getBlockState(target))) {
                        return Outcome.failure(FailureCode.TARGET_UNREACHABLE, "Target block could not be broken");
                    }
                    mined++;
                    target = null;
                    resetSearch(player);
                    return null;
                }
                if (!pathing) {
                    pathfinder.start(player, target);
                    pathing = true;
                }
                IPathfinder.Status status = pathfinder.tick(player);
                if (status == IPathfinder.Status.FAILED || status == IPathfinder.Status.IDLE) {
                    return Outcome.failure(FailureCode.TARGET_UNREACHABLE, "Located target could not be reached");
                }
                return null;
            }

            if (searchCenter == null) {
                resetSearch(player);
            }
            ServerWorld world = player.getServerWorld();
            for (int sample = 0; sample < SEARCH_PER_TICK && searchCursor < SEARCH_TOTAL; sample++, searchCursor++) {
                int dx = searchCursor % DIAMETER - SEARCH_RADIUS;
                int remainder = searchCursor / DIAMETER;
                int dz = remainder % DIAMETER - SEARCH_RADIUS;
                int dy = remainder / DIAMETER - SEARCH_RADIUS;
                BlockPos candidate = searchCenter.add(dx, dy, dz);
                if (candidate.getY() < world.getBottomY()
                        || candidate.getY() >= world.getTopY()
                        || !world.isChunkLoaded(candidate)
                        || !predicate.test(world.getBlockState(candidate))) {
                    continue;
                }
                double distance = candidate.getSquaredDistance(player.getPos());
                if (bestTarget == null || distance < bestDistance) {
                    bestTarget = candidate.toImmutable();
                    bestDistance = distance;
                }
            }

            if (searchCursor >= SEARCH_TOTAL) {
                if (bestTarget == null) {
                    JsonObject data = new JsonObject();
                    data.addProperty("mined_count", mined);
                    return new Outcome(
                            false,
                            FailureCode.TARGET_NOT_FOUND,
                            "No matching " + requestedBlock + " found within 24 blocks",
                            data
                    );
                }
                target = bestTarget;
                pathing = false;
            }
            return null;
        }

        private void resetSearch(ServerPlayerEntity player) {
            searchCenter = player.getBlockPos().toImmutable();
            searchCursor = 0;
            bestTarget = null;
            bestDistance = Double.MAX_VALUE;
        }

        private static Predicate<BlockState> blockPredicate(String requested) throws CommandFailure {
            if (requested.contains(":")) {
                Identifier id;
                try {
                    id = new Identifier(requested);
                } catch (RuntimeException e) {
                    throw new CommandFailure(FailureCode.INVALID_ARGUMENTS, "Invalid block id");
                }
                if (!Registries.BLOCK.containsId(id)) {
                    throw new CommandFailure(FailureCode.UNSUPPORTED_ITEM, "Unknown block id " + requested);
                }
                Block block = Registries.BLOCK.get(id);
                return state -> state.isOf(block);
            }

            return switch (requested) {
                case "log" -> state -> {
                    String path = Registries.BLOCK.getId(state.getBlock()).getPath();
                    return path.endsWith("_log") || path.endsWith("_stem");
                };
                case "stone" -> state -> state.isOf(Blocks.STONE)
                        || state.isOf(Blocks.DEEPSLATE)
                        || state.isOf(Blocks.COBBLESTONE)
                        || state.isOf(Blocks.COBBLED_DEEPSLATE);
                case "coal_ore" -> oreFamily("coal_ore");
                case "copper_ore" -> oreFamily("copper_ore");
                case "iron_ore" -> oreFamily("iron_ore");
                case "gold_ore" -> oreFamily("gold_ore");
                case "redstone_ore" -> oreFamily("redstone_ore");
                case "lapis_ore" -> oreFamily("lapis_ore");
                case "diamond_ore" -> oreFamily("diamond_ore");
                case "emerald_ore" -> oreFamily("emerald_ore");
                default -> throw new CommandFailure(
                        FailureCode.UNSUPPORTED_ITEM,
                        "Unsupported block family " + requested
                );
            };
        }

        private static Predicate<BlockState> oreFamily(String suffix) {
            return state -> Registries.BLOCK.getId(state.getBlock()).getPath().endsWith(suffix);
        }

        private static Outcome equipSuitableTool(ServerPlayerEntity player, BlockState state) {
            if (!state.isToolRequired() || player.getMainHandStack().isSuitableFor(state)) {
                return null;
            }
            for (int slot = 0; slot < player.getInventory().main.size(); slot++) {
                ItemStack stack = player.getInventory().main.get(slot);
                if (stack.isSuitableFor(state)) {
                    moveToMainHand(player, slot);
                    return null;
                }
            }
            return Outcome.failure(FailureCode.NO_TOOL, "No suitable tool is available for the target block");
        }
    }

    private static final class CraftTask extends BaseTask {
        private final String itemId;
        private final int count;
        private boolean complete;

        private CraftTask(String commandId, String itemId, int count) {
            super(commandId, "craft");
            this.itemId = itemId;
            this.count = count;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (complete) {
                return Outcome.failure(FailureCode.INTERNAL_ERROR, "Craft task ticked after completion");
            }
            complete = true;

            Identifier id;
            try {
                id = new Identifier(itemId);
            } catch (RuntimeException e) {
                return Outcome.failure(FailureCode.INVALID_ARGUMENTS, "Invalid item id");
            }
            if (!Registries.ITEM.containsId(id)) {
                return Outcome.failure(FailureCode.UNSUPPORTED_ITEM, "Unknown item id " + itemId);
            }
            Item targetItem = Registries.ITEM.get(id);
            Optional<CraftingRecipe> recipeOptional = player.server.getRecipeManager()
                    .listAllOfType(RecipeType.CRAFTING)
                    .stream()
                    .filter(recipe -> recipe.getOutput(player.getServerWorld().getRegistryManager()).isOf(targetItem))
                    .sorted(Comparator.comparing(recipe -> recipe.getId().toString()))
                    .findFirst();
            if (recipeOptional.isEmpty()) {
                return Outcome.failure(FailureCode.UNSUPPORTED_ITEM, "No crafting recipe produces " + itemId);
            }

            CraftingRecipe recipe = recipeOptional.get();
            if (!recipe.fits(2, 2) && !hasCraftingTable(player)) {
                return Outcome.failure(
                        FailureCode.CRAFTING_FAILED,
                        "Recipe requires a crafting table in inventory or within four blocks"
                );
            }
            ItemStack output = recipe.getOutput(player.getServerWorld().getRegistryManager());
            int batches = (count + output.getCount() - 1) / output.getCount();
            int produced = batches * output.getCount();
            if (!canFit(player, targetItem, produced)) {
                return Outcome.failure(FailureCode.INVENTORY_FULL, "Craft output does not fit in inventory");
            }

            Map<Integer, Integer> reserved = new HashMap<>();
            for (int batch = 0; batch < batches; batch++) {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    int slot = findIngredientSlot(player, ingredient, reserved);
                    if (slot < 0) {
                        return Outcome.failure(
                                FailureCode.INSUFFICIENT_MATERIALS,
                                "Missing ingredient for recipe " + recipe.getId()
                        );
                    }
                    reserved.merge(slot, 1, Integer::sum);
                }
            }

            reserved.forEach((slot, amount) -> player.getInventory().removeStack(slot, amount));
            ItemStack crafted = output.copy();
            crafted.setCount(produced);
            if (!player.getInventory().insertStack(crafted) || !crafted.isEmpty()) {
                return Outcome.failure(FailureCode.CRAFTING_FAILED, "Could not insert crafted output");
            }
            return Outcome.success("Crafted " + produced + " of " + itemId);
        }

        private static int findIngredientSlot(
                ServerPlayerEntity player,
                Ingredient ingredient,
                Map<Integer, Integer> reserved
        ) {
            for (int slot = 0; slot < player.getInventory().main.size(); slot++) {
                ItemStack stack = player.getInventory().main.get(slot);
                int remaining = stack.getCount() - reserved.getOrDefault(slot, 0);
                if (remaining > 0 && ingredient.test(stack)) {
                    return slot;
                }
            }
            return -1;
        }
    }

    private static final class PlaceTask extends BaseTask {
        private final String blockId;
        private final BlockPos offset;
        private boolean complete;

        private PlaceTask(String commandId, String blockId, BlockPos offset) {
            super(commandId, "place");
            this.blockId = blockId;
            this.offset = offset;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (complete) {
                return Outcome.failure(FailureCode.INTERNAL_ERROR, "Place task ticked after completion");
            }
            complete = true;
            if (Math.abs(offset.getX()) > 5 || Math.abs(offset.getY()) > 5 || Math.abs(offset.getZ()) > 5) {
                return Outcome.failure(FailureCode.INVALID_ARGUMENTS, "place offset exceeds deterministic reach");
            }

            Identifier id;
            try {
                id = new Identifier(blockId);
            } catch (RuntimeException e) {
                return Outcome.failure(FailureCode.INVALID_ARGUMENTS, "Invalid block id");
            }
            if (!Registries.BLOCK.containsId(id)) {
                return Outcome.failure(FailureCode.UNSUPPORTED_ITEM, "Unknown block id " + blockId);
            }
            Block block = Registries.BLOCK.get(id);
            Item item = block.asItem();
            if (!(item instanceof BlockItem)) {
                return Outcome.failure(FailureCode.UNSUPPORTED_ITEM, blockId + " has no placeable item");
            }
            int slot = findItemSlot(player, item);
            if (slot < 0) {
                return Outcome.failure(FailureCode.INSUFFICIENT_MATERIALS, "Required block is not in inventory");
            }

            BlockPos target = player.getBlockPos().add(offset);
            ServerWorld world = player.getServerWorld();
            if (!world.getBlockState(target).isReplaceable()
                    || !world.getWorldBorder().contains(target)
                    || !world.canPlayerModifyAt(player, target)) {
                return Outcome.failure(FailureCode.PLACEMENT_OBSTRUCTED, "Target position is not replaceable");
            }
            if (!world.setBlockState(target, block.getDefaultState(), Block.NOTIFY_ALL)) {
                return Outcome.failure(FailureCode.PLACEMENT_OBSTRUCTED, "World rejected block placement");
            }
            player.getInventory().main.get(slot).decrement(1);
            player.swingHand(Hand.MAIN_HAND);
            return Outcome.success("Placed " + blockId + " at " + target.toShortString());
        }
    }

    private static final class EatTask extends BaseTask {
        private EatTask(String commandId) {
            super(commandId, "eat");
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            for (int slot = 0; slot < player.getInventory().main.size(); slot++) {
                ItemStack stack = player.getInventory().main.get(slot);
                if (stack.isEmpty() || !stack.isFood()) {
                    continue;
                }
                if (player.getHungerManager().isNotFull()
                        || (stack.getItem().getFoodComponent() != null
                        && stack.getItem().getFoodComponent().isAlwaysEdible())) {
                    ItemStack remainder = player.eatFood(player.getWorld(), stack);
                    player.getInventory().setStack(slot, remainder);
                    return Outcome.success("Ate " + Registries.ITEM.getId(stack.getItem()));
                }
            }
            return Outcome.failure(FailureCode.NO_FOOD, "No currently edible food is available");
        }
    }

    private static final class EquipTask extends BaseTask {
        private final String itemId;

        private EquipTask(String commandId, String itemId) {
            super(commandId, "equip");
            this.itemId = itemId;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            Identifier id;
            try {
                id = new Identifier(itemId);
            } catch (RuntimeException e) {
                return Outcome.failure(FailureCode.INVALID_ARGUMENTS, "Invalid item id");
            }
            if (!Registries.ITEM.containsId(id)) {
                return Outcome.failure(FailureCode.UNSUPPORTED_ITEM, "Unknown item id " + itemId);
            }
            Item item = Registries.ITEM.get(id);
            int slot = findItemSlot(player, item);
            if (slot < 0) {
                return Outcome.failure(FailureCode.INSUFFICIENT_MATERIALS, "Item is not in inventory");
            }

            ItemStack stack = player.getInventory().main.get(slot);
            if (item instanceof ArmorItem armor) {
                EquipmentSlot equipmentSlot = armor.getSlotType();
                ItemStack toEquip = stack.split(1);
                ItemStack old = player.getEquippedStack(equipmentSlot).copy();
                player.equipStack(equipmentSlot, toEquip);
                if (!old.isEmpty() && !player.getInventory().insertStack(old)) {
                    return Outcome.failure(FailureCode.INVENTORY_FULL, "No room for replaced armor");
                }
            } else {
                moveToMainHand(player, slot);
            }
            return Outcome.success("Equipped " + itemId);
        }
    }

    /**
     * Deterministic combat loop: approach, strike every ten ticks, and flee at
     * critical health. There is no target-ranking or adaptive strategy.
     */
    private static final class AttackTask extends BaseTask {
        private final String targetType;
        private Entity target;
        private int ticks;
        private boolean pathing;

        private AttackTask(String commandId, String targetType) {
            super(commandId, "attack");
            this.targetType = targetType;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (player.getHealth() <= 6.0F) {
                pathfinder.cancel(player);
                if (target != null) {
                    Vec3d away = player.getPos().subtract(target.getPos()).normalize().multiply(0.45);
                    player.setVelocity(away.x, player.getVelocity().y, away.z);
                    player.velocityModified = true;
                }
                return Outcome.failure(FailureCode.FLED_FROM_COMBAT, "Deterministic combat safety threshold reached");
            }
            if (target == null) {
                target = findCombatTarget(player, targetType);
                if (target == null) {
                    return Outcome.failure(FailureCode.TARGET_NOT_FOUND, "No matching combat target within 24 blocks");
                }
            }
            if (!target.isAlive()) {
                JsonObject data = new JsonObject();
                data.addProperty("kills", 1);
                return Outcome.success("Combat target defeated", data);
            }

            double distance = player.distanceTo(target);
            if (distance > 3.2) {
                if (!pathing || ticks % 20 == 0) {
                    pathfinder.start(player, target.getBlockPos());
                    pathing = true;
                }
                IPathfinder.Status status = pathfinder.tick(player);
                if (status == IPathfinder.Status.FAILED) {
                    return Outcome.failure(FailureCode.TARGET_UNREACHABLE, "Combat target is unreachable");
                }
            } else {
                pathfinder.cancel(player);
                pathing = false;
                if (ticks % 10 == 0) {
                    player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
                    player.attack(target);
                    player.swingHand(Hand.MAIN_HAND);
                }
            }
            ticks++;
            if (ticks > 20 * 90) {
                return Outcome.failure(FailureCode.TARGET_UNREACHABLE, "Combat script exceeded 90 seconds");
            }
            return null;
        }
    }

    /**
     * Deterministic portal script. End frames are activated in coordinate order
     * before walking into the resulting portal; no search decision is made.
     */
    private static final class UsePortalTask extends BaseTask {
        private final String portalType;
        private BlockPos portal;
        private net.minecraft.registry.RegistryKey<World> startingDimension;
        private boolean started;
        private int ticks;

        private UsePortalTask(String commandId, String portalType) {
            super(commandId, "use_portal");
            this.portalType = portalType;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (!started) {
                started = true;
                startingDimension = player.getWorld().getRegistryKey();
                if (portalType.equals("end")) {
                    Outcome activationFailure = activateEndFrames(player);
                    if (activationFailure != null) {
                        return activationFailure;
                    }
                }
                Block portalBlock = portalType.equals("nether") ? Blocks.NETHER_PORTAL : Blocks.END_PORTAL;
                portal = findNearestBlock(player, 16, state -> state.isOf(portalBlock));
                if (portal == null) {
                    return Outcome.failure(FailureCode.PORTAL_NOT_FOUND, "No usable " + portalType + " portal within 16 blocks");
                }
                pathfinder.start(player, portal);
            }

            if (player.getWorld().getRegistryKey() != startingDimension) {
                return Outcome.success("Traversed " + portalType + " portal");
            }
            if (++ticks > 20 * 20) {
                return Outcome.failure(FailureCode.PORTAL_NOT_FOUND, "Portal traversal did not change dimension");
            }
            IPathfinder.Status status = pathfinder.tick(player);
            if (status == IPathfinder.Status.FAILED) {
                return Outcome.failure(FailureCode.TARGET_UNREACHABLE, "Portal could not be reached");
            }
            return null;
        }

        private static Outcome activateEndFrames(ServerPlayerEntity player) {
            List<BlockPos> emptyFrames = new ArrayList<>();
            BlockPos center = player.getBlockPos();
            for (BlockPos mutable : BlockPos.iterate(
                    center.add(-8, -4, -8),
                    center.add(8, 4, 8)
            )) {
                BlockState state = player.getServerWorld().getBlockState(mutable);
                if (state.isOf(Blocks.END_PORTAL_FRAME) && !state.get(EndPortalFrameBlock.EYE)) {
                    emptyFrames.add(mutable.toImmutable());
                }
            }
            emptyFrames.sort(Comparator
                    .comparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
            int eyes = countItem(player, Items.ENDER_EYE);
            if (eyes < emptyFrames.size()) {
                return Outcome.failure(FailureCode.NO_ENDER_EYE, "Not enough eyes to activate all nearby portal frames");
            }
            for (BlockPos frame : emptyFrames) {
                removeItems(player, Items.ENDER_EYE, 1);
                BlockState state = player.getServerWorld().getBlockState(frame);
                player.getServerWorld().setBlockState(
                        frame,
                        state.with(EndPortalFrameBlock.EYE, true),
                        Block.NOTIFY_ALL
                );
            }
            return null;
        }
    }

    /**
     * Fixed 10-obsidian, cornerless 4x5 frame in the X/Y plane. It validates
     * every target first, consumes exact materials, then ignites the interior.
     */
    private static final class BuildPortalTask extends BaseTask {
        private BlockPos origin;
        private boolean built;
        private int ticks;

        private BuildPortalTask(String commandId) {
            super(commandId, "build_portal");
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (!built) {
                if (countItem(player, Items.OBSIDIAN) < 10 || !hasItem(player, Items.FLINT_AND_STEEL)) {
                    return Outcome.failure(
                            FailureCode.INSUFFICIENT_MATERIALS,
                            "Portal script requires 10 obsidian and flint_and_steel"
                    );
                }
                origin = player.getBlockPos().add(2, 0, 0);
                List<BlockPos> frame = portalFrame(origin);
                for (BlockPos pos : frame) {
                    BlockState existing = player.getServerWorld().getBlockState(pos);
                    if (!existing.isReplaceable() && !existing.isOf(Blocks.OBSIDIAN)) {
                        return Outcome.failure(FailureCode.PORTAL_BUILD_FAILED, "Portal frame position is obstructed");
                    }
                }
                for (BlockPos pos : portalInterior(origin)) {
                    if (!player.getServerWorld().getBlockState(pos).isReplaceable()) {
                        return Outcome.failure(FailureCode.PORTAL_BUILD_FAILED, "Portal interior is obstructed");
                    }
                }

                removeItems(player, Items.OBSIDIAN, 10);
                for (BlockPos pos : frame) {
                    player.getServerWorld().setBlockState(pos, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
                }
                BlockPos ignition = origin.add(1, 1, 0);
                player.getServerWorld().setBlockState(
                        ignition,
                        AbstractFireBlock.getState(player.getServerWorld(), ignition),
                        Block.NOTIFY_ALL
                );
                damageOne(player, Items.FLINT_AND_STEEL);
                built = true;
            }

            if (findNearestBlock(player, 8, state -> state.isOf(Blocks.NETHER_PORTAL)) != null) {
                return Outcome.success("Built and ignited deterministic obsidian portal frame");
            }
            if (++ticks > 40) {
                return Outcome.failure(FailureCode.PORTAL_BUILD_FAILED, "Frame did not form a nether portal");
            }
            return null;
        }

        private static List<BlockPos> portalFrame(BlockPos origin) {
            List<BlockPos> result = new ArrayList<>();
            result.add(origin.add(1, 0, 0));
            result.add(origin.add(2, 0, 0));
            result.add(origin.add(1, 4, 0));
            result.add(origin.add(2, 4, 0));
            for (int y = 1; y <= 3; y++) {
                result.add(origin.add(0, y, 0));
                result.add(origin.add(3, y, 0));
            }
            return result;
        }

        private static List<BlockPos> portalInterior(BlockPos origin) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 1; x <= 2; x++) {
                for (int y = 1; y <= 3; y++) {
                    result.add(origin.add(x, y, 0));
                }
            }
            return result;
        }
    }

    /**
     * Fixed dragon procedure required by the specification:
     * phase 1 destroys every loaded end crystal; phase 2 attacks the dragon.
     * Tower climbing/ranged crystal handling remains a documented P1 TODO.
     */
    private static final class FightDragonTask extends BaseTask {
        private enum Phase {
            CRYSTALS,
            DRAGON
        }

        private Phase phase = Phase.CRYSTALS;
        private Entity target;
        private int ticks;
        private boolean pathing;

        private FightDragonTask(String commandId) {
            super(commandId, "fight_dragon");
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (player.getWorld().getRegistryKey() != World.END) {
                return Outcome.failure(FailureCode.DRAGON_FIGHT_ABORTED, "fight_dragon requires the end dimension");
            }
            if (player.getHealth() <= 6.0F) {
                return Outcome.failure(FailureCode.DRAGON_FIGHT_ABORTED, "Dragon script aborted at critical health");
            }

            if (target == null || !target.isAlive()) {
                target = null;
                pathing = false;
                if (phase == Phase.CRYSTALS) {
                    List<EndCrystalEntity> crystals = player.getServerWorld().getEntitiesByClass(
                            EndCrystalEntity.class,
                            player.getBoundingBox().expand(160.0),
                            Entity::isAlive
                    );
                    crystals.sort(Comparator.comparingDouble(player::squaredDistanceTo));
                    if (!crystals.isEmpty()) {
                        target = crystals.get(0);
                    } else {
                        phase = Phase.DRAGON;
                    }
                }
                if (phase == Phase.DRAGON && target == null) {
                    List<EnderDragonEntity> dragons = player.getServerWorld().getEntitiesByClass(
                            EnderDragonEntity.class,
                            player.getBoundingBox().expand(256.0),
                            Entity::isAlive
                    );
                    if (dragons.isEmpty()) {
                        return Outcome.success("No living dragon remains after crystal phase");
                    }
                    target = dragons.get(0);
                }
            }

            double reach = phase == Phase.CRYSTALS ? 4.5 : 5.0;
            if (player.distanceTo(target) <= reach) {
                pathfinder.cancel(player);
                pathing = false;
                if (ticks % 10 == 0) {
                    player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
                    player.attack(target);
                    player.swingHand(Hand.MAIN_HAND);
                }
            } else {
                if (!pathing || ticks % 20 == 0) {
                    pathfinder.start(player, target.getBlockPos());
                    pathing = true;
                }
                IPathfinder.Status status = pathfinder.tick(player);
                if (status == IPathfinder.Status.FAILED) {
                    return Outcome.failure(
                            FailureCode.DRAGON_FIGHT_ABORTED,
                            phase == Phase.CRYSTALS
                                    ? "P1 TODO: elevated crystal requires deterministic ranged/tower routine"
                                    : "Dragon could not be reached during attack phase"
                    );
                }
            }
            if (++ticks > 20 * 300) {
                return Outcome.failure(FailureCode.DRAGON_FIGHT_ABORTED, "Dragon script exceeded five minutes");
            }
            return null;
        }
    }

    private static Entity findCombatTarget(ServerPlayerEntity player, String targetType) {
        Box box = player.getBoundingBox().expand(24.0);
        Predicate<Entity> predicate;
        if (targetType.equals("nearest_hostile")) {
            predicate = entity -> entity instanceof HostileEntity && entity.isAlive();
        } else {
            predicate = entity -> entity.isAlive()
                    && Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(targetType);
        }
        return player.getServerWorld().getOtherEntities(player, box, predicate)
                .stream()
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
    }

    private static BlockPos findNearestBlock(
            ServerPlayerEntity player,
            int radius,
            Predicate<BlockState> predicate
    ) {
        BlockPos center = player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.iterate(
                center.add(-radius, -radius, -radius),
                center.add(radius, radius, radius)
        )) {
            if (!player.getServerWorld().isChunkLoaded(mutable)
                    || !predicate.test(player.getServerWorld().getBlockState(mutable))) {
                continue;
            }
            double distance = mutable.getSquaredDistance(player.getPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mutable.toImmutable();
            }
        }
        return best;
    }

    private static boolean hasCraftingTable(ServerPlayerEntity player) {
        if (hasItem(player, Items.CRAFTING_TABLE)) {
            return true;
        }
        BlockPos center = player.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(center.add(-4, -4, -4), center.add(4, 4, 4))) {
            if (player.getServerWorld().getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canFit(ServerPlayerEntity player, Item item, int count) {
        int capacity = 0;
        for (ItemStack stack : player.getInventory().main) {
            if (stack.isEmpty()) {
                capacity += item.getMaxCount();
            } else if (stack.isOf(item)) {
                capacity += stack.getMaxCount() - stack.getCount();
            }
            if (capacity >= count) {
                return true;
            }
        }
        return false;
    }

    private static int findItemSlot(ServerPlayerEntity player, Item item) {
        for (int slot = 0; slot < player.getInventory().main.size(); slot++) {
            if (player.getInventory().main.get(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean hasItem(ServerPlayerEntity player, Item item) {
        return findItemSlot(player, item) >= 0;
    }

    private static int countItem(ServerPlayerEntity player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().main) {
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeItems(ServerPlayerEntity player, Item item, int count) {
        int remaining = count;
        for (int slot = 0; slot < player.getInventory().main.size() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().main.get(slot);
            if (!stack.isOf(item)) {
                continue;
            }
            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
        }
    }

    private static void damageOne(ServerPlayerEntity player, Item item) {
        int slot = findItemSlot(player, item);
        if (slot < 0) {
            return;
        }
        ItemStack stack = player.getInventory().main.get(slot);
        stack.damage(1, player, ignored -> {
        });
    }

    private static void moveToMainHand(ServerPlayerEntity player, int slot) {
        if (slot < 9) {
            player.getInventory().selectedSlot = slot;
            return;
        }
        int selected = player.getInventory().selectedSlot;
        ItemStack selectedStack = player.getInventory().getStack(selected);
        ItemStack requested = player.getInventory().getStack(slot);
        player.getInventory().setStack(selected, requested);
        player.getInventory().setStack(slot, selectedStack);
    }
}
