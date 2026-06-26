package com.mcagent.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.IMineProcess;
import baritone.api.schematic.FillSchematic;
import baritone.api.schematic.ISchematic;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.core.service.ops.BotControlOperations;
import com.mcagent.core.service.ops.BuildingOperations;
import com.mcagent.core.service.ops.InventoryOperations;
import com.mcagent.core.service.ops.NavigationOperations;
import com.mcagent.core.service.ops.ObservationOperations;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.fabric.cache.WorldStateCache;
import com.mcagent.fabric.queue.ClientThreadExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Baritone-backed implementation of {@link BotOperations} for Fabric 26.1.2.
 * Bridges core business logic to the Baritone pathfinding engine.
 *
 * <p>Compatible with Baritone v1.15.0+ API (Mojmap for MC 26.1.2).</p>
 */
public class FabricBaritoneBridge implements BotOperations,
        NavigationOperations,
        ObservationOperations,
        InventoryOperations,
        BuildingOperations,
        BotControlOperations {

    private static final Logger log = LoggerFactory.getLogger(FabricBaritoneBridge.class);

    // Hostile mob names are defined centrally in HostileMobs to avoid duplication with WorldStateUpdater.

    private final IBaritone baritone;
    private Consumer<String> progressCallback;

    // Track previous states for tick-based notifications
    private boolean wasPathing;
    private boolean wasMining;
    private boolean wasFollowing;
    private int pathStuckTicks;

    // Read-only world state cache; injected after Spring context is assembled.
    // Null until setCache() is called — fallback to live ClientThreadExecutor reads.
    private volatile WorldStateCache worldStateCache;

    // Counters for cache telemetry; injected alongside the cache.
    private volatile Counters counters;

    public FabricBaritoneBridge() {
        this.baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        this.progressCallback = msg -> {}; // no-op until set

        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onPathEvent(PathEvent event) {
                handlePathEvent(event);
            }

            @Override
            public void onTick(TickEvent event) {
                if (event.getState() == EventState.POST) {
                    handleTick();
                }
            }
        });
    }

    /**
     * Inject the world-state cache and counters after the Spring context has
     * assembled both beans. Called from {@link com.mcagent.fabric.McAgentFabricMod}.
     * Thread-safe: both fields are {@code volatile}.
     */
    public void setCache(WorldStateCache cache, Counters counters) {
        this.worldStateCache = cache;
        this.counters = counters;
    }

    @Override
    public void setProgressCallback(Consumer<String> callback) {
        this.progressCallback = callback != null ? callback : msg -> {};
    }

    @Override
    public PathResult navigateTo(int x, int y, int z) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: pathTo({}, {}, {})", x, y, z);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(x, y, z)));
            notify("Navigating to (" + x + ", " + y + ", " + z + ")");
            return PathResult.builder()
                    .success(true)
                    .message("Pathing to (" + x + ", " + y + ", " + z + ")")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult navigateTo(String locationName) {
        return PathResult.builder()
                .success(false)
                .message("Use navigateTo(int, int, int) with resolved coordinates.")
                .type(PathResult.PathResultType.ERROR)
                .build();
    }

    @Override
    public PathResult navigateToXZ(int x, int z) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: navigateToXZ({}, {})", x, z);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));
            notify("Navigating to surface coordinates (" + x + ", " + z + ")");
            return PathResult.builder()
                    .success(true)
                    .message("Pathing to surface (" + x + ", " + z + ")")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult navigateToYLevel(int y) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: navigateToYLevel({})", y);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalYLevel(y));
            notify("Going to Y=" + y);
            return PathResult.builder()
                    .success(true)
                    .message("Going to Y=" + y)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult exploreNear(Location center, int radius) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: exploreNear({}, {})", center, radius);
            BlockPos pos = new BlockPos(center.x(), center.y(), center.z());
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, radius));
            notify("Exploring within " + radius + " blocks of " + center);
            return PathResult.builder()
                    .success(true)
                    .message("Exploring within " + radius + " blocks of " + center)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult fleeFrom(Location threat, int safeDistance) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: fleeFrom({}, {})", threat, safeDistance);
            Location bot = getCurrentPosition();
            double dx = bot.x() - threat.x();
            double dz = bot.z() - threat.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double scale = safeDistance / Math.max(dist, 1.0);
            int retreatX = (int) (bot.x() + dx * scale);
            int retreatZ = (int) (bot.z() + dz * scale);
            BlockPos retreatPos = new BlockPos(retreatX, bot.y(), retreatZ);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(retreatPos));
            notify("Fleeing to maintain " + safeDistance + " blocks from threat");
            return PathResult.builder()
                    .success(true)
                    .message("Fleeing from " + threat + ", maintaining " + safeDistance + " blocks")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult navigateToNearest(List<Location> candidates) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: navigateToNearest({} candidates)", candidates.size());
            Goal[] goals = candidates.stream()
                    .map(loc -> new GoalBlock(new BlockPos(loc.x(), loc.y(), loc.z())))
                    .toArray(Goal[]::new);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalComposite(goals));
            notify("Navigating to nearest of " + candidates.size() + " locations");
            return PathResult.builder()
                    .success(true)
                    .message("Navigating to nearest of " + candidates.size() + " locations")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult followPlayer(String playerName) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: follow({})", playerName);
            IFollowProcess follow = baritone.getFollowProcess();
            Predicate<Entity> filter = entity -> entity.getName().getString().equalsIgnoreCase(playerName);
            follow.follow(filter);
            notify("Following player " + playerName);
            return PathResult.builder()
                    .success(true)
                    .message("Following player " + playerName)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult mine(String blockType, int maxBlocks, Integer radius) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: mine({}, {}, {})", blockType, maxBlocks, radius);
            Optional<Block> block = resolveBlock(blockType);
            if (block.isEmpty()) {
                return PathResult.builder()
                        .success(false)
                        .message("No such block: " + blockType)
                        .type(PathResult.PathResultType.ERROR)
                        .build();
            }

            IMineProcess mining = baritone.getMineProcess();
            BlockOptionalMetaLookup boml = new BlockOptionalMetaLookup(block.get());
            mining.mine(maxBlocks, boml);
            notify("Mining " + blockType + " (up to " + maxBlocks + " blocks)");

            return PathResult.builder()
                    .success(true)
                    .message("Mining " + blockType + " (up to " + maxBlocks + ")")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public void cancel() {
        ClientThreadExecutor.execute(() -> {
            log.info("Baritone: cancelEverything");
            baritone.getPathingBehavior().cancelEverything();
            baritone.getMineProcess().cancel();
            baritone.getFollowProcess().cancel();
            notify("Cancelled current operation.");
        });
    }

    @Override
    public void pause() {
        ClientThreadExecutor.execute(() -> log.warn("Baritone pause not supported in this API version"));
    }

    @Override
    public void resume() {
        ClientThreadExecutor.execute(() -> log.warn("Baritone resume not supported in this API version"));
    }

    @Override
    public Location getCurrentPosition() {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.currentPosition();
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var pos = baritone.getPlayerContext().playerFeet();
            return new Location(pos.x, pos.y, pos.z);
        });
    }

    @Override
    public Optional<Location> getPlayerPosition(String playerName) {
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) {
                return Optional.<Location>empty();
            }
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Player p && p.getName().getString().equalsIgnoreCase(playerName)) {
                    BlockPos pos = p.blockPosition();
                    return Optional.of(new Location(pos.getX(), pos.getY(), pos.getZ()));
                }
            }
            return Optional.<Location>empty();
        });
    }

    @Override
    public PlayerInfo findPlayer(String playerName) {
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return null;
            }
            Location botPos = new Location(mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ());
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Player p && p.getName().getString().equalsIgnoreCase(playerName)) {
                    BlockPos pos = p.blockPosition();
                    Location loc = new Location(pos.getX(), pos.getY(), pos.getZ());
                    double dist = botPos.distanceTo(loc);
                    String dir = calculateDirection(botPos, loc);
                    return PlayerInfo.builder()
                            .name(p.getName().getString())
                            .location(loc)
                            .distance(dist)
                            .direction(dir)
                            .build();
                }
            }
            return null;
        });
    }

    @Override
    public List<PlayerInfo> getNearbyPlayers(int radius) {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.playersWithinRadius(radius);
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return new ArrayList<>();
            }
            Location botPos = new Location(mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ());
            List<PlayerInfo> players = new ArrayList<>();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Player p && p != mc.player) {
                    BlockPos pos = p.blockPosition();
                    Location loc = new Location(pos.getX(), pos.getY(), pos.getZ());
                    double dist = botPos.distanceTo(loc);
                    if (dist <= radius) {
                        players.add(PlayerInfo.builder()
                                .name(p.getName().getString())
                                .location(loc)
                                .distance(dist)
                                .direction(calculateDirection(botPos, loc))
                                .build());
                    }
                }
            }
            players.sort((a, b) -> Double.compare(a.getDistance(), b.getDistance()));
            return players;
        });
    }

    @Override
    public List<EntityInfo> getNearbyEntities(String entityType, int radius) {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.entitiesWithinRadius(entityType, radius);
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return new ArrayList<>();
            }
            Location botPos = new Location(mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ());
            List<EntityInfo> entities = new ArrayList<>();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getClass().getSimpleName().equalsIgnoreCase(entityType)) {
                    BlockPos pos = entity.blockPosition();
                    Location loc = new Location(pos.getX(), pos.getY(), pos.getZ());
                    double dist = botPos.distanceTo(loc);
                    if (dist <= radius) {
                        entities.add(EntityInfo.builder()
                                .type(entity.getClass().getSimpleName())
                                .location(loc)
                                .distance(dist)
                                .direction(calculateDirection(botPos, loc))
                                .build());
                    }
                }
            }
            entities.sort((a, b) -> Double.compare(a.getDistance(), b.getDistance()));
            return entities;
        });
    }

    @Override
    public void setSafetyMode(boolean enabled) {
        ClientThreadExecutor.execute(() -> {
            log.info("Baritone: setSafetyMode({})", enabled);
            var settings = BaritoneAPI.getSettings();
            if (enabled) {
                settings.allowBreak.value = false;
                settings.allowParkour.value = false;
                settings.allowSprint.value = false;
                settings.avoidance.value = true;
                settings.mobAvoidanceRadius.value = 16;
                populateCommonAvoidBlocks();
                notify("Safe mode enabled. I'll be careful.");
            } else {
                settings.allowBreak.value = true;
                settings.allowParkour.value = true;
                settings.allowSprint.value = true;
                settings.avoidance.value = false;
                settings.mobAvoidanceRadius.value = 8;
                settings.blocksToAvoidBreaking.value.clear();
                notify("Safe mode disabled. Normal behavior restored.");
            }
        });
    }

    @Override
    public HealthStatus getHealthStatus() {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.health();
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                log.warn("Cannot get health status: player is null");
                return new HealthStatus(0, 0, 0, 0);
            }
            float health = player.getHealth();
            float maxHealth = player.getMaxHealth();
            int foodLevel = player.getFoodData().getFoodLevel();
            int armorValue = player.getArmorValue();
            return new HealthStatus(health, maxHealth, foodLevel, armorValue);
        });
    }

    @Override
    public List<ThreatInfo> getNearbyThreats(int radius) {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.threatsWithinRadius(radius);
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return new ArrayList<ThreatInfo>();
            }
            Location botPos = new Location(mc.player.blockPosition().getX(), mc.player.blockPosition().getY(), mc.player.blockPosition().getZ());
            List<ThreatInfo> threats = new ArrayList<>();
            for (Entity entity : mc.level.entitiesForRendering()) {
                String type = entity.getClass().getSimpleName();
                if (isHostileMob(type)) {
                    BlockPos pos = entity.blockPosition();
                    Location loc = new Location(pos.getX(), pos.getY(), pos.getZ());
                    double dist = botPos.distanceTo(loc);
                    if (dist <= radius) {
                        threats.add(new ThreatInfo(type, loc, dist, calculateDirection(botPos, loc)));
                    }
                }
            }
            threats.sort((a, b) -> Double.compare(a.distance(), b.distance()));
            return threats;
        });
    }

    @Override
    public int getWorldTime() {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.worldTime();
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) {
                log.warn("Cannot get world time: level is null");
                return 0;
            }
            return (int) (mc.level.getDefaultClockTime() % 24000);
        });
    }

    @Override
    public boolean isNight() {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.isNight();
            }
        }
        recordCacheMiss();
        return BotControlHelpers.isNightTick(getWorldTime());
    }

    @Override
    public int getLightLevel() {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.lightLevel();
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                log.warn("Cannot get light level: level or player is null");
                return 0;
            }
            BlockPos feetPos = mc.player.blockPosition();
            return mc.level.getMaxLocalRawBrightness(feetPos);
        });
    }

    @Override
    public void setPathingBehavior(String mode) {
        ClientThreadExecutor.execute(() -> {
            log.info("Baritone: setPathingBehavior({})", mode);
            var settings = BaritoneAPI.getSettings();
            switch (mode.toLowerCase()) {
                case "careful" -> {
                    settings.allowBreak.value = false;
                    settings.allowParkour.value = false;
                    settings.allowSprint.value = false;
                    populateCommonAvoidBlocks();
                    notify("Pathing behavior set to careful.");
                }
                case "aggressive" -> {
                    settings.allowBreak.value = true;
                    settings.allowParkour.value = true;
                    settings.allowSprint.value = true;
                    settings.blocksToAvoidBreaking.value.clear();
                    notify("Pathing behavior set to aggressive.");
                }
                case "default" -> {
                    settings.allowBreak.value = true;
                    settings.allowParkour.value = true;
                    settings.allowSprint.value = true;
                    settings.blocksToAvoidBreaking.value.clear();
                    notify("Pathing behavior set to default.");
                }
                default -> log.warn("Unknown pathing behavior mode: {}", mode);
            }
        });
    }

    @Override
    public void addBlockToAvoid(String blockType) {
        ClientThreadExecutor.execute(() -> {
            Optional<Block> block = resolveBlock(blockType);
            if (block.isPresent()) {
                BaritoneAPI.getSettings().blocksToAvoidBreaking.value.add(block.get());
                log.info("Added block to avoid-breaking list: {}", blockType);
            } else {
                log.warn("Could not find block: {}", blockType);
            }
        });
    }

    @Override
    public void clearAvoidedBlocks() {
        ClientThreadExecutor.execute(() -> {
            BaritoneAPI.getSettings().blocksToAvoidBreaking.value.clear();
            log.info("Cleared all block avoidance rules.");
        });
    }

    /**
     * Attempt to eat a food item from the bot's inventory.
     *
     * <p><b>Contract: optimistic / best-effort.</b> Food consumption is server-authoritative
     * and takes approximately 32 ticks to complete. The post-call food level on the same
     * tick will not yet reflect the eaten item, so a synchronous hunger check always
     * returns false. Instead, this method returns {@code true} as soon as a valid food
     * slot has been selected and {@code useItem} dispatched — meaning "eat initiated
     * successfully." A {@code false} return means no suitable food was found in inventory.</p>
     *
     * <p>Sprint 13 may add an async follow-up verification via
     * {@code Minecraft.getInstance().execute(...)} scheduled 40 ticks out, if tighter
     * confirmation semantics are needed.</p>
     *
     * @param itemId specific food item ID, or {@code null} for auto-best selection
     * @return {@code true} if a food item was found and consumption initiated;
     *         {@code false} if no matching food was in inventory
     */
    @Override
    public boolean eat(String itemId) {
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null) {
                log.warn("Cannot eat: player is null");
                return false;
            }

            // Build a slot list from inventory for the helper
            var inv = mc.player.getInventory();
            List<BotControlHelpers.InventorySlot> slots = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    slots.add(new BotControlHelpers.InventorySlot(i, id, stack.getCount()));
                }
            }

            int targetSlot = (itemId == null)
                    ? BotControlHelpers.chooseBestFoodSlot(slots, BotControlHelpers.FOOD_PRIORITY)
                    : BotControlHelpers.findItemSlot(slots, itemId);

            if (targetSlot < 0) {
                log.info("eat: no food found (itemId={})", itemId);
                return false;
            }

            // Move the food item into a hotbar slot (0-8) so it can be selected.
            // Inventory slots 0-8 are hotbar slots; slots >= 9 are main inventory.
            if (targetSlot >= 9) {
                // Swap the item into the currently selected hotbar slot
                inv.pickSlot(targetSlot);
                // After pickSlot the item is now at inv.getSelectedSlot()
            } else {
                inv.setSelectedSlot(targetSlot);
            }

            // Dispatch the use-item packet to begin food consumption animation.
            // Food consumption is server-authoritative (~32 ticks); we do not wait
            // for the server acknowledgement — return optimistic true immediately.
            if (mc.gameMode != null) {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            }

            log.info("eat: initiated consumption of slot {} (itemId={})", targetSlot, itemId);
            return true;
        });
    }

    @Override
    public void placeTorch() {
        ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.gameMode == null) {
                log.warn("placeTorch: not in a world");
                return;
            }

            // Confirm torch in inventory
            if (!hasItem("minecraft:torch", 1)) {
                log.info("placeTorch: no torch in inventory");
                return;
            }

            // Select a torch in hotbar or swap it in
            var inv = mc.player.getInventory();
            List<BotControlHelpers.InventorySlot> slots = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    slots.add(new BotControlHelpers.InventorySlot(i, id, stack.getCount()));
                }
            }
            int torchSlot = BotControlHelpers.findItemSlot(slots, "minecraft:torch");
            if (torchSlot < 0) {
                return; // already checked hasItem, should not happen
            }
            if (torchSlot >= 9) {
                inv.pickSlot(torchSlot);
            } else {
                inv.setSelectedSlot(torchSlot);
            }

            // Find a placement surface: scan adjacent blocks at bot's feet level.
            // Torches attach to the side of a solid block; we want an air block with
            // a solid block adjacent in a horizontal direction.
            BlockPos feetPos = mc.player.blockPosition();
            Direction[] horizontalDirs = {
                    Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
            };

            BlockPos placementPos = null;
            Direction attachFace = null;

            for (Direction dir : horizontalDirs) {
                BlockPos candidate = feetPos.relative(dir);
                BlockPos supportBlock = candidate.relative(dir); // block to attach torch against
                if (mc.level.getBlockState(candidate).isAir()
                        && mc.level.getBlockState(supportBlock).isSolid()) {
                    placementPos = candidate;
                    attachFace = dir.getOpposite();
                    break;
                }
            }

            // Fallback: place on the block directly below the bot's feet
            if (placementPos == null) {
                BlockPos below = feetPos.below();
                if (mc.level.getBlockState(feetPos).isAir()
                        && mc.level.getBlockState(below).isSolid()) {
                    placementPos = feetPos;
                    attachFace = Direction.DOWN;
                }
            }

            if (placementPos == null) {
                log.info("placeTorch: no suitable adjacent surface found at {}", feetPos);
                return;
            }

            // Use the game mode's useItemOn to place the torch
            BlockPos supportingBlock = placementPos.relative(attachFace.getOpposite());
            Vec3 hitVec = Vec3.atCenterOf(supportingBlock);
            BlockHitResult hit = new BlockHitResult(hitVec, attachFace, supportingBlock, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            log.info("placeTorch: placed at {} (attached to {} face)", placementPos, attachFace);
        });
    }

    /** Increment the {@code cache.hit} counter when the cache served a read. */
    private void recordCacheHit() {
        Counters c = counters;
        if (c != null) {
            c.inc("cache.hit");
        }
    }

    /** Increment the {@code cache.miss} counter when the cache fell back to a live read. */
    private void recordCacheMiss() {
        Counters c = counters;
        if (c != null) {
            c.inc("cache.miss");
        }
    }

    private boolean isHostileMob(String type) {
        return HostileMobs.NAMES.contains(type);
    }

    private void populateCommonAvoidBlocks() {
        var avoidList = BaritoneAPI.getSettings().blocksToAvoidBreaking.value;
        avoidList.add(Blocks.GLASS);
        avoidList.add(Blocks.GLASS_PANE);
        avoidList.add(Blocks.OAK_PLANKS);
        avoidList.add(Blocks.BIRCH_PLANKS);
        avoidList.add(Blocks.SPRUCE_PLANKS);
        avoidList.add(Blocks.JUNGLE_PLANKS);
        avoidList.add(Blocks.ACACIA_PLANKS);
        avoidList.add(Blocks.DARK_OAK_PLANKS);
        avoidList.add(Blocks.OAK_LOG);
        avoidList.add(Blocks.BIRCH_LOG);
        avoidList.add(Blocks.SPRUCE_LOG);
        avoidList.add(Blocks.JUNGLE_LOG);
        avoidList.add(Blocks.ACACIA_LOG);
        avoidList.add(Blocks.DARK_OAK_LOG);
        avoidList.add(Blocks.STONE_BRICKS);
        avoidList.add(Blocks.BRICKS);
        avoidList.add(Blocks.WHITE_WOOL);
    }

    private String calculateDirection(Location from, Location to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.round(angle / 45) % 8;
        return dirs[index];
    }

    void onClientTick() {
        // Baritone event listeners handle progress via onPathEvent/onTick callbacks
        // This hook is available for any Fabric-specific tick logic if needed
    }

    private void handlePathEvent(PathEvent event) {
        switch (event) {
            case AT_GOAL -> {
                pathStuckTicks = 0;
                notify("Arrived at destination.");
            }
            case CALC_FAILED -> {
                pathStuckTicks = 0;
                notify("Cannot find a path to the destination.");
            }
            case CANCELED -> {
                pathStuckTicks = 0;
                notify("Pathing was cancelled.");
            }
            case CALC_FINISHED_NOW_EXECUTING -> {
                pathStuckTicks = 0;
                log.debug("Path calculation complete; now executing");
            }
            case NEXT_CALC_FAILED -> {
                pathStuckTicks++;
                if (pathStuckTicks > 100) {
                    notify("Stuck trying to find the next path segment.");
                    pathStuckTicks = 0;
                }
            }
            default -> log.debug("PathEvent: {}", event);
        }
    }

    private void handleTick() {
        IPathingBehavior pathing = baritone.getPathingBehavior();
        IMineProcess mining = baritone.getMineProcess();
        IFollowProcess follow = baritone.getFollowProcess();

        boolean isPathing = pathing.isPathing();
        boolean isMining = mining.isActive();
        boolean isFollowing = follow.isActive();

        if (wasPathing && !isPathing) {
            // Pathing stopped but no AT_GOAL event — likely stuck or failed
            if (pathing.getGoal() != null) {
                notify("Pathing stopped before reaching the goal.");
            }
        }

        if (wasMining && !isMining) {
            notify("Mining operation complete.");
        }

        if (wasFollowing && !isFollowing) {
            notify("Stopped following target.");
        }

        wasPathing = isPathing;
        wasMining = isMining;
        wasFollowing = isFollowing;
    }

    private void notify(String message) {
        log.info("[Progress] {}", message);
        if (progressCallback != null) {
            progressCallback.accept(message);
        }
    }

    private Optional<Block> resolveBlock(String blockType) {
        if (blockType == null || blockType.isBlank()) {
            return Optional.empty();
        }
        String lower = blockType.toLowerCase();
        final String qualified = lower.contains(":") ? lower : "minecraft:" + lower;
        try {
            Identifier id = Identifier.parse(qualified);
            return BuiltInRegistries.BLOCK.get(id)
                    .map(ref -> ref.value())
                    .filter(b -> b != Blocks.AIR || qualified.endsWith(":air"));
        } catch (Exception e) {
            log.warn("Could not resolve block: {}", blockType);
            return Optional.empty();
        }
    }

    @Override
    public boolean hasItem(String itemId, int count) {
        return ClientThreadExecutor.execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                log.warn("Cannot check inventory: player is null");
                return false;
            }
            var inv = player.getInventory();
            String target = itemId.contains(":") ? itemId.toLowerCase() : "minecraft:" + itemId.toLowerCase();
            int total = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (id.equalsIgnoreCase(target)) {
                        total += stack.getCount();
                        if (total >= count) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
    }

    @Override
    public int countItem(String itemId) {
        return ClientThreadExecutor.execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                log.warn("Cannot check inventory: player is null");
                return 0;
            }
            var inv = player.getInventory();
            String target = itemId.contains(":") ? itemId.toLowerCase() : "minecraft:" + itemId.toLowerCase();
            int total = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    if (id.equalsIgnoreCase(target)) {
                        total += stack.getCount();
                    }
                }
            }
            return total;
        });
    }

    @Override
    public String getInventorySummary() {
        WorldStateCache cache = worldStateCache;
        if (cache != null) {
            WorldStateCache.Snapshot snap = cache.get();
            if (!snap.isEmpty()) {
                recordCacheHit();
                return snap.inventorySummary();
            }
        }
        recordCacheMiss();
        return ClientThreadExecutor.execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) {
                log.warn("Cannot get inventory summary: player is null");
                return "Inventory is empty.";
            }
            var inv = player.getInventory();
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
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
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                var entry = entries.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append(" x").append(entry.getValue());
            }
            return sb.toString();
        });
    }

    @Override
    public String pickupNearbyItems(int radius) {
        return ClientThreadExecutor.execute(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                log.warn("Cannot pickup items: level or player is null");
                return "Cannot pick up items: not in a world.";
            }
            BlockPos botPos = mc.player.blockPosition();
            double r = radius;
            AABB searchBox = AABB.ofSize(
                    new Vec3(botPos.getX(), botPos.getY(), botPos.getZ()),
                    r * 2, r * 2, r * 2
            );
            List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, searchBox);
            if (items.isEmpty()) {
                return "No items found within " + radius + " blocks.";
            }
            // Build a single GoalComposite so Baritone navigates to the nearest of all items
            // in one pathing call. Setting the goal in a loop overwrites the previous goal on
            // each iteration, meaning only the last item would ever be pathed to.
            Goal[] goals = items.stream()
                    .map(i -> (Goal) new GoalNear(i.blockPosition(), 1))
                    .toArray(Goal[]::new);
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalComposite(goals));
            return "Picking up " + items.size() + " items within " + radius + " blocks.";
        });
    }

    @Override
    public PathResult buildPlatform(int x1, int y1, int z1, int x2, int y2, int z2, String blockType) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: buildPlatform({}, {}, {}, {}, {}, {}, {})", x1, y1, z1, x2, y2, z2, blockType);
            Optional<Block> block = resolveBlock(blockType);
            if (block.isEmpty()) {
                return PathResult.builder()
                        .success(false)
                        .message("No such block: " + blockType)
                        .type(PathResult.PathResultType.ERROR)
                        .build();
            }
            int width = Math.abs(x2 - x1) + 1;
            int height = Math.abs(y2 - y1) + 1;
            int length = Math.abs(z2 - z1) + 1;
            BlockPos origin = new BlockPos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
            BaritoneAPI.getSettings().allowPlace.value = true;
            IBuilderProcess builder = baritone.getBuilderProcess();
            ISchematic schematic = new FillSchematic(width, height, length, new BlockOptionalMeta(block.get()));
            builder.build("platform", schematic, origin);
            notify("Building " + blockType + " platform " + width + "x" + height + "x" + length);
            return PathResult.builder()
                    .success(true)
                    .message("Building " + blockType + " platform " + width + "x" + height + "x" + length)
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }

    @Override
    public PathResult placeBlock(int x, int y, int z, String blockType) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: placeBlock({}, {}, {}, {})", x, y, z, blockType);
            Optional<Block> block = resolveBlock(blockType);
            if (block.isEmpty()) {
                return PathResult.builder()
                        .success(false)
                        .message("No such block: " + blockType)
                        .type(PathResult.PathResultType.ERROR)
                        .build();
            }
            BaritoneAPI.getSettings().allowPlace.value = true;
            IBuilderProcess builder = baritone.getBuilderProcess();
            ISchematic schematic = new FillSchematic(1, 1, 1, new BlockOptionalMeta(block.get()));
            builder.build("place", schematic, new BlockPos(x, y, z));
            notify("Placing " + blockType + " at (" + x + ", " + y + ", " + z + ")");
            return PathResult.builder()
                    .success(true)
                    .message("Placing " + blockType + " at (" + x + ", " + y + ", " + z + ")")
                    .type(PathResult.PathResultType.SUCCESS)
                    .build();
        });
    }
}
