package dev.minecp.observation;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.minecp.protocol.BridgeWebSocketClient;
import dev.minecp.skill.SkillManager;
import net.minecraft.advancement.Advancement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Emits only fields defined in observation.schema.json or event.schema.json.
 * In accordance with ADR-0001, no historical or remembered coordinate is kept.
 */
public final class ObservationService {
    private static final Set<String> NOTABLE_PICKUPS = Set.of(
            "minecraft:blaze_rod",
            "minecraft:ender_pearl",
            "minecraft:ender_eye",
            "minecraft:diamond"
    );
    private static final List<String> TRACKED_ADVANCEMENTS = List.of(
            "minecraft:story/mine_stone",
            "minecraft:story/upgrade_tools",
            "minecraft:story/smelt_iron",
            "minecraft:story/iron_tools",
            "minecraft:story/enter_the_nether",
            "minecraft:nether/obtain_blaze_rod",
            "minecraft:story/follow_ender_eye",
            "minecraft:story/enter_the_end",
            "minecraft:end/kill_dragon"
    );

    private final Supplier<ServerPlayerEntity> playerSupplier;
    private final NearbyScanner scanner;
    private final Supplier<SkillManager> skillSupplier;
    private final Logger logger;
    private final Map<String, Integer> previousNotableCounts = new HashMap<>();
    private final Set<String> previousAdvancements = new HashSet<>();
    private BridgeWebSocketClient bridge;
    private float previousHealth = -1.0F;
    private boolean critical;
    private boolean monitorsInitialized;

    public ObservationService(
            Supplier<ServerPlayerEntity> playerSupplier,
            NearbyScanner scanner,
            Supplier<SkillManager> skillSupplier,
            Logger logger
    ) {
        this.playerSupplier = playerSupplier;
        this.scanner = scanner;
        this.skillSupplier = skillSupplier;
        this.logger = logger;
    }

    public void setBridge(BridgeWebSocketClient bridge) {
        this.bridge = bridge;
    }

    public void send(String reason) {
        ServerPlayerEntity player = playerSupplier.get();
        if (bridge == null || player == null) {
            return;
        }
        bridge.send("observation", json -> writeObservation(json, reason, player));
    }

    public void sendDeathEvent(ServerPlayerEntity player, String cause) {
        sendEvent("death", data -> {
            data.add("pos", vec3(player.getPos()));
            data.addProperty("dimension", dimension(player));
            data.addProperty("cause", cause == null || cause.isBlank() ? "unknown" : cause);
        });
        send("interrupt");
        monitorsInitialized = false;
    }

    public void sendRespawnedEvent(ServerPlayerEntity player) {
        sendEvent("respawned", data -> {
            data.add("pos", vec3(player.getPos()));
            data.addProperty("dimension", dimension(player));
        });
        send("interrupt");
        monitorsInitialized = false;
    }

    public void tickInterruptMonitors() {
        ServerPlayerEntity player = playerSupplier.get();
        if (bridge == null || player == null || player.isDead()) {
            return;
        }

        float health = player.getHealth();
        Map<String, Integer> currentNotable = notableCounts(player);
        Set<String> currentAdvancements = completedAdvancements(player);
        if (!monitorsInitialized) {
            previousHealth = health;
            critical = health <= 6.0F;
            previousNotableCounts.clear();
            previousNotableCounts.putAll(currentNotable);
            previousAdvancements.clear();
            previousAdvancements.addAll(currentAdvancements);
            monitorsInitialized = true;
            return;
        }

        if (health < previousHealth && health > 0.0F) {
            Entity attacker = player.getAttacker();
            String attackerType = attacker == null
                    ? "unknown"
                    : Registries.ENTITY_TYPE.getId(attacker.getType()).toString();
            sendEvent("attacked", data -> {
                data.addProperty("attacker_type", attackerType);
                data.addProperty("hp", health);
            });
            send("interrupt");
        }

        if (health <= 6.0F && !critical) {
            sendEvent("hp_critical", data -> data.addProperty("hp", health));
            send("interrupt");
        }
        critical = health <= 6.0F;
        previousHealth = health;

        currentNotable.forEach((id, count) -> {
            int oldCount = previousNotableCounts.getOrDefault(id, 0);
            if (count > oldCount) {
                sendEvent("item_pickup", data -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", id);
                    item.addProperty("count", count - oldCount);
                    data.add("item", item);
                });
                send("interrupt");
            }
        });
        previousNotableCounts.clear();
        previousNotableCounts.putAll(currentNotable);

