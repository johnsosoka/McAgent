package com.mcagent.fabric;

import com.mcagent.core.CoreApplication;
import com.mcagent.core.config.EnvLoader;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
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

    private AnnotationConfigApplicationContext springContext;
    private FabricChatHandler chatHandler;
    private FabricBaritoneBridge baritoneBridge;
    private ClientPacketListener lastConnection;
    private BotEventQueue botEventQueue;
    private FrameworkMessageBuffer frameworkBuffer;
    private int disconnectTicks;

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

            // Initialize framework message buffer: throttled, deduplicated, batched
            frameworkBuffer = new FrameworkMessageBuffer(
                    32,           // capacity
                    250,          // throttle ms
                    msg -> langChainService.addFrameworkContext(msg)
            );

            // Initialize event queue (inbound + outbound)
            botEventQueue = new BotEventQueue(frameworkBuffer);

            // Wire Baritone progress callbacks to the framework buffer
            botOps.setProgressCallback(msg -> {
                if (botEventQueue != null) {
                    botEventQueue.publishFramework(msg);
                }
            });

            // Wire ChatService so the LLM's sendMessage tool posts to the outbound queue
            chatService.setSender(botEventQueue::enqueueOutbound);

            chatHandler = new FabricChatHandler(botEventQueue);
            botEventQueue.start(langChainService);

            LOGGER.info("McAgent initialized successfully. Beans: {}",
                    springContext.getBeanDefinitionCount());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize McAgent Spring context", e);
        }
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
        baritoneBridge = null;
        disconnectTicks = 0;
        LOGGER.info("McAgent Spring context shut down.");
    }
}
