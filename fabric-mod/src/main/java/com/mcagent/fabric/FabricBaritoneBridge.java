package com.mcagent.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import baritone.api.process.IMineProcess;
import baritone.api.utils.BlockOptionalMetaLookup;
import com.mcagent.core.model.EntityInfo;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.model.PlayerInfo;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.BotOperations.Location;
import com.mcagent.fabric.queue.ClientThreadExecutor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Baritone-backed implementation of {@link BotOperations} for Fabric 26.1.2.
 * Bridges core business logic to the Baritone pathfinding engine.
 *
 * <p>Compatible with Baritone v1.15.0+ API (Mojmap for MC 26.1.2).</p>
 */
@Slf4j
public class FabricBaritoneBridge implements BotOperations {

    private static final Map<String, Block> BLOCK_MAP = Map.ofEntries(
            Map.entry("DIAMOND_ORE", Blocks.DIAMOND_ORE),
            Map.entry("IRON_ORE", Blocks.IRON_ORE),
            Map.entry("GOLD_ORE", Blocks.GOLD_ORE),
            Map.entry("COAL_ORE", Blocks.COAL_ORE),
            Map.entry("REDSTONE_ORE", Blocks.REDSTONE_ORE),
            Map.entry("LAPIS_ORE", Blocks.LAPIS_ORE),
            Map.entry("EMERALD_ORE", Blocks.EMERALD_ORE),
            Map.entry("STONE", Blocks.STONE),
            Map.entry("OAK_LOG", Blocks.OAK_LOG),
            Map.entry("BIRCH_LOG", Blocks.BIRCH_LOG),
            Map.entry("SPRUCE_LOG", Blocks.SPRUCE_LOG)
    );

    private static final Set<String> HOSTILE_MOBS = Set.of(
            "Creeper", "Zombie", "Skeleton", "Spider", "Enderman", "Witch",
            "Drowned", "Husk", "Stray", "WitherSkeleton", "Blaze", "Ghast",
            "PiglinBrute", "Vindicator", "Evoker", "Ravager"
    );

    private final IBaritone baritone;
    private Consumer<String> progressCallback;

    // Track previous states for tick-based notifications
    private boolean wasPathing;
    private boolean wasMining;
    private boolean wasFollowing;
    private int pathStuckTicks;

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
    public PathResult followPlayer(String playerName) {
        return ClientThreadExecutor.execute(() -> {
            log.info("Baritone: follow({})", playerName);
            IFollowProcess follow = baritone.getFollowProcess();
            Predicate<Entity> filter = entity -> entity.getName().getString().equals(playerName);
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
            Block block = resolveBlock(blockType);
            if (block == null) {
                return PathResult.builder()
                        .success(false)
                        .message("Unknown block type: " + blockType)
                        .type(PathResult.PathResultType.ERROR)
                        .build();
            }

            IMineProcess mining = baritone.getMineProcess();
            BlockOptionalMetaLookup boml = new BlockOptionalMetaLookup(block);
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
            Block block = resolveBlock(blockType);
            if (block != null) {
                BaritoneAPI.getSettings().blocksToAvoidBreaking.value.add(block);
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

    private boolean isHostileMob(String type) {
        return HOSTILE_MOBS.contains(type);
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

    private Block resolveBlock(String blockType) {
        Block block = BLOCK_MAP.get(blockType.toUpperCase());
        if (block != null) {
            return block;
        }
        try {
            Identifier id = Identifier.tryParse(blockType.toLowerCase());
            if (id == null) {
                id = Identifier.fromNamespaceAndPath("minecraft", blockType.toLowerCase());
            }
            return BuiltInRegistries.BLOCK.get(id).map(ref -> ref.value()).orElse(null);
        } catch (Exception e) {
            log.warn("Could not resolve block: {}", blockType);
            return null;
        }
    }
}