        for (String id : currentAdvancements) {
            if (previousAdvancements.add(id)) {
                sendEvent("advancement", data -> data.addProperty("id", id));
                send("interrupt");
            }
        }
    }

    private void writeObservation(JsonObject json, String reason, ServerPlayerEntity player) {
        json.addProperty("reason", reason);

        JsonObject self = new JsonObject();
        self.addProperty("hp", Math.max(0.0F, Math.min(20.0F, player.getHealth())));
        self.addProperty("food", player.getHungerManager().getFoodLevel());
        self.add("pos", vec3(player.getPos()));
        self.addProperty("yaw", player.getYaw());
        self.addProperty("pitch", player.getPitch());
        self.addProperty("dimension", dimension(player));
        self.addProperty("game_time", player.getServerWorld().getTime());
        self.addProperty("time_of_day", Math.floorMod(player.getServerWorld().getTimeOfDay(), 24000L));
        json.add("self", self);

        JsonObject inventory = new JsonObject();
        JsonArray items = new JsonArray();
        Map<String, Integer> counts = inventoryCounts(player);
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.getKey());
            item.addProperty("count", entry.getValue());
            items.add(item);
        });
        int emptySlots = 0;
        for (ItemStack stack : player.getInventory().main) {
            if (stack.isEmpty()) {
                emptySlots++;
            }
        }
        inventory.add("items", items);
        inventory.addProperty("empty_slots", emptySlots);
        json.add("inventory", inventory);

        JsonObject equipment = new JsonObject();
        addEquipment(equipment, "main_hand", player.getMainHandStack());
        addEquipment(equipment, "off_hand", player.getOffHandStack());
        addEquipment(equipment, "helmet", player.getEquippedStack(EquipmentSlot.HEAD));
        addEquipment(equipment, "chestplate", player.getEquippedStack(EquipmentSlot.CHEST));
        addEquipment(equipment, "leggings", player.getEquippedStack(EquipmentSlot.LEGS));
        addEquipment(equipment, "boots", player.getEquippedStack(EquipmentSlot.FEET));
        json.add("equipment", equipment);

        JsonObject nearby = new JsonObject();
        JsonArray points = new JsonArray();
        for (NearbyScanner.PointOfInterest point : scanner.pointsOfInterest(player)) {
            JsonObject poi = new JsonObject();
            poi.addProperty("kind", point.kind());
            poi.addProperty("id", point.id());
            poi.add("pos", blockPos(point.pos()));
            poi.addProperty("distance", point.distance());
            points.add(poi);
        }
        nearby.add("points_of_interest", points);

        NearbyScanner.NearbyEntities entities = scanner.nearbyEntities(player);
        JsonArray hostiles = new JsonArray();
        for (NearbyScanner.Hostile hostile : entities.hostiles()) {
            JsonObject hostileJson = new JsonObject();
            hostileJson.addProperty("type", hostile.type());
            hostileJson.addProperty("distance", hostile.distance());
            hostileJson.add("pos", vec3(hostile.pos()));
            hostiles.add(hostileJson);
        }
        nearby.add("hostiles", hostiles);
        nearby.addProperty("villagers", entities.villagers());
        json.add("nearby", nearby);

        JsonObject progress = new JsonObject();
        progress.addProperty("blaze_rods", counts.getOrDefault("minecraft:blaze_rod", 0));
        progress.addProperty("ender_pearls", counts.getOrDefault("minecraft:ender_pearl", 0));
        progress.addProperty("ender_eyes", counts.getOrDefault("minecraft:ender_eye", 0));
        JsonArray advancements = new JsonArray();
        completedAdvancements(player).stream().sorted().forEach(advancements::add);
        progress.add("advancements", advancements);
        json.add("progress", progress);

        SkillManager manager = skillSupplier.get();
        SkillManager.CurrentSkill current = manager == null ? null : manager.currentSkill();
        if (current == null) {
            json.add("current_skill", JsonNull.INSTANCE);
        } else {
            JsonObject skill = new JsonObject();
            skill.addProperty("command_id", current.commandId());
            skill.addProperty("skill", current.skill());
            skill.addProperty("elapsed_ms", current.elapsedMs());
            json.add("current_skill", skill);
        }
    }

    private void sendEvent(String eventType, java.util.function.Consumer<JsonObject> dataWriter) {
        if (bridge == null) {
            return;
        }
        bridge.send("event", json -> {
            json.addProperty("event_type", eventType);
            JsonObject data = new JsonObject();
            dataWriter.accept(data);
            json.add("data", data);
        });
    }

    private static Map<String, Integer> inventoryCounts(ServerPlayerEntity player) {
        Map<String, Integer> result = new HashMap<>();
        for (ItemStack stack : player.getInventory().main) {
            if (!stack.isEmpty()) {
                result.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }
        return result;
    }

    private static Map<String, Integer> notableCounts(ServerPlayerEntity player) {
        Map<String, Integer> all = inventoryCounts(player);
        Map<String, Integer> result = new HashMap<>();
        NOTABLE_PICKUPS.forEach(id -> result.put(id, all.getOrDefault(id, 0)));
        return result;
    }

    private static Set<String> completedAdvancements(ServerPlayerEntity player) {
        Set<String> result = new HashSet<>();
        for (String id : TRACKED_ADVANCEMENTS) {
            Advancement advancement = player.server.getAdvancementLoader().get(new Identifier(id));
            if (advancement != null && player.getAdvancementTracker().getProgress(advancement).isDone()) {
                result.add(id);
            }
        }
        return result;
    }

    private static void addEquipment(JsonObject equipment, String field, ItemStack stack) {
        if (stack.isEmpty()) {
            equipment.add(field, JsonNull.INSTANCE);
        } else {
            equipment.addProperty(field, Registries.ITEM.getId(stack.getItem()).toString());
        }
    }

    private static String dimension(ServerPlayerEntity player) {
        if (player.getWorld().getRegistryKey() == World.NETHER) {
            return "nether";
        }
        if (player.getWorld().getRegistryKey() == World.END) {
            return "end";
        }
        return "overworld";
    }

    private static JsonObject vec3(Vec3d pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.x);
        json.addProperty("y", pos.y);
        json.addProperty("z", pos.z);
        return json;
    }

    private static JsonObject blockPos(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }
}
