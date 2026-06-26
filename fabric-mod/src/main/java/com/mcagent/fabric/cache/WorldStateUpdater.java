package com.mcagent.fabric.cache;

import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations.HealthStatus;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.BotOperations.ThreatInfo;
import com.mcagent.fabric.HostileMobs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure snapshot-building logic for {@link WorldStateCache}.
 *
 * <p>Accepts Minecraft {@link ClientLevel} and {@link Player} as inputs; emits a
 * {@link WorldStateCache.Snapshot} composed entirely of {@code core/} types.
 * All arguments are passed explicitly — no {@code Minecraft.getInstance()} calls
 * live here, keeping the logic straightforward to follow and the arguments clear.</p>
 *
 * <p>This class is intentionally stateless; every method is {@code static}.</p>
 */
public final class WorldStateUpdater {

    // Hostile mob names are defined centrally in HostileMobs to avoid duplication with FabricBaritoneBridge.

    /** Night begins at tick 13000 and ends at tick 23000 in the 24000-tick day cycle. */
    private static final int NIGHT_START = 13000;
    private static final int NIGHT_END = 23000;

    private WorldStateUpdater() {
        // static utility class
    }

    /**
     * Build a full {@link WorldStateCache.Snapshot} from live Minecraft client state.
     *
     * <p>This method must be called on the Minecraft client thread. All reads from
     * {@code level} and {@code player} are synchronous within the tick.</p>
     *
     * @param level            the current client level (non-null)
     * @param player           the local player (non-null)
     * @param entityScanRadius max radius for entity/threat/player scans (blocks)
     */
    public static WorldStateCache.Snapshot snapshot(ClientLevel level, Player player, int entityScanRadius) {
        Location currentPosition = botPosition(player);
        HealthStatus health = healthStatus(player);
        long rawTime = level.getDefaultClockTime() % 24000;
        int worldTime = (int) rawTime;
        boolean isNight = rawTime >= NIGHT_START && rawTime <= NIGHT_END;
        int lightLevel = level.getMaxLocalRawBrightness(player.blockPosition());
        String inventorySummary = inventorySummary(player);

        ScannedEntities scanned = scanEntities(level, player, currentPosition, entityScanRadius);

        // Wrap each list with List.copyOf so callers cannot mutate the cached snapshot.
        // Snapshot.EMPTY already uses List.of() (immutable); this guards all live snapshots.
        return new WorldStateCache.Snapshot(
                currentPosition,
                health,
                List.copyOf(scanned.entities()),
                List.copyOf(scanned.threats()),
                List.copyOf(scanned.players()),
                inventorySummary,
                worldTime,
                isNight,
                lightLevel
        );
    }

    // --- package-private helpers so WorldStateUpdaterTest can call them individually ---

    /**
     * Extract the bot's block-grid position from the player.
     */
    static Location botPosition(Player player) {
        BlockPos pos = player.blockPosition();
        return new Location(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Extract health, food, and armor from the player.
     */
    static HealthStatus healthStatus(Player player) {
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        int foodLevel = player.getFoodData().getFoodLevel();
        int armorValue = player.getArmorValue();
        return new HealthStatus(health, maxHealth, foodLevel, armorValue);
    }

    /**
     * Build a compact inventory summary string (top 10 items by count).
     * Format: "minecraft:dirt x64, minecraft:stone x32, ..."
     * Returns {@code "Inventory is empty."} when no items are present.
     */
    static String inventorySummary(Player player) {
        var inv = player.getInventory();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                counts.merge(id, stack.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "Inventory is empty.";
        }
        var entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int limit = Math.min(entries.size(), 10);
        var sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            var entry = entries.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * Scan all entities within {@code radius} blocks of the bot and partition them
     * into threats, players, and general entities.
     *
     * <p>The local player itself is excluded from the player list.</p>
     */
    static ScannedEntities scanEntities(ClientLevel level, Player localPlayer, Location botPos, int radius) {
        List<EntityInfo> entities = new ArrayList<>();
        List<ThreatInfo> threats = new ArrayList<>();
        List<PlayerInfo> players = new ArrayList<>();

        for (Entity entity : level.entitiesForRendering()) {
            // Skip item entities — they clutter the scan without providing threat/opportunity info
            if (entity instanceof ItemEntity) {
                continue;
            }

            BlockPos ePos = entity.blockPosition();
            Location loc = new Location(ePos.getX(), ePos.getY(), ePos.getZ());
            double dist = botPos.distanceTo(loc);

            if (dist > radius) {
                continue;
            }

            String direction = calculateDirection(botPos, loc);
            String type = entity.getClass().getSimpleName();

            if (entity instanceof Player p && p != localPlayer) {
                players.add(PlayerInfo.builder()
                        .name(p.getName().getString())
                        .location(loc)
                        .distance(dist)
                        .direction(direction)
                        .build());
            } else if (isHostileMob(type)) {
                threats.add(new ThreatInfo(type, loc, dist, direction));
            } else if (!(entity instanceof Player)) {
                entities.add(EntityInfo.builder()
                        .type(type)
                        .location(loc)
                        .distance(dist)
                        .direction(direction)
                        .build());
            }
        }

        entities.sort((a, b) -> Double.compare(a.getDistance(), b.getDistance()));
        threats.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        players.sort((a, b) -> Double.compare(a.getDistance(), b.getDistance()));

        return new ScannedEntities(entities, threats, players);
    }

    private static boolean isHostileMob(String type) {
        return HostileMobs.NAMES.contains(type);
    }

    private static String calculateDirection(Location from, Location to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.round(angle / 45) % 8;
        return dirs[index];
    }

    /**
     * Value holder for entity scan results partitioned by category.
     */
    record ScannedEntities(
            List<EntityInfo> entities,
            List<ThreatInfo> threats,
            List<PlayerInfo> players
    ) {}
}
