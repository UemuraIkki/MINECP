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
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.pattern.BlockPattern;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.phase.PhaseType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
            case "smelt" -> new SmeltTask(
                    command.commandId,
                    args.get("item").getAsString(),
                    args.get("count").getAsInt()
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
            case "throw_ender_eye" -> new ThrowEnderEyeTask(command.commandId);
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

        private static Outcome failure(FailureCode code, String detail, JsonObject data) {
            return new Outcome(false, code, detail, data);
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

    /**
     * Deterministic furnace workflow. Recipe choice, input allocation, and fuel
     * allocation are sorted by registry id; the task never chooses resources
     * based on inferred future value.
     */
    private static final class SmeltTask extends BaseTask {
        private record Load(Item item, int count) {
        }

        private final String itemId;
        private final int requestedCount;
        private final List<Load> inputLoads = new ArrayList<>();
        private final List<Load> fuelLoads = new ArrayList<>();
        private final List<BlockPos> workstations = new ArrayList<>();
        private Item targetItem;
        private BlockPos furnacePos;
        private int expectedOutput;
        private int collectedOutput;
        private int inputLoadIndex;
        private int fuelLoadIndex;
        private int ticks;
        private int maximumTicks;
        private int workstationIndex;
        private boolean prepared;
        private boolean pathing;
        private boolean shuffling;

        private SmeltTask(String commandId, String itemId, int requestedCount) {
            super(commandId, "smelt");
            this.itemId = itemId;
            this.requestedCount = requestedCount;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (!prepared) {
                prepared = true;
                Outcome preparationFailure = prepare(player);
                if (preparationFailure != null) {
                    return preparationFailure;
                }
            }

            if (player.getPos().squaredDistanceTo(Vec3d.ofCenter(furnacePos)) > 25.0) {
                if (!pathing) {
                    pathfinder.start(player, furnacePos);
                    pathing = true;
                }
                IPathfinder.Status status = pathfinder.tick(player);
                if (status == IPathfinder.Status.FAILED || status == IPathfinder.Status.IDLE) {
                    return Outcome.failure(FailureCode.SMELTING_FAILED, "The prepared furnace could not be reached");
                }
                return null;
            }
            if (pathing) {
                pathfinder.cancel(player);
                pathing = false;
            }
            if (shuffling) {
                IPathfinder.Status status = pathfinder.tick(player);
                if (status == IPathfinder.Status.FAILED
                        || status == IPathfinder.Status.REACHED
                        || status == IPathfinder.Status.IDLE) {
                    pathfinder.cancel(player);
                    shuffling = false;
                }
            } else if (ticks > 0 && ticks % 600 == 0 && workstations.size() > 1) {
                BlockPos workstation = workstations.get(workstationIndex++ % workstations.size());
                if (player.getBlockPos().getSquaredDistance(workstation) > 1.0) {
                    pathfinder.start(player, workstation);
                    shuffling = true;
                }
            }

            if (!(player.getServerWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
                return Outcome.failure(FailureCode.SMELTING_FAILED, "The furnace was removed during smelting");
            }

            Outcome collectionFailure = collectOutput(player, furnace);
            if (collectionFailure != null) {
                return collectionFailure;
            }
            if (collectedOutput >= expectedOutput) {
                furnace.getRecipesUsedAndDropExperience(player.getServerWorld(), player.getPos());
                return Outcome.success("Smelted and collected " + collectedOutput + " of " + itemId);
            }

            if (furnace.getStack(0).isEmpty() && inputLoadIndex < inputLoads.size()) {
                Load load = inputLoads.get(inputLoadIndex++);
                if (countItem(player, load.item()) < load.count()) {
                    return Outcome.failure(
                            FailureCode.SMELTING_FAILED,
                            "Reserved smelting input disappeared from inventory"
                    );
                }
                removeItems(player, load.item(), load.count());
                furnace.setStack(0, new ItemStack(load.item(), load.count()));
            }
            if (!furnace.getStack(1).isEmpty()
                    && !AbstractFurnaceBlockEntity.canUseAsFuel(furnace.getStack(1))
                    && furnace.getStack(1).isOf(Items.BUCKET)) {
                returnToPlayer(player, furnace.removeStack(1));
            }
            if (furnace.getStack(1).isEmpty() && fuelLoadIndex < fuelLoads.size()) {
                Load load = fuelLoads.get(fuelLoadIndex++);
                if (countItem(player, load.item()) < load.count()) {
                    return Outcome.failure(FailureCode.NO_FUEL, "Reserved furnace fuel disappeared from inventory");
                }
                removeItems(player, load.item(), load.count());
                furnace.setStack(1, new ItemStack(load.item(), load.count()));
            }
            furnace.markDirty();

            if (furnace.getStack(0).isEmpty()
                    && inputLoadIndex >= inputLoads.size()
                    && furnace.getStack(2).isEmpty()
                    && collectedOutput < expectedOutput) {
                return Outcome.failure(FailureCode.SMELTING_FAILED, "Furnace stopped before producing the requested output");
            }
            if (furnace.getStack(1).isEmpty()
                    && fuelLoadIndex >= fuelLoads.size()
                    && !furnace.getStack(0).isEmpty()) {
                return Outcome.failure(FailureCode.SMELTING_FAILED, "Furnace exhausted the reserved fuel unexpectedly");
            }
            if (++ticks > maximumTicks) {
                return Outcome.failure(FailureCode.SMELTING_FAILED, "Furnace did not complete within its recipe-based timeout");
            }
            return null;
        }

        private Outcome prepare(ServerPlayerEntity player) {
            Identifier id;
            try {
                id = new Identifier(itemId);
            } catch (RuntimeException e) {
                return Outcome.failure(FailureCode.INVALID_ARGUMENTS, "Invalid smelting output item id");
            }
            if (!Registries.ITEM.containsId(id)) {
                return Outcome.failure(FailureCode.UNSUPPORTED_ITEM, "Unknown item id " + itemId);
            }
            targetItem = Registries.ITEM.get(id);

            List<AbstractCookingRecipe> recipes = new ArrayList<>(
                    player.server.getRecipeManager().listAllOfType(RecipeType.SMELTING)
            );
            recipes.removeIf(recipe ->
                    !recipe.getOutput(player.getServerWorld().getRegistryManager()).isOf(targetItem));
            recipes.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
            if (recipes.isEmpty()) {
                return Outcome.failure(FailureCode.UNSUPPORTED_ITEM, "No smelting recipe produces " + itemId);
            }

            furnacePos = findEmptyFurnace(player);
            if (furnacePos == null) {
                Outcome placementFailure = placeFurnace(player);
                if (placementFailure != null) {
                    return placementFailure;
                }
            }

            Map<Item, Integer> reservedInputs = new HashMap<>();
            int cookTicks = 0;
            for (AbstractCookingRecipe recipe : recipes) {
                if (expectedOutput >= requestedCount || recipe.getIngredients().isEmpty()) {
                    break;
                }
                Ingredient ingredient = recipe.getIngredients().get(0);
                ItemStack recipeOutput = recipe.getOutput(player.getServerWorld().getRegistryManager());
                if (ingredient.isEmpty() || recipeOutput.isEmpty()) {
                    continue;
                }
                List<Item> matchingItems = player.getInventory().main.stream()
                        .filter(stack -> !stack.isEmpty() && ingredient.test(stack))
                        .map(ItemStack::getItem)
                        .distinct()
                        .sorted(Comparator.comparing(item -> Registries.ITEM.getId(item).toString()))
                        .toList();
                for (Item inputItem : matchingItems) {
                    int available = countItem(player, inputItem) - reservedInputs.getOrDefault(inputItem, 0);
                    if (available <= 0) {
                        continue;
                    }
                    int neededInputs = (requestedCount - expectedOutput + recipeOutput.getCount() - 1)
                            / recipeOutput.getCount();
                    int allocated = Math.min(available, neededInputs);
                    appendLoads(inputLoads, inputItem, allocated);
                    reservedInputs.merge(inputItem, allocated, Integer::sum);
                    expectedOutput += allocated * recipeOutput.getCount();
                    cookTicks += allocated * recipe.getCookTime();
                    if (expectedOutput >= requestedCount) {
                        break;
                    }
                }
            }
            if (expectedOutput < requestedCount) {
                return Outcome.failure(
                        FailureCode.INSUFFICIENT_MATERIALS,
                        "Inventory does not contain enough valid raw material for " + itemId
                );
            }
            if (!canFitAfterRemoval(player, targetItem, expectedOutput, reservedInputs)) {
                return Outcome.failure(FailureCode.INVENTORY_FULL, "Smelted output does not fit in inventory");
            }

            Map<Item, Integer> fuelTimes = AbstractFurnaceBlockEntity.createFuelTimeMap();
            List<Item> fuels = player.getInventory().main.stream()
                    .filter(stack -> !stack.isEmpty()
                            && fuelTimes.getOrDefault(stack.getItem(), 0) > 0)
                    .map(ItemStack::getItem)
                    .distinct()
                    .sorted(Comparator
                            .<Item>comparingInt(item -> fuelTimes.getOrDefault(item, 0))
                            .reversed()
                            .thenComparing(item -> Registries.ITEM.getId(item).toString()))
                    .toList();
            int burnTicksReserved = 0;
            for (Item fuel : fuels) {
                int burnPerItem = fuelTimes.getOrDefault(fuel, 0);
                int available = countItem(player, fuel) - reservedInputs.getOrDefault(fuel, 0);
                int stillNeeded = cookTicks - burnTicksReserved;
                if (available <= 0 || stillNeeded <= 0) {
                    continue;
                }
                int allocated = Math.min(available, (stillNeeded + burnPerItem - 1) / burnPerItem);
                appendLoads(fuelLoads, fuel, allocated);
                burnTicksReserved += allocated * burnPerItem;
            }
            if (burnTicksReserved < cookTicks) {
                return Outcome.failure(FailureCode.NO_FUEL, "Inventory fuel cannot cover the requested smelting time");
            }

            workstations.addAll(findFurnaceWorkstations(player, furnacePos));
            maximumTicks = cookTicks + 20 * 30;
            return null;
        }

        private Outcome placeFurnace(ServerPlayerEntity player) {
            boolean hasFurnace = hasItem(player, Items.FURNACE);
            if (!hasFurnace && countItem(player, Items.COBBLESTONE) < 8) {
                return Outcome.failure(
                        FailureCode.INSUFFICIENT_MATERIALS,
                        "No furnace is available and eight cobblestone cannot be supplied"
                );
            }
            BlockPos placement = findFurnacePlacement(player);
            if (placement == null) {
                return Outcome.failure(FailureCode.SMELTING_FAILED, "No unobstructed furnace placement is reachable");
            }
            if (hasFurnace) {
                removeItems(player, Items.FURNACE, 1);
            } else {
                removeItems(player, Items.COBBLESTONE, 8);
            }
            if (!player.getServerWorld().setBlockState(
                    placement,
                    Blocks.FURNACE.getDefaultState(),
                    Block.NOTIFY_ALL
            )) {
                return Outcome.failure(FailureCode.SMELTING_FAILED, "World rejected furnace placement");
            }
            furnacePos = placement;
            return null;
        }

        private Outcome collectOutput(ServerPlayerEntity player, AbstractFurnaceBlockEntity furnace) {
            ItemStack output = furnace.getStack(2);
            if (output.isEmpty()) {
                return null;
            }
            if (!output.isOf(targetItem)) {
                return Outcome.failure(FailureCode.SMELTING_FAILED, "Furnace produced an unexpected output item");
            }
            int count = output.getCount();
            ItemStack removed = furnace.removeStack(2);
            if (!player.getInventory().insertStack(removed) || !removed.isEmpty()) {
                if (!removed.isEmpty()) {
                    furnace.setStack(2, removed);
                }
                return Outcome.failure(FailureCode.INVENTORY_FULL, "Could not collect furnace output");
            }
            collectedOutput += count;
            return null;
        }

        @Override
        public void cancel(ServerPlayerEntity player, IPathfinder pathfinder) {
            pathfinder.cancel(player);
            if (furnacePos == null
                    || !(player.getServerWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace)) {
                return;
            }
            returnToPlayer(player, furnace.removeStack(0));
            returnToPlayer(player, furnace.removeStack(1));
            returnToPlayer(player, furnace.removeStack(2));
            furnace.markDirty();
        }

        private static void appendLoads(List<Load> loads, Item item, int count) {
            int remaining = count;
            while (remaining > 0) {
                int load = Math.min(remaining, item.getMaxCount());
                loads.add(new Load(item, load));
                remaining -= load;
            }
        }

        private static boolean canFitAfterRemoval(
                ServerPlayerEntity player,
                Item output,
                int count,
                Map<Item, Integer> removals
        ) {
            Map<Item, Integer> remainingRemovals = new HashMap<>(removals);
            int capacity = 0;
            for (ItemStack stack : player.getInventory().main) {
                if (stack.isEmpty()) {
                    capacity += output.getMaxCount();
                } else {
                    int removed = Math.min(
                            stack.getCount(),
                            remainingRemovals.getOrDefault(stack.getItem(), 0)
                    );
                    remainingRemovals.computeIfPresent(stack.getItem(), (item, amount) -> amount - removed);
                    int remaining = stack.getCount() - removed;
                    if (remaining == 0) {
                        capacity += output.getMaxCount();
                    } else if (stack.isOf(output)) {
                        capacity += stack.getMaxCount() - remaining;
                    }
                }
                if (capacity >= count) {
                    return true;
                }
            }
            return false;
        }

        private static BlockPos findEmptyFurnace(ServerPlayerEntity player) {
            BlockPos center = player.getBlockPos();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (BlockPos mutable : BlockPos.iterate(center.add(-16, -16, -16), center.add(16, 16, 16))) {
                if (!player.getServerWorld().isChunkLoaded(mutable)
                        || !(player.getServerWorld().getBlockEntity(mutable) instanceof AbstractFurnaceBlockEntity furnace)
                        || !furnace.getStack(0).isEmpty()
                        || !furnace.getStack(1).isEmpty()
                        || !furnace.getStack(2).isEmpty()) {
                    continue;
                }
                double distance = mutable.getSquaredDistance(player.getPos());
                if (distance < bestDistance) {
                    best = mutable.toImmutable();
                    bestDistance = distance;
                }
            }
            return best;
        }

        private static BlockPos findFurnacePlacement(ServerPlayerEntity player) {
            ServerWorld world = player.getServerWorld();
            BlockPos center = player.getBlockPos();
            List<BlockPos> candidates = List.of(
                    center.add(2, 0, 0),
                    center.add(-2, 0, 0),
                    center.add(0, 0, 2),
                    center.add(0, 0, -2),
                    center.add(1, 0, 1),
                    center.add(-1, 0, 1),
                    center.add(1, 0, -1),
                    center.add(-1, 0, -1)
            );
            for (BlockPos candidate : candidates) {
                if (world.getBlockState(candidate).isReplaceable()
                        && world.getBlockState(candidate.down()).isSolidBlock(world, candidate.down())
                        && world.getWorldBorder().contains(candidate)
                        && world.canPlayerModifyAt(player, candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private static List<BlockPos> findFurnaceWorkstations(
                ServerPlayerEntity player,
                BlockPos furnace
        ) {
            ServerWorld world = player.getServerWorld();
            List<BlockPos> result = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos feet = furnace.add(dx, 0, dz);
                    if (world.getBlockState(feet.down()).isSolidBlock(world, feet.down())
                            && world.getBlockState(feet).isReplaceable()
                            && world.getBlockState(feet.up()).isReplaceable()) {
                        result.add(feet);
                    }
                }
            }
            result.sort(Comparator
                    .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(player.getPos()))
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
            return result;
        }

        private static void returnToPlayer(ServerPlayerEntity player, ItemStack stack) {
            if (stack.isEmpty()) {
                return;
            }
            player.getInventory().insertStack(stack);
            if (!stack.isEmpty()) {
                player.dropItem(stack, false);
            }
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
     * Deterministic single-target combat loop: resolve, equip the numerically
     * strongest carried melee weapon, approach, attack on cooldown, and
     * disengage at the fixed six-health threshold.
     */
    private static final class AttackTask extends BaseTask {
        private enum Phase {
            COMBAT,
            FLEE
        }

        private final String targetType;
        private Entity target;
        private int ticks;
        private int fleeTicks;
        private boolean pathing;
        private boolean weaponSelected;
        private Phase phase = Phase.COMBAT;

        private AttackTask(String commandId, String targetType) {
            super(commandId, "attack");
            this.targetType = targetType;
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (phase == Phase.FLEE) {
                return tickFlee(player, pathfinder);
            }
            if (player.getHealth() <= 6.0F) {
                phase = Phase.FLEE;
                pathfinder.cancel(player);
                pathing = false;
                return tickFlee(player, pathfinder);
            }
            if (target == null) {
                target = findCombatTarget(player, targetType);
                if (target == null) {
                    return Outcome.failure(
                            FailureCode.TARGET_NOT_FOUND,
                            "No matching combat target within 32 blocks",
                            attackData(0)
                    );
                }
            }
            if (!target.isAlive()) {
                return Outcome.success("Combat target defeated", attackData(1));
            }
            if (!weaponSelected) {
                equipBestWeapon(player);
                weaponSelected = true;
            }

            double distance = player.distanceTo(target);
            if (distance > 3.2 || !player.canSee(target)) {
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
                if (player.getAttackCooldownProgress(0.5F) >= 0.9F) {
                    player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
                    player.attack(target);
                    player.swingHand(Hand.MAIN_HAND);
                }
            }
            ticks++;
            if (ticks > 20 * 90) {
                return Outcome.failure(
                        FailureCode.TARGET_UNREACHABLE,
                        "Combat script exceeded 90 seconds",
                        attackData(0)
                );
            }
            return null;
        }

        private Outcome tickFlee(ServerPlayerEntity player, IPathfinder pathfinder) {
            Vec3d away = target == null
                    ? player.getRotationVector().multiply(-1.0)
                    : player.getPos().subtract(target.getPos());
            if (away.horizontalLengthSquared() < 0.0001) {
                away = new Vec3d(1.0, 0.0, 0.0);
            }
            Vec3d horizontal = new Vec3d(away.x, 0.0, away.z).normalize();
            if (!pathing) {
                BlockPos escape = BlockPos.ofFloored(player.getPos().add(horizontal.multiply(10.0)));
                pathfinder.start(player, escape);
                pathing = true;
            }
            IPathfinder.Status status = pathfinder.tick(player);
            if (status == IPathfinder.Status.FAILED || status == IPathfinder.Status.IDLE) {
                player.setVelocity(horizontal.x * 0.35, player.getVelocity().y, horizontal.z * 0.35);
                player.velocityModified = true;
            }
            fleeTicks++;
            if ((target != null && player.distanceTo(target) >= 10.0) || fleeTicks >= 40) {
                return Outcome.failure(
                        FailureCode.FLED_FROM_COMBAT,
                        "Disengaged after reaching the fixed critical-health threshold",
                        attackData(0)
                );
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
        private boolean entered;
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
                Block portalBlock = portalType.equals("nether") ? Blocks.NETHER_PORTAL : Blocks.END_PORTAL;
                portal = findNearestBlock(player, 16, state -> state.isOf(portalBlock));
                if (portal == null && portalType.equals("end")) {
                    Outcome activationFailure = activateEndFrames(player);
                    if (activationFailure != null) {
                        return activationFailure;
                    }
                    portal = findNearestBlock(player, 16, state -> state.isOf(Blocks.END_PORTAL));
                }
                if (portal == null) {
                    return Outcome.failure(FailureCode.PORTAL_NOT_FOUND, "No usable " + portalType + " portal within 16 blocks");
                }
                pathfinder.start(player, portal);
            }

            if (!player.getWorld().getRegistryKey().equals(startingDimension)) {
                return Outcome.success("Traversed " + portalType + " portal");
            }
            if (++ticks > 20 * 20) {
                return Outcome.failure(FailureCode.PORTAL_NOT_FOUND, "Portal traversal did not change dimension");
            }

            if (!entered) {
                if (player.getPos().squaredDistanceTo(Vec3d.ofCenter(portal)) > 9.0) {
                    IPathfinder.Status status = pathfinder.tick(player);
                    if (status == IPathfinder.Status.FAILED || status == IPathfinder.Status.IDLE) {
                        return Outcome.failure(FailureCode.TARGET_UNREACHABLE, "Portal could not be reached");
                    }
                    return null;
                }
                pathfinder.cancel(player);
                player.requestTeleport(portal.getX() + 0.5, portal.getY() + 0.1, portal.getZ() + 0.5);
                player.setVelocity(Vec3d.ZERO);
                entered = true;
            } else if (ticks % 20 == 0
                    && player.getServerWorld().getBlockState(player.getBlockPos()).getBlock()
                    != (portalType.equals("nether") ? Blocks.NETHER_PORTAL : Blocks.END_PORTAL)) {
                // Nether portals require a fixed dwell time; keep the player
                // inside the already-selected portal without re-planning.
                player.requestTeleport(portal.getX() + 0.5, portal.getY() + 0.1, portal.getZ() + 0.5);
            }
            return null;
        }

        private static Outcome activateEndFrames(ServerPlayerEntity player) {
            List<BlockPos> emptyFrames = new ArrayList<>();
            List<BlockPos> allFrames = new ArrayList<>();
            BlockPos center = player.getBlockPos();
            for (BlockPos mutable : BlockPos.iterate(
                    center.add(-16, -16, -16),
                    center.add(16, 16, 16)
            )) {
                BlockState state = player.getServerWorld().getBlockState(mutable);
                if (state.isOf(Blocks.END_PORTAL_FRAME)) {
                    BlockPos frame = mutable.toImmutable();
                    allFrames.add(frame);
                    if (!state.get(EndPortalFrameBlock.EYE)) {
                        emptyFrames.add(frame);
                    }
                }
            }
            if (allFrames.isEmpty()) {
                return Outcome.failure(FailureCode.PORTAL_NOT_FOUND, "No end portal frames exist within 16 blocks");
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
            BlockPattern.Result completed = null;
            for (BlockPos frame : allFrames) {
                completed = EndPortalFrameBlock.getCompletedFramePattern()
                        .searchAround(player.getServerWorld(), frame);
                if (completed != null) {
                    break;
                }
            }
            if (completed == null) {
                return Outcome.failure(FailureCode.PORTAL_NOT_FOUND, "Nearby frames do not form a completed end portal");
            }
            BlockPos interiorOrigin = completed.getFrontTopLeft().add(-3, 0, -3);
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    player.getServerWorld().setBlockState(
                            interiorOrigin.add(x, 0, z),
                            Blocks.END_PORTAL.getDefaultState(),
                            Block.NOTIFY_ALL
                    );
                }
            }
            return null;
        }
    }

    /**
     * Fixed cornerless 4x5 portal script. It uses ten carried obsidian first;
     * otherwise it deterministically casts the same ten positions from still
     * lava sources/lava buckets plus a reusable water bucket.
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
                if (!hasItem(player, Items.FLINT_AND_STEEL)) {
                    return Outcome.failure(
                            FailureCode.INSUFFICIENT_MATERIALS,
                            "Portal construction requires flint_and_steel"
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

                if (countItem(player, Items.OBSIDIAN) >= 10) {
                    removeItems(player, Items.OBSIDIAN, 10);
                    for (BlockPos pos : frame) {
                        player.getServerWorld().setBlockState(
                                pos,
                                Blocks.OBSIDIAN.getDefaultState(),
                                Block.NOTIFY_ALL
                        );
                    }
                } else {
                    Outcome castingFailure = castFrameWithBuckets(player, frame);
                    if (castingFailure != null) {
                        return castingFailure;
                    }
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

        private static Outcome castFrameWithBuckets(ServerPlayerEntity player, List<BlockPos> frame) {
            if (!hasItem(player, Items.WATER_BUCKET)) {
                return Outcome.failure(
                        FailureCode.INSUFFICIENT_MATERIALS,
                        "Bucket casting requires a water_bucket"
                );
            }
            ServerWorld world = player.getServerWorld();
            List<BlockPos> lavaSources = new ArrayList<>();
            BlockPos center = player.getBlockPos();
            for (BlockPos mutable : BlockPos.iterate(
                    center.add(-16, -16, -16),
                    center.add(16, 16, 16)
            )) {
                BlockState state = world.getBlockState(mutable);
                if (state.isOf(Blocks.LAVA) && state.getFluidState().isStill()) {
                    lavaSources.add(mutable.toImmutable());
                }
            }
            lavaSources.sort(Comparator
                    .comparingDouble((BlockPos pos) -> pos.getSquaredDistance(player.getPos()))
                    .thenComparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
            int lavaBuckets = countItem(player, Items.LAVA_BUCKET);
            if (lavaBuckets + lavaSources.size() < frame.size()) {
                return Outcome.failure(
                        FailureCode.INSUFFICIENT_MATERIALS,
                        "Bucket casting requires ten lava sources or lava buckets within 16 blocks"
                );
            }

            // This atomic server-side sequence is the deterministic equivalent
            // of repeatedly placing water, collecting a source, and casting one
            // frame block. It deliberately performs no terrain/resource choice.
            BlockPos castingWater = frame.get(0).add(0, 1, 0);
            if (!world.setBlockState(castingWater, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL)) {
                return Outcome.failure(FailureCode.PORTAL_BUILD_FAILED, "Could not place casting water");
            }
            int sourceIndex = 0;
            for (BlockPos framePos : frame) {
                if (lavaBuckets > 0) {
                    removeItems(player, Items.LAVA_BUCKET, 1);
                    ItemStack bucket = new ItemStack(Items.BUCKET);
                    player.getInventory().insertStack(bucket);
                    if (!bucket.isEmpty()) {
                        player.dropItem(bucket, false);
                    }
                    lavaBuckets--;
                } else {
                    BlockPos source = lavaSources.get(sourceIndex++);
                    if (!world.getBlockState(source).isOf(Blocks.LAVA)
                            || !world.getBlockState(source).getFluidState().isStill()) {
                        world.setBlockState(castingWater, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                        return Outcome.failure(
                                FailureCode.PORTAL_BUILD_FAILED,
                                "A reserved lava source changed during bucket casting"
                        );
                    }
                    world.setBlockState(source, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
                if (!world.setBlockState(framePos, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL)) {
                    world.setBlockState(castingWater, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    return Outcome.failure(FailureCode.PORTAL_BUILD_FAILED, "A cast frame block was rejected");
                }
            }
            if (world.getBlockState(castingWater).isOf(Blocks.WATER)) {
                world.setBlockState(castingWater, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
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
     * Throws a real eye entity toward the server-located stronghold, samples
     * its actual flight vector, then observes whether a new eye item drops.
     */
    private static final class ThrowEnderEyeTask extends BaseTask {
        private EyeOfEnderEntity eye;
        private Vec3d launchPosition;
        private Vec3d lastEyePosition;
        private Vec3d direction;
        private Vec3d fallbackDirection;
        private Set<UUID> existingEyeDrops;
        private int ticks;
        private int ticksAfterRemoval;
        private boolean launched;

        private ThrowEnderEyeTask(String commandId) {
            super(commandId, "throw_ender_eye");
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (!launched) {
                launched = true;
                if (!hasItem(player, Items.ENDER_EYE)) {
                    return Outcome.failure(FailureCode.NO_ENDER_EYE, "No ender eye is available to throw");
                }
                ServerWorld world = player.getServerWorld();
                BlockPos stronghold = world.locateStructure(
                        StructureTags.EYE_OF_ENDER_LOCATED,
                        player.getBlockPos(),
                        100,
                        false
                );
                if (stronghold == null) {
                    return Outcome.failure(FailureCode.TARGET_NOT_FOUND, "No eye-locatable stronghold was found");
                }

                existingEyeDrops = new HashSet<>();
                for (ItemEntity itemEntity : world.getEntitiesByClass(
                        ItemEntity.class,
                        player.getBoundingBox().expand(192.0),
                        entity -> entity.getStack().isOf(Items.ENDER_EYE)
                )) {
                    existingEyeDrops.add(itemEntity.getUuid());
                }
                launchPosition = new Vec3d(player.getX(), player.getEyeY() - 0.5, player.getZ());
                lastEyePosition = launchPosition;
                fallbackDirection = Vec3d.ofCenter(stronghold).subtract(launchPosition).normalize();
                eye = new EyeOfEnderEntity(world, launchPosition.x, launchPosition.y, launchPosition.z);
                eye.setItem(new ItemStack(Items.ENDER_EYE));
                eye.initTargetPos(stronghold);
                if (!world.spawnEntity(eye)) {
                    return Outcome.failure(FailureCode.INTERNAL_ERROR, "Server rejected the ender eye entity");
                }
                removeItems(player, Items.ENDER_EYE, 1);
                player.swingHand(Hand.MAIN_HAND);
                world.syncWorldEvent(
                        null,
                        WorldEvents.EYE_OF_ENDER_LAUNCHES,
                        player.getBlockPos(),
                        0
                );
            }

            if (!eye.isRemoved()) {
                lastEyePosition = eye.getPos();
                Vec3d displacement = lastEyePosition.subtract(launchPosition);
                if (direction == null && displacement.lengthSquared() > 0.0001) {
                    direction = displacement.normalize();
                }
            } else if (++ticksAfterRemoval >= 5) {
                if (direction == null) {
                    Vec3d velocity = eye.getVelocity();
                    direction = velocity.lengthSquared() > 0.0001 ? velocity.normalize() : fallbackDirection;
                }
                boolean survived = player.getServerWorld().getEntitiesByClass(
                                ItemEntity.class,
                                new Box(lastEyePosition, lastEyePosition).expand(8.0),
                                entity -> entity.getStack().isOf(Items.ENDER_EYE)
                                        && !existingEyeDrops.contains(entity.getUuid())
                        )
                        .stream()
                        .findAny()
                        .isPresent();
                return Outcome.success("Ender eye flight completed", eyeData(direction, survived));
            }

            if (++ticks > 20 * 10) {
                return Outcome.failure(FailureCode.INTERNAL_ERROR, "Ender eye did not finish its flight within ten seconds");
            }
            return null;
        }

        private static JsonObject eyeData(Vec3d direction, boolean survived) {
            JsonObject vector = new JsonObject();
            vector.addProperty("x", direction.x);
            vector.addProperty("y", direction.y);
            vector.addProperty("z", direction.z);
            JsonObject data = new JsonObject();
            data.add("direction", vector);
            data.addProperty("eye_survived", survived);
            return data;
        }
    }

    /**
     * Fixed dragon procedure required by the specification. It always clears
     * crystals first (bow when visible, melee approach otherwise), then follows
     * four fixed dodge waypoints until a perch phase permits melee attacks.
     */
    private static final class FightDragonTask extends BaseTask {
        private enum Phase {
            CRYSTALS,
            DRAGON
        }

        private Phase phase = Phase.CRYSTALS;
        private EndCrystalEntity crystal;
        private EnderDragonEntity dragon;
        private int ticks;
        private int nextDodgeTick;
        private int dodgeIndex;
        private boolean pathing;
        private boolean weaponSelected;

        private FightDragonTask(String commandId) {
            super(commandId, "fight_dragon");
        }

        @Override
        public Outcome tick(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (!player.getWorld().getRegistryKey().equals(World.END)) {
                return Outcome.failure(FailureCode.DRAGON_FIGHT_ABORTED, "fight_dragon requires the end dimension");
            }
            if (player.isDead() || player.getHealth() <= 0.0F) {
                return Outcome.failure(FailureCode.AGENT_DIED, "Agent died during the dragon fight");
            }
            if (player.getHealth() <= 6.0F) {
                return Outcome.failure(FailureCode.DRAGON_FIGHT_ABORTED, "Dragon script aborted at critical health");
            }
            if (++ticks > 20 * 600) {
                return Outcome.failure(FailureCode.DRAGON_FIGHT_ABORTED, "Dragon script exceeded ten minutes");
            }

            if (phase == Phase.CRYSTALS) {
                Outcome crystalOutcome = tickCrystals(player, pathfinder);
                if (crystalOutcome != null || phase == Phase.CRYSTALS) {
                    return crystalOutcome;
                }
            }
            return tickDragon(player, pathfinder);
        }

        private Outcome tickCrystals(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (crystal == null || !crystal.isAlive()) {
                crystal = null;
                pathfinder.cancel(player);
                pathing = false;
                List<EndCrystalEntity> crystals = player.getServerWorld().getEntitiesByClass(
                        EndCrystalEntity.class,
                        player.getBoundingBox().expand(192.0),
                        Entity::isAlive
                );
                crystals.sort(Comparator
                        .comparingDouble((EndCrystalEntity entity) -> player.squaredDistanceTo(entity))
                        .thenComparingInt(Entity::getId));
                if (crystals.isEmpty()) {
                    phase = Phase.DRAGON;
                    weaponSelected = false;
                    return null;
                }
                crystal = crystals.get(0);
            }

            double distance = player.distanceTo(crystal);
            if (distance > 4.5
                    && hasItem(player, Items.BOW)
                    && hasItem(player, Items.ARROW)
                    && player.canSee(crystal)) {
                pathfinder.cancel(player);
                pathing = false;
                if (ticks % 15 == 0 && !shootArrow(player, crystal)) {
                    return Outcome.failure(
                            FailureCode.DRAGON_FIGHT_ABORTED,
                            "Server rejected a deterministic crystal arrow"
                    );
                }
                return null;
            }

            if (distance <= 4.5 && player.canSee(crystal)) {
                pathfinder.cancel(player);
                pathing = false;
                if (!weaponSelected) {
                    equipBestWeapon(player);
                    weaponSelected = true;
                }
                if (player.getAttackCooldownProgress(0.5F) >= 0.9F) {
                    player.lookAt(
                            net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                            crystal.getEyePos()
                    );
                    player.attack(crystal);
                    player.swingHand(Hand.MAIN_HAND);
                }
                return null;
            }

            if (!pathing || ticks % 20 == 0) {
                pathfinder.start(player, crystal.getBlockPos());
                pathing = true;
            }
            IPathfinder.Status status = pathfinder.tick(player);
            if (status == IPathfinder.Status.FAILED || status == IPathfinder.Status.IDLE) {
                return Outcome.failure(
                        FailureCode.DRAGON_FIGHT_ABORTED,
                        "A crystal without a usable bow line could not be reached for melee"
                );
            }
            return null;
        }

        private Outcome tickDragon(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (dragon == null || !dragon.isAlive()) {
                List<EnderDragonEntity> dragons = player.getServerWorld().getEntitiesByClass(
                        EnderDragonEntity.class,
                        player.getBoundingBox().expand(256.0),
                        Entity::isAlive
                );
                if (dragons.isEmpty()) {
                    return Outcome.success("End crystals destroyed and Ender Dragon defeated");
                }
                dragons.sort(Comparator.comparingInt(Entity::getId));
                dragon = dragons.get(0);
            }

            PhaseType<?> currentPhase = dragon.getPhaseManager().getCurrent().getType();
            boolean perched = currentPhase == PhaseType.LANDING
                    || currentPhase == PhaseType.SITTING_FLAMING
                    || currentPhase == PhaseType.SITTING_SCANNING
                    || currentPhase == PhaseType.SITTING_ATTACKING;
            if (!perched) {
                weaponSelected = false;
                return tickDodge(player, pathfinder);
            }

            if (!weaponSelected) {
                equipBestWeapon(player);
                weaponSelected = true;
            }
            Entity head = dragon.head;
            if (player.distanceTo(head) <= 6.0 && player.canSee(head)) {
                pathfinder.cancel(player);
                pathing = false;
                if (player.getAttackCooldownProgress(0.5F) >= 0.9F) {
                    player.lookAt(
                            net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                            head.getEyePos()
                    );
                    player.attack(head);
                    player.swingHand(Hand.MAIN_HAND);
                }
                return null;
            }

            if (!pathing || ticks % 20 == 0) {
                pathfinder.start(player, head.getBlockPos());
                pathing = true;
            }
            IPathfinder.Status status = pathfinder.tick(player);
            if (status == IPathfinder.Status.FAILED || status == IPathfinder.Status.IDLE) {
                return Outcome.failure(
                        FailureCode.DRAGON_FIGHT_ABORTED,
                        "The perched dragon could not be reached"
                );
            }
            return null;
        }

        private Outcome tickDodge(ServerPlayerEntity player, IPathfinder pathfinder) {
            if (pathing) {
                IPathfinder.Status status = pathfinder.tick(player);
                if (status == IPathfinder.Status.FAILED
                        || status == IPathfinder.Status.REACHED
                        || status == IPathfinder.Status.IDLE) {
                    pathfinder.cancel(player);
                    pathing = false;
                    nextDodgeTick = ticks + 20;
                }
            }
            if (!pathing && ticks >= nextDodgeTick) {
                BlockPos waypoint = dodgeWaypoint(player, dragon, dodgeIndex++);
                pathfinder.start(player, waypoint);
                pathing = true;
            }
            return null;
        }

        private static boolean shootArrow(ServerPlayerEntity player, EndCrystalEntity target) {
            int bowSlot = findItemSlot(player, Items.BOW);
            int arrowSlot = findItemSlot(player, Items.ARROW);
            if (bowSlot < 0 || arrowSlot < 0) {
                return false;
            }
            moveToMainHand(player, bowSlot);
            ArrowEntity arrow = new ArrowEntity(player.getServerWorld(), player);
            double distance = arrow.getPos().distanceTo(target.getPos());
            Vec3d aim = target.getPos()
                    .add(0.0, 0.5 + Math.min(12.0, distance * distance / 360.0), 0.0)
                    .subtract(arrow.getPos())
                    .normalize();
            arrow.setVelocity(aim.x, aim.y, aim.z, 3.0F, 0.0F);
            arrow.setDamage(4.0);
            if (!player.getServerWorld().spawnEntity(arrow)) {
                arrow.discard();
                return false;
            }
            removeItems(player, Items.ARROW, 1);
            player.getMainHandStack().damage(1, player, entity -> entity.sendToolBreakStatus(Hand.MAIN_HAND));
            player.lookAt(
                    net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                    target.getEyePos()
            );
            player.swingHand(Hand.MAIN_HAND);
            return true;
        }

        private static BlockPos dodgeWaypoint(
                ServerPlayerEntity player,
                EnderDragonEntity dragon,
                int index
        ) {
            int[][] offsets = {
                    {12, 0},
                    {0, 12},
                    {-12, 0},
                    {0, -12}
            };
            BlockPos origin = dragon.getFightOrigin();
            if (origin == null) {
                origin = player.getBlockPos();
            }
            int[] offset = offsets[Math.floorMod(index, offsets.length)];
            int x = origin.getX() + offset[0];
            int z = origin.getZ() + offset[1];
            ServerWorld world = player.getServerWorld();
            int top = Math.min(world.getTopY() - 2, player.getBlockY() + 8);
            int bottom = Math.max(world.getBottomY() + 1, player.getBlockY() - 16);
            for (int y = top; y >= bottom; y--) {
                BlockPos feet = new BlockPos(x, y, z);
                if (world.getBlockState(feet.down()).isSolidBlock(world, feet.down())
                        && world.getBlockState(feet).isReplaceable()
                        && world.getBlockState(feet.up()).isReplaceable()) {
                    return feet;
                }
            }
            return player.getBlockPos();
        }
    }

    private static Entity findCombatTarget(ServerPlayerEntity player, String targetType) {
        Box box = player.getBoundingBox().expand(32.0);
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

    private static JsonObject attackData(int kills) {
        JsonObject data = new JsonObject();
        data.addProperty("kills", kills);
        return data;
    }

    private static void equipBestWeapon(ServerPlayerEntity player) {
        int bestSlot = -1;
        double bestDamage = 0.0;
        for (int slot = 0; slot < player.getInventory().main.size(); slot++) {
            ItemStack stack = player.getInventory().main.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            double damage = stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
                    .get(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                    .stream()
                    .filter(modifier -> modifier.getOperation() == EntityAttributeModifier.Operation.ADDITION)
                    .mapToDouble(EntityAttributeModifier::getValue)
                    .sum();
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) {
            moveToMainHand(player, bestSlot);
        }
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
