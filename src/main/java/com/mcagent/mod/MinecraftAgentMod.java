package com.mcagent.mod;

import com.mcagent.core.CoreApplication;
import com.mcagent.core.service.BotOperations;
import com.mcagent.core.service.ChatService;
import com.mcagent.core.service.LangChain4jService;
import com.mcagent.mod.handler.ChatEventHandler;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Forge mod entry point for McAgent.
 * Bootstraps the Spring context and wires Minecraft event handlers to core services.
 */
@Slf4j
@Mod("mc_agent")
public class MinecraftAgentMod {

    private AnnotationConfigApplicationContext springContext;

    public MinecraftAgentMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);

        // Register ourselves for server and other game events on the Forge event bus
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        log.info("McAgent client setup starting...");

        try {
            // Start Spring Boot context (no web server, no banner)
            springContext = new AnnotationConfigApplicationContext();
            springContext.register(CoreApplication.class);
            springContext.register(com.mcagent.mod.config.ModSpringConfig.class);
            springContext.refresh();

            LangChain4jService langChainService = springContext.getBean(LangChain4jService.class);
            ChatEventHandler chatHandler = new ChatEventHandler(langChainService);

            // Register chat handler on the Forge event bus
            MinecraftForge.EVENT_BUS.register(chatHandler);

            // Wire Baritone progress callbacks:
            // 1. <framework> tagged messages go to LLM memory (game state context)
            // 2. Plain text goes to in-game chat (player visibility)
            BotOperations botOps = springContext.getBean(BotOperations.class);
            botOps.setProgressCallback(msg -> {
                langChainService.addFrameworkContext(msg);
                sendBotChatMessage(msg);
            });

            // Wire ChatService so the LLM's sendMessage tool actually posts to chat
            ChatService chatService = springContext.getBean(ChatService.class);
            chatService.setSender(this::sendBotChatMessage);

            log.info("McAgent initialized successfully.");
        } catch (Exception e) {
            log.error("Failed to initialize McAgent Spring context", e);
        }
    }

    /**
     * Send a message to Minecraft chat as the bot.
     */
    private void sendBotChatMessage(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String truncated = message.length() > 250 ? message.substring(0, 250) + "..." : message;
            try {
                mc.player.connection.sendChat(truncated);
            } catch (Exception e1) {
                try {
                    mc.player.connection.sendCommand(truncated);
                } catch (Exception e2) {
                    log.warn("Could not send chat message; logging locally: {}", truncated);
                    mc.gui.getChat().addMessage(Component.literal(truncated));
                }
            }
        }
    }
}
