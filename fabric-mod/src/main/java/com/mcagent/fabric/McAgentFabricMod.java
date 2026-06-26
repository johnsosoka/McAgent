package com.mcagent.fabric;

import com.mcagent.core.CoreApplication;
import com.mcagent.core.config.BotProperties;
import com.mcagent.core.config.EnvLoader;
import com.mcagent.core.routine.AutoModeDriver;
import com.mcagent.core.routine.RoutineService;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.core.reflex.SurvivalReflexes;
import com.mcagent.core.task.DefaultTaskExecutor;
import com.mcagent.core.task.PendingApprovalStore;
import com.mcagent.core.task.SessionResumer;
import com.mcagent.core.task.TaskExecutor;
import com.mcagent.core.telemetry.Counters;
import com.mcagent.core.telemetry.JobTracker;
import com.mcagent.core.telemetry.RunLog;
import com.mcagent.fabric.cache.WorldStateCache;
import com.mcagent.fabric.cache.WorldStateUpdater;
import com.mcagent.fabric.observer.AutonomousObserver;
import com.mcagent.fabric.queue.BotEventQueue;
import com.mcagent.fabric.queue.FrameworkMessageBuffer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;

/**
 * Fabric client mod entry point for McAgent on Minecraft 26.1.2.
 * Bootstraps the Spring context and wires Minecraft event handlers to core services.
 */
public class McAgentFabricMod implements ClientModInitializer {
    public static final String MOD_ID = "mc_agent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int DISCONNECT_DEBOUNCE_TICKS = 60; // 3 seconds at 20 TPS

    /** Cache update cadence: every N client ticks (5 ticks = 250 ms at 20 TPS). */
    private static final int CACHE_UPDATE_INTERVAL_TICKS = 5;

    /**
     * Fallback scan radius used only if BotProperties cannot be resolved before the first
     * cache refresh. In practice this is never reached because the cache is populated after
     * initSpringContext() completes; the value intentionally matches the default threat radius.
     */
    private static final int CACHE_SCAN_RADIUS_DEFAULT = 32;

    private AnnotationConfigApplicationContext springContext;
    private FabricChatHandler chatHandler;
    private FabricBaritoneBridge baritoneBridge;
    private AutonomousObserver autonomousObserver;
    private WorldStateCache worldStateCache;
    private ClientPacketListener lastConnection;
    private BotEventQueue botEventQueue;
    private FrameworkMessageBuffer frameworkBuffer;
    private int disconnectTicks;
    private int cacheTickCounter;
    /**
     * Resolved from {@code bot.observation.threat-radius} and {@code passive-radius} after
     * Spring context starts. Defaults to {@link #CACHE_SCAN_RADIUS_DEFAULT} until then.
     */
    private int cacheScanRadius = CACHE_SCAN_RADIUS_DEFAULT;

    @Override
    public void onInitializeClient() {
        LOGGER.info("McAgent Fabric mod initializing...");

        // Register Mixin-based event hooks
        ModEventHooks.setChatMessageCallback(this::onChatMessage);
        ModEventHooks.setTickCallback(this::onClientTick);
    }

    private void onChatMessage(net.minecraft.network.chat.Component message) {
        if (chatHandler != null) {
            chatHandler.onChatMessage(message.getString());
        }
    }

    private void onClientTick() {
        var mc = Minecraft.getInstance();
        if (mc == null) return;

        ClientPacketListener current = mc.getConnection();

        // Detect server join
        if (current != null && lastConnection == null) {
            disconnectTicks = 0;
            initSpringContext();
        }

        // Detect server disconnect with debounce to avoid false positives
        // during dimension changes, respawns, or brief connection hiccups.
        if (current == null && lastConnection != null) {
            disconnectTicks++;
            if (disconnectTicks == DISCONNECT_DEBOUNCE_TICKS) {
                LOGGER.info("Connection lost for {} ticks — shutting down McAgent.", DISCONNECT_DEBOUNCE_TICKS);
                shutdownSpringContext();
            }
        } else if (current != null) {
            disconnectTicks = 0;
        }

        lastConnection = current;

        // Baritone tick callback
        if (baritoneBridge != null) {
            baritoneBridge.onClientTick();
        }

        // Refresh the world-state cache every CACHE_UPDATE_INTERVAL_TICKS ticks.
        // We are already on the client thread here, so direct Minecraft reads are safe.
        if (worldStateCache != null && mc.level != null && mc.player != null) {
            if (++cacheTickCounter >= CACHE_UPDATE_INTERVAL_TICKS) {
                cacheTickCounter = 0;
                try {
                    WorldStateCache.Snapshot snap = WorldStateUpdater.snapshot(
                            mc.level, mc.player, cacheScanRadius);
                    worldStateCache.update(snap);
                } catch (Exception e) {
                    LOGGER.warn("WorldStateCache update failed: {}", e.getMessage());
                }
            }
        }

        if (autonomousObserver != null) {
            autonomousObserver.onTick();
        }
    }

