package com.mcagent.fabric;

import com.mcagent.core.config.BotProperties;
import com.mcagent.core.service.BotOperations;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mod-level Spring configuration that provides Minecraft-specific beans.
 * These beans bridge the Fabric/Baritone world to the pure-Java core.
 */
@Configuration
public class FabricModConfig {

    private final BotProperties botProperties;

    public FabricModConfig(BotProperties botProperties) {
        this.botProperties = botProperties;
    }

    /**
     * Apply the configured chat rate-limit interval to the static sender.
     * Runs once after the Spring context is fully assembled, so all
     * {@code BotProperties} bindings are in place.
     */
    @PostConstruct
    public void configureChatSender() {
        FabricChatSender.setMinIntervalMs(botProperties.getChat().getMinIntervalMs());
    }

    @Bean
    public BotOperations botOperations() {
        return new FabricBaritoneBridge();
    }
}
