package com.mcagent.fabric;

import com.mcagent.core.CoreApplication;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

    @Override
    public void onInitializeClient() {
        LOGGER.info("McAgent Fabric mod initializing...");

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            initSpringContext();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            shutdownSpringContext();
        });
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

            registerEventHandlers();

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

    private void registerEventHandlers() {
        // Chat message received
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && chatHandler != null) {
                chatHandler.onChatMessage(message.getString());
            }
        });

        // Client tick for Baritone state polling
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (baritoneBridge != null) {
                baritoneBridge.onClientTick();
            }
        });
    }
}