    private void initSpringContext() {
        if (springContext != null && springContext.isActive()) {
            return;
        }

        LOGGER.info("McAgent client setup starting...");

        try {
            // Load .env from Fabric config directory (or fallbacks) before Spring starts.
            // This ensures API keys are available for ${...} placeholders in application.yml.
            Path envPath = FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("mc-agent.env");
            EnvLoader.load(envPath);

            springContext = new AnnotationConfigApplicationContext();
            springContext.getEnvironment().setActiveProfiles("dev");
            springContext.register(CoreApplication.class);
            springContext.register(FabricModConfig.class);
            springContext.refresh();

            LangChain4jService langChainService = springContext.getBean(LangChain4jService.class);
            BotOperations botOps = springContext.getBean(BotOperations.class);
            ChatService chatService = springContext.getBean(ChatService.class);

            baritoneBridge = (FabricBaritoneBridge) botOps;
            Counters counters = springContext.getBean(Counters.class);
            worldStateCache = springContext.getBean(WorldStateCache.class);
            baritoneBridge.setCache(worldStateCache, counters);

            // Initialize framework message buffer: throttled, deduplicated, batched
            frameworkBuffer = new FrameworkMessageBuffer(
                    32,           // capacity
                    250,          // throttle ms
                    msg -> langChainService.addFrameworkContext(msg),
                    counters
            );

            // Initialize event queue (inbound + outbound)
            botEventQueue = new BotEventQueue(frameworkBuffer, counters);

            // Wire Baritone progress callbacks to the framework buffer
            botOps.setProgressCallback(msg -> {
                if (botEventQueue != null) {
                    botEventQueue.publishFramework(msg);
                }
            });

            // Wire ChatService so the LLM's sendMessage tool posts to the outbound queue
            chatService.setSender(botEventQueue::enqueueOutbound);

            BotProperties botProperties = springContext.getBean(BotProperties.class);
            // Cache scan radius = the larger of threat and passive radii so we capture
            // both hostile and passive entities in a single pass each tick.
            cacheScanRadius = Math.max(
                    botProperties.getObservation().getThreatRadius(),
                    botProperties.getObservation().getPassiveRadius());
            JobTracker jobTracker = springContext.getBean(JobTracker.class);
            RunLog runLog = springContext.getBean(RunLog.class);
            SurvivalReflexes survivalReflexes = springContext.getBean(SurvivalReflexes.class);
            TaskExecutor taskExecutor = springContext.getBean(TaskExecutor.class);
            AutoModeDriver autoModeDriver = springContext.getBean(AutoModeDriver.class);
            RoutineService routineService = springContext.getBean(RoutineService.class);
            autonomousObserver = new AutonomousObserver(
                    botOps, botEventQueue, botProperties.getObservation(),
                    jobTracker, counters, runLog, botOps,
                    () -> {
                        var mc = net.minecraft.client.Minecraft.getInstance();
                        var player = (mc != null) ? mc.player : null;
                        if (player == null) return null;
                        return new com.mcagent.fabric.observer.AutonomousObserver.PlayerState(
                                player.isDeadOrDying(),
                                player.getBlockX(), player.getBlockY(), player.getBlockZ());
                    },
                    worldStateCache,
                    survivalReflexes,
                    botProperties,
                    taskExecutor,
                    botOps,
                    autoModeDriver,
                    routineService);

            PendingApprovalStore pendingApprovalStore = springContext.getBean(PendingApprovalStore.class);
            chatHandler = new FabricChatHandler(botEventQueue, autonomousObserver, botOps, jobTracker, counters,
                    runLog, taskExecutor, pendingApprovalStore);
            botEventQueue.start(langChainService);

            // Wire the current server address into the task executor for session scoping.
            String serverAddress = resolveServerAddress();
            ((DefaultTaskExecutor) taskExecutor).setServerAddress(serverAddress);

            // Attempt to resume a plan interrupted by a recent disconnect.
            SessionResumer sessionResumer = springContext.getBean(SessionResumer.class);
            sessionResumer.tryResume(serverAddress);

            LOGGER.info("McAgent initialized successfully. Beans: {}",
                    springContext.getBeanDefinitionCount());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize McAgent Spring context", e);
        }
    }

    /**
     * Resolve the current server address for session scoping.
     * Returns the server IP for multiplayer, or "single-player" for LAN/integrated.
     */
    private String resolveServerAddress() {
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.getCurrentServer() != null) {
                String ip = mc.getCurrentServer().ip;
                return (ip != null && !ip.isBlank()) ? ip : "single-player";
            }
        } catch (Exception e) {
            LOGGER.warn("Could not resolve server address: {}", e.getMessage());
        }
        return "single-player";
    }

    private void shutdownSpringContext() {
        // Detach Baritone callbacks first to prevent NPEs from in-flight events
        if (baritoneBridge != null) {
            baritoneBridge.setProgressCallback(msg -> {});
        }

        if (chatHandler != null) {
            chatHandler.shutdown();
            chatHandler = null;
        }
        if (botEventQueue != null) {
            botEventQueue.shutdown();
            botEventQueue = null;
        }
        frameworkBuffer = null;
        if (springContext != null) {
            springContext.close();
            springContext = null;
        }
        autonomousObserver = null;
        baritoneBridge = null;
        worldStateCache = null;
        disconnectTicks = 0;
        cacheTickCounter = 0;
        cacheScanRadius = CACHE_SCAN_RADIUS_DEFAULT;
        LOGGER.info("McAgent Spring context shut down.");
    }
}
