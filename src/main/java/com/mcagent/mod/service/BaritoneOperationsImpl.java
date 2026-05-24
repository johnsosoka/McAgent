package com.mcagent.mod.service;

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
import baritone.api.utils.BlockOptionalMeta;
import com.mcagent.core.model.PathResult;
import com.mcagent.core.service.BotOperations;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Baritone-backed implementation of {@link BotOperations}.
 * Bridges core business logic to the Baritone pathfinding engine.
 *
 * <p>Compatible with Baritone v1.10.1 API (official mappings for MC 1.20.1).</p>
 */
@Slf4j
public class BaritoneOperationsImpl implements BotOperations {

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

    private final IBaritone baritone;
    private Consumer<String> progressCallback;

    // Track previous states for tick-based notifications
    private boolean wasPathing;
    private boolean wasMining;
    private boolean wasFollowing;
    private int pathStuckTicks;

    public BaritoneOperationsImpl() {
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
        log.info("Baritone: pathTo({}, {}, {})", x, y, z);
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
        notify("Navigating to (" + x + ", " + y + ", " + z + ")");
        return PathResult.builder()
                .success(true)
                .message("Pathing to (" + x + ", " + y + ", " + z + ")")
                .type(PathResult.PathResultType.SUCCESS)
                .build();
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
    }

    @Override
    public PathResult mine(String blockType, int maxBlocks, Integer radius) {
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
        BlockOptionalMeta bom = new BlockOptionalMeta(block);
        mining.mine(maxBlocks, bom);
        notify("Mining " + blockType + " (up to " + maxBlocks + " blocks)");

        return PathResult.builder()
                .success(true)
                .message("Mining " + blockType + " (up to " + maxBlocks + ")")
                .type(PathResult.PathResultType.SUCCESS)
                .build();
    }

    @Override
    public void cancel() {
        log.info("Baritone: cancelEverything");
        baritone.getPathingBehavior().cancelEverything();
        baritone.getMineProcess().cancel();
        baritone.getFollowProcess().cancel();
        notify("Cancelled current operation.");
    }

    @Override
    public void pause() {
        log.warn("Baritone pause not supported in v1.10.1 API");
    }

    @Override
    public void resume() {
        log.warn("Baritone resume not supported in v1.10.1 API");
    }

    @Override
    public Location getCurrentPosition() {
        var pos = baritone.getPlayerContext().playerFeet();
        return new Location(pos.x, pos.y, pos.z);
    }

    private void handlePathEvent(PathEvent event) {
        switch (event) {
            case AT_GOAL -> notify("Arrived at destination.");
            case CALC_FAILED -> notify("Cannot find a path to the destination.");
            case CANCELED -> notify("Pathing was cancelled.");
            case CALC_FINISHED_NOW_EXECUTING -> {
                // Path found and bot is starting to walk
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
        progressCallback.accept(message);
    }

    private Block resolveBlock(String blockType) {
        Block block = BLOCK_MAP.get(blockType.toUpperCase());
        if (block != null) {
            return block;
        }
        try {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    new net.minecraft.resources.ResourceLocation(blockType.toLowerCase())
            );
        } catch (Exception e) {
            log.warn("Could not resolve block: {}", blockType);
            return null;
        }
    }
}
