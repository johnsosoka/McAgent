package com.mcagent.fabric;

import com.mcagent.core.CoreApplication;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Fabric client mod entry point for McAgent on Minecraft 26.1.2.
 * Bootstraps the Spring context and wires Minecraft event handlers to core services.
 */
public class McAgentFabricMod implements ClientModInitializer {
    public static final String MOD_ID = "mc_agent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private AnnotationConfigApplicationContext springContext;
    private FabricChatHandler chatHandler;
    private FabricBaritoneBridge baritoneBridge;
    private ClientPacketListener lastConnection;

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
            initSpringContext();
        }

        // Detect server disconnect
        if (current == null && lastConnection != null) {
            shutdownSpringContext();
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
            springContext = new AnnotationConfigApplicationContext();
            springContext.register(CoreApplication.class);
            springContext.register(FabricModConfig.class);
            springContext.refresh();

            LangChain4jService langChainService = springContext.getBean(LangChain4jService.class);
            BotOperations botOps = springContext.getBean(BotOperations.class);
            ChatService chatService = springContext.getBean(ChatService.class);

            baritoneBridge = (FabricBaritoneBridge) botOps;

            // Wire Baritone progress callbacks:
            // 1. <framework> tagged messages go to LLM memory (game state context)
            // 2. Plain text goes to in-game chat (player visibility)
            botOps.setProgressCallback(msg -> {
                langChainService.addFrameworkContext(msg);
                FabricChatSender.send(msg);
            });

            // Wire ChatService so the LLM's sendMessage tool actually posts to chat
            chatService.setSender(FabricChatSender::send);

            chatHandler = new FabricChatHandler(langChainService);

            LOGGER.info("McAgent initialized successfully. Beans: {}",
                    springContext.getBeanDefinitionCount());
        } catch (Exception e) {
            LOGGER.error("Failed to initialize McAgent Spring context", e);
        }
    }

    private void shutdownSpringContext() {
        if (chatHandler != null) {
            chatHandler.shutdown();
            chatHandler = null;
        }
        if (springContext != null) {
            springContext.close();
            springContext = null;
        }
        baritoneBridge = null;
        LOGGER.info("McAgent Spring context shut down.");
    }
}
